package com.zonacards.giftcards.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.giftcards.CodigoGenerator;
import com.zonacards.giftcards.GiftcardsTestResource;
import com.zonacards.testkit.ContractExamples;
import com.zonacards.testkit.EventSpy;
import com.zonacards.testkit.FlociFixture;
import com.zonacards.testkit.TestConfig;
import com.zonacards.testkit.TestTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * The application as a deployment that serves only the giftcards module ({@code
 * ZC_MODULES=giftcards}), which is how it runs when the module is promoted to its own service.
 * The lotes module is not served here: the test plays it by putting its events, built from the
 * contract examples, on the inbox queue. Everything after that is real: the plan, the commands
 * on the work queue, the workers, the cards in PostgreSQL and the events that come out.
 */
@QuarkusTest
@TestProfile(GiftcardsTestResource.OnlyGiftcards.class)
class GiftCardApiIT {

  private static final String GIFTCARDS = "/api/v1/giftcards";
  private static final Duration EVENTUALLY = Duration.ofSeconds(60);

  static TestTokens tokens;
  static FlociFixture floci;
  static EventSpy published;
  static String inboxUrl;
  static String workUrl;

  @BeforeAll
  static void connect() {
    Config config = ConfigProvider.getConfig();
    tokens = TestTokens.ofPrivateKey(config.getValue(TestConfig.JWT_PRIVATE_KEY, String.class));
    floci = FlociFixture.attach(URI.create(config.getValue(TestConfig.AWS_ENDPOINT, String.class)));
    inboxUrl = config.getValue(GiftcardsTestResource.INBOX_URL, String.class);
    workUrl = config.getValue(GiftcardsTestResource.WORK_URL, String.class);
    published = new EventSpy(floci, config.getValue(GiftcardsTestResource.SPY_URL, String.class));
  }

  private static RequestSpecification as(String token) {
    return given().auth().oauth2(token).contentType(ContentType.JSON);
  }

  private static RequestSpecification ana() {
    return as(tokens.user("pe", "ana", "giftcards:read", "giftcards:write", "giftcards:activar", "giftcards:anular"));
  }

  private static Map<String, Object> solicitud() {
    Map<String, Object> body = new HashMap<>();
    body.put("pais", "604");
    body.put("moneda", "PEN");
    body.put("monto", 75.50);
    body.put("vigenciaHasta", "2027-12-31");
    return body;
  }

  private static Response emitir() {
    Response response = ana().body(solicitud()).post(GIFTCARDS);
    response.then().statusCode(201);
    return response;
  }

  private static void deliver(String type, String body) {
    floci
        .sqs()
        .sendMessage(
            b ->
                b.queueUrl(inboxUrl)
                    .messageBody(body)
                    .messageAttributes(Map.of("type", MessageAttributeValue.builder().dataType("String").stringValue(type).build())));
  }

  private static int total(String query) {
    return ana().get(GIFTCARDS + query).then().statusCode(200).extract().path("page.total");
  }

  // --- security --------------------------------------------------------------------------------

  @Test
  void theApiRequiresAValidTokenTheRightScopeAndTheRightTenant() {
    given().get(GIFTCARDS).then().statusCode(401);
    as(tokens.user("pe", "ana", "lotes:read")).get(GIFTCARDS).then().statusCode(403).body("code", equalTo("AUTH_ACCESS_DENIED"));
    as(tokens.user("pe", "ana", "giftcards:read")).body(solicitud()).post(GIFTCARDS).then().statusCode(403);

    String id = emitir().path("id");
    as(tokens.user("ec", "eva", "giftcards:read")).get(GIFTCARDS + "/" + id).then().statusCode(404).body("code", equalTo("GIFTCARD_NOT_FOUND"));
    as(tokens.user("ec", "eva", "giftcards:anular")).delete(GIFTCARDS + "/" + id).then().statusCode(404);
    as(tokens.user("pe", "ana", "giftcards:read")).post(GIFTCARDS + "/" + id + "/activar").then().statusCode(403);
    ana().get(GIFTCARDS + "/" + id).then().statusCode(200).body("estado", equalTo("GENERADA"));
  }

  // --- single cards ----------------------------------------------------------------------------

  @Test
  void issuingShowsTheCodeAndCvvOnceAndNeverAgain() {
    Response response = emitir();
    response
        .then()
        .header("Cache-Control", containsString("no-store"))
        .header("Location", matchesPattern(".*/api/v1/giftcards/[0-9a-f-]{36}"))
        .body("estado", equalTo("GENERADA"))
        .body("monto", equalTo(75.50f))
        .body("cvv", matchesPattern("\\d{3}"))
        .body("loteId", nullValue());
    String id = response.path("id");
    String codigo = response.path("codigo");
    assertTrue(CodigoGenerator.esValido(codigo), codigo);
    assertTrue(codigo.startsWith("604"));
    assertEquals(CodigoGenerator.mascara(codigo), response.path("codigoEnmascarado"));

    String stored = ana().get(GIFTCARDS + "/" + id).then().statusCode(200).body("codigo", nullValue()).body("cvv", nullValue()).extract().asString();
    assertFalse(stored.contains(codigo), "the code is not readable after the emission");
    assertFalse(stored.contains("cvvHash") || stored.contains("codigoCifrado") || stored.contains("codigoIndice"), stored);

    EventSpy.Received emitted = published.await("zonacards.giftcards.giftcard-emitida.v1", id, EVENTUALLY);
    ContractExamples.assertMatches("zonacards.giftcards.giftcard-emitida.v1", emitted.event());
    assertFalse(emitted.raw().contains(codigo), "the code never travels in an event");
    assertTrue(emitted.raw().contains("\"monto\":75.50"), emitted.raw());
  }

  @Test
  void aCardIsActivatedAnnulledAndItsValidityExtended() {
    String id = emitir().path("id");

    ana().post(GIFTCARDS + "/" + id + "/activar").then().statusCode(200).body("estado", equalTo("ACTIVA")).body("activadaEn", notNullValue());
    ana().post(GIFTCARDS + "/" + id + "/activar").then().statusCode(200).body("estado", equalTo("ACTIVA"));
    EventSpy.Received activated = published.await("zonacards.giftcards.giftcard-activada.v1", id, EVENTUALLY);
    ContractExamples.assertMatches("zonacards.giftcards.giftcard-activada.v1", activated.event());
    assertEquals(1, published.count("zonacards.giftcards.giftcard-activada.v1", id, Duration.ofSeconds(2)));

    ana()
        .body(Map.of("vigenciaHasta", "2028-06-30", "version", 1))
        .put(GIFTCARDS + "/" + id)
        .then()
        .statusCode(200)
        .body("vigenciaHasta", equalTo("2028-06-30"))
        .body("version", equalTo(2));
    ana().body(Map.of("vigenciaHasta", "2029-01-01", "version", 1)).put(GIFTCARDS + "/" + id).then().statusCode(409).body("code", equalTo("GIFTCARD_VERSION_CONFLICT"));
    ana().body(Map.of("vigenciaHasta", "2027-01-01", "version", 2)).put(GIFTCARDS + "/" + id).then().statusCode(400).body("details.field", equalTo("vigenciaHasta"));

    ana()
        .delete(GIFTCARDS + "/" + id + "?motivo=Reportada como extraviada")
        .then()
        .statusCode(200)
        .body("estado", equalTo("ANULADA"))
        .body("motivo", equalTo("Reportada como extraviada"));
    EventSpy.Received annulled = published.await("zonacards.giftcards.giftcard-anulada.v1", id, EVENTUALLY);
    ContractExamples.assertMatches("zonacards.giftcards.giftcard-anulada.v1", annulled.event());
    ana().post(GIFTCARDS + "/" + id + "/activar").then().statusCode(422).body("code", equalTo("GIFTCARD_ESTADO_INVALIDO")).body("type", equalTo("BUSINESS"));
  }

  @Test
  void invalidRequestsAreRejected() {
    Map<String, Object> body = solicitud();
    body.put("pais", "PE");
    ana().body(body).post(GIFTCARDS).then().statusCode(400).body("details.field", equalTo("pais"));
    body = solicitud();
    body.put("vigenciaHasta", "2020-01-01");
    ana().body(body).post(GIFTCARDS).then().statusCode(400).body("details.field", equalTo("vigenciaHasta"));
    ana().body("{").post(GIFTCARDS).then().statusCode(400).body("code", equalTo("REQUEST_INVALID_BODY"));
    ana().get(GIFTCARDS + "/nope").then().statusCode(400).body("details.field", equalTo("id"));
    ana().get(GIFTCARDS + "?loteId=nope").then().statusCode(400).body("details.field", equalTo("loteId"));
    ana().get(GIFTCARDS + "?estado=ROTA").then().statusCode(400).body("details.field", equalTo("estado"));
    ana().get(GIFTCARDS + "/" + UUID.randomUUID()).then().statusCode(404);
  }

  // --- the saga, from this side ----------------------------------------------------------------

  @Test
  void aRequestedLoteIsGeneratedThroughTheWorkQueueAndThenAnnulled() {
    String lote = UUID.randomUUID().toString();
    String request = ContractExamples.instance("zonacards.lotes.lote-solicitado.v1", "pe", Map.of("loteId", lote, "cantidad", 450));
    deliver("zonacards.lotes.lote-solicitado.v1", request);
    deliver("zonacards.lotes.lote-solicitado.v1", request);

    await().atMost(EVENTUALLY).until(() -> total("?loteId=" + lote) == 450);

    EventSpy.Received started = published.await("zonacards.giftcards.generacion-iniciada.v1", lote, EVENTUALLY);
    ContractExamples.assertMatches("zonacards.giftcards.generacion-iniciada.v1", started.event());
    assertEquals(5, started.event().path("data").path("totalFragmentos").asInt());
    assertEquals(1, published.count("zonacards.giftcards.generacion-iniciada.v1", lote, Duration.ofSeconds(1)), "planned once");

    // One event per fragment, in whatever order the workers finished.
    published.await("zonacards.giftcards.fragmento-generado.v1", lote, EVENTUALLY);
    await().atMost(EVENTUALLY).until(() -> published.count("zonacards.giftcards.fragmento-generado.v1", lote, Duration.ofMillis(500)) == 5);
    Set<Integer> fragments = new HashSet<>();
    int cards = 0;
    for (EventSpy.Received received : published.all("zonacards.giftcards.fragmento-generado.v1", lote)) {
      ContractExamples.assertMatches("zonacards.giftcards.fragmento-generado.v1", received.event());
      assertEquals("zonacards.giftcards.fragmento-generado.v1", received.typeAttribute());
      fragments.add(received.event().path("data").path("fragmento").asInt());
      cards += received.event().path("data").path("cantidad").asInt();
    }
    assertEquals(Set.of(1, 2, 3, 4, 5), fragments);
    assertEquals(450, cards);

    // The cards: in order inside the lote, with the data of the request, secrets masked.
    ana()
        .get(GIFTCARDS + "?loteId=" + lote + "&pageSize=3")
        .then()
        .body("items", hasSize(3))
        .body("items[0].secuencia", equalTo(1))
        .body("items[2].secuencia", equalTo(3))
        .body("items[0].loteId", equalTo(lote))
        .body("items[0].estado", equalTo("GENERADA"))
        .body("items[0].pais", equalTo("604"))
        .body("items[0].monto", equalTo(50.00f))
        .body("items[0].codigoEnmascarado", matchesPattern("604\\*{9}\\d{4}"))
        .body("items[0].codigo", nullValue())
        .body("page.pages", equalTo(150));
    assertEquals(0, floci.depth(workUrl), "the work queue was drained");

    // Now the lote is annulled in the other domain.
    deliver("zonacards.lotes.lote-anulado.v1", ContractExamples.instance("zonacards.lotes.lote-anulado.v1", "pe", Map.of("loteId", lote)));
    await().atMost(EVENTUALLY).until(() -> total("?loteId=" + lote + "&estado=ANULADA") == 450);
    assertEquals(0, total("?loteId=" + lote + "&estado=GENERADA"));
    EventSpy.Received applied = published.await("zonacards.giftcards.lote-anulado-aplicado.v1", lote, EVENTUALLY);
    ContractExamples.assertMatches("zonacards.giftcards.lote-anulado-aplicado.v1", applied.event());
    assertEquals(450, applied.event().path("data").path("giftcardsAnuladas").asInt());
  }

  // --- operations ------------------------------------------------------------------------------

  @Test
  void healthAndMetricsDescribeBothConsumers() {
    given()
        .get("/q/health/live")
        .then()
        .statusCode(200)
        .body("checks.find { it.name == 'eventx-loops' }.data.'giftcards/outbox-relay'", equalTo("alive"))
        .body("checks.find { it.name == 'eventx-loops' }.data.'giftcards/consumer-giftcards-inbox'", equalTo("alive"))
        .body("checks.find { it.name == 'eventx-loops' }.data.'giftcards/consumer-giftcards-generacion'", equalTo("alive"));
    emitir();
    await()
        .atMost(EVENTUALLY)
        .untilAsserted(() -> given().get("/q/metrics").then().statusCode(200).body(containsString("zonacards_outbox_published_total")));
  }
}
