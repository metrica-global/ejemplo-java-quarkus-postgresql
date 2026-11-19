package com.zonacards.lotes.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.lotes.LotesTestResource;
import com.zonacards.testkit.ContractExamples;
import com.zonacards.testkit.EventSpy;
import com.zonacards.testkit.FlociFixture;
import com.zonacards.testkit.TestConfig;
import com.zonacards.testkit.TestTokens;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * The service as a whole: real HTTP, real token verification, PostgreSQL 18 with the production
 * roles, and the SNS/SQS emulator. The other domain is not running; the test plays its part by
 * putting on the inbox queue the events giftcards would publish, built from the contract
 * examples.
 */
@QuarkusTest
@QuarkusTestResource(LotesTestResource.class)
class LoteApiIT {

  private static final String LOTES = "/api/v1/lotes";
  private static final Duration EVENTUALLY = Duration.ofSeconds(30);

  static TestTokens tokens;
  static FlociFixture floci;
  static EventSpy published;
  static String inboxUrl;

  @BeforeAll
  static void connect() {
    Config config = ConfigProvider.getConfig();
    tokens = TestTokens.ofPrivateKey(config.getValue(TestConfig.JWT_PRIVATE_KEY, String.class));
    floci = FlociFixture.attach(URI.create(config.getValue(TestConfig.AWS_ENDPOINT, String.class)));
    inboxUrl = config.getValue(LotesTestResource.INBOX_URL, String.class);
    published = new EventSpy(floci, config.getValue(LotesTestResource.SPY_URL, String.class));
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static RequestSpecification as(String token) {
    return given().auth().oauth2(token).contentType(ContentType.JSON);
  }

  private static RequestSpecification ana() {
    return as(tokens.user("pe", "ana", "lotes:read", "lotes:write", "lotes:anular"));
  }

  private static Map<String, Object> solicitud(int cantidad) {
    Map<String, Object> body = new HashMap<>();
    body.put("descripcion", "Campaña de fin de año");
    body.put("referenciaExterna", "OC-2026-118");
    body.put("pais", "604");
    body.put("moneda", "PEN");
    body.put("monto", 50.00);
    body.put("cantidad", cantidad);
    body.put("vigenciaHasta", "2027-12-31");
    return body;
  }

  private static String crear(int cantidad) {
    return ana()
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .body(solicitud(cantidad))
        .post(LOTES)
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  /** Puts on the inbox queue an event of giftcards, as SNS would deliver it. */
  private static void deliver(String type, String body) {
    floci
        .sqs()
        .sendMessage(
            b ->
                b.queueUrl(inboxUrl)
                    .messageBody(body)
                    .messageAttributes(Map.of("type", MessageAttributeValue.builder().dataType("String").stringValue(type).build())));
  }

  private static String estado(String id) {
    return ana().get(LOTES + "/" + id).then().statusCode(200).extract().path("estado");
  }

  // --- security --------------------------------------------------------------------------------

  @Test
  void aRequestWithoutAValidTokenIsRejectedBeforeReachingTheApplication() {
    given().get(LOTES).then().statusCode(401);
    given().auth().oauth2("not-a-token").get(LOTES).then().statusCode(401);
    as(TestTokens.generate().user("pe", "mallory", "lotes:*")).get(LOTES).then().statusCode(401);
    as(tokens.forAnotherAudience("pe", "lotes:*")).get(LOTES).then().statusCode(401);
    as(tokens.expired("pe", "lotes:*")).get(LOTES).then().statusCode(401);
  }

  @Test
  void aValidTokenWithoutTheScopeIsForbiddenWithTheStandardErrorBody() {
    as(tokens.user("pe", "ana", "giftcards:read"))
        .get(LOTES)
        .then()
        .statusCode(403)
        .contentType(ContentType.JSON)
        .body("code", equalTo("AUTH_ACCESS_DENIED"))
        .body("type", equalTo("AUTHORIZATION"))
        .body("status", equalTo(403))
        .body("request_id", notNullValue());

    as(tokens.user("pe", "ana", "lotes:read"))
        .header("Idempotency-Key", "k")
        .body(solicitud(10))
        .post(LOTES)
        .then()
        .statusCode(403);
  }

  @Test
  void aTokenWithoutATenantIsNotAnIdentity() {
    as(tokens.user(null, "nadie", "lotes:*")).get(LOTES).then().statusCode(401).body("code", equalTo("AUTH_UNAUTHORIZED"));
  }

  @Test
  void aMachineClientIsAnActorToo() {
    String id =
        as(tokens.client("pe", "erp-integracion", "lotes:write", "lotes:read"))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(solicitud(10))
            .post(LOTES)
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    as(tokens.client("pe", "erp-integracion", "lotes:read")).get(LOTES + "/" + id).then().statusCode(200);
  }

  @Test
  void oneTenantCannotSeeOrTouchTheLotesOfAnother() {
    String id = crear(10);
    RequestSpecification otroPais = as(tokens.user("ec", "eva", "lotes:read", "lotes:write", "lotes:anular"));
    otroPais.get(LOTES + "/" + id).then().statusCode(404).body("code", equalTo("LOTE_NOT_FOUND"));
    as(tokens.user("ec", "eva", "lotes:anular")).delete(LOTES + "/" + id).then().statusCode(404);
    as(tokens.user("ec", "eva", "lotes:write"))
        .body(Map.of("descripcion", "x", "version", 0))
        .put(LOTES + "/" + id)
        .then()
        .statusCode(404);
    assertEquals("SOLICITADO", estado(id));
  }

  // --- create ----------------------------------------------------------------------------------

  @Test
  void creatingALoteAnswersAtOnceAndPublishesItsEvent() {
    String requestId = "it-" + UUID.randomUUID();
    Response response =
        ana().header("Idempotency-Key", UUID.randomUUID().toString()).header("X-Request-ID", requestId).body(solicitud(5000)).post(LOTES);

    response
        .then()
        .statusCode(201)
        .header("X-Request-ID", requestId)
        .header("Location", matchesPattern(".*/api/v1/lotes/[0-9a-f-]{36}"))
        .body("estado", equalTo("SOLICITADO"))
        .body("cantidad", equalTo(5000))
        .body("monto", equalTo(50.00f))
        .body("progreso.generadas", equalTo(0))
        .body("version", equalTo(0))
        .body("idempotencyKey", nullValue())
        .body("requestHash", nullValue());
    String id = response.path("id");

    EventSpy.Received received = published.await("zonacards.lotes.lote-solicitado.v1", id, EVENTUALLY);
    ContractExamples.assertMatches("zonacards.lotes.lote-solicitado.v1", received.event());
    assertEquals("zonacards.lotes.lote-solicitado.v1", received.typeAttribute(), "the type travels as an attribute for SNS filters");
    assertEquals("1.0", received.event().path("specversion").asText());
    assertEquals("/zonacards/lotes", received.event().path("source").asText());
    assertEquals("pe", received.event().path("tenantid").asText());
    assertEquals(requestId, received.event().path("correlationid").asText(), "the request id follows the event");
    assertEquals(id, received.event().path("data").path("loteId").asText());
    assertTrue(received.raw().contains("\"monto\":50.00"), "amounts travel as exact decimals: " + received.raw());
    assertEquals(5000, received.event().path("data").path("cantidad").asInt());
  }

  @Test
  void aRetriedPostWithTheSameKeyCreatesNothingNew() {
    String key = UUID.randomUUID().toString();
    String id = ana().header("Idempotency-Key", key).body(solicitud(700)).post(LOTES).then().statusCode(201).extract().path("id");

    ana().header("Idempotency-Key", key).body(solicitud(700)).post(LOTES).then().statusCode(200).body("id", equalTo(id));
    ana()
        .header("Idempotency-Key", key)
        .body(solicitud(701))
        .post(LOTES)
        .then()
        .statusCode(409)
        .body("code", equalTo("LOTE_IDEMPOTENCY_KEY_REUSED"));

    published.await("zonacards.lotes.lote-solicitado.v1", id, EVENTUALLY);
    assertEquals(1, published.count("zonacards.lotes.lote-solicitado.v1", id, Duration.ofSeconds(2)), "one lote, one event");
  }

  @Test
  void invalidRequestsAreRejectedWithTheFieldThatFailed() {
    ana().body(solicitud(10)).post(LOTES).then().statusCode(400).body("code", equalTo("LOTE_IDEMPOTENCY_KEY_REQUIRED"));
    ana()
        .header("Idempotency-Key", "k1")
        .body(solicitud(200_001))
        .post(LOTES)
        .then()
        .statusCode(400)
        .body("type", equalTo("VALIDATION"))
        .body("details.field", equalTo("cantidad"));
    ana()
        .header("Idempotency-Key", "k2")
        .body("{ this is not json")
        .post(LOTES)
        .then()
        .statusCode(400)
        .body("code", equalTo("REQUEST_INVALID_BODY"));
    ana()
        .header("Idempotency-Key", "k3")
        .body("{\"cantidad\": \"muchas\"}")
        .post(LOTES)
        .then()
        .statusCode(400)
        .body("code", equalTo("REQUEST_INVALID_BODY"));
    ana().header("Idempotency-Key", "k4").post(LOTES).then().statusCode(400);
    ana().get(LOTES + "/not-a-uuid").then().statusCode(400).body("details.field", equalTo("id"));
    ana().get(LOTES + "?estado=INVENTADO").then().statusCode(400).body("details.field", equalTo("estado"));
    ana().get(LOTES + "/" + UUID.randomUUID()).then().statusCode(404).body("code", equalTo("LOTE_NOT_FOUND"));
  }

  // --- read and update -------------------------------------------------------------------------

  @Test
  void lotesAreListedByPageAndFilteredByState() {
    RequestSpecification bo = as(tokens.user("bo", "beto", "lotes:read", "lotes:write", "lotes:anular"));
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      ids.add(
          as(tokens.user("bo", "beto", "lotes:write"))
              .header("Idempotency-Key", UUID.randomUUID().toString())
              .body(solicitud(10 + i))
              .post(LOTES)
              .then()
              .statusCode(201)
              .extract()
              .path("id"));
    }
    as(tokens.user("bo", "beto", "lotes:anular")).delete(LOTES + "/" + ids.get(0)).then().statusCode(200);

    bo.get(LOTES + "?page=1&pageSize=2")
        .then()
        .statusCode(200)
        .body("items", hasSize(2))
        .body("page.total", equalTo(3))
        .body("page.pages", equalTo(2))
        .body("items[0].id", equalTo(ids.get(2)));
    as(tokens.user("bo", "beto", "lotes:read"))
        .get(LOTES + "?estado=anulado")
        .then()
        .statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].id", equalTo(ids.get(0)));
  }

  @Test
  void updatingChangesDescriptiveDataAndRejectsAStaleVersion() {
    String id = crear(10);
    ana()
        .body(Map.of("descripcion", "Campaña renombrada", "referenciaExterna", "OC-7", "version", 0))
        .put(LOTES + "/" + id)
        .then()
        .statusCode(200)
        .body("descripcion", equalTo("Campaña renombrada"))
        .body("version", equalTo(1))
        .body("cantidad", equalTo(10));
    ana()
        .body(Map.of("descripcion", "Otra", "version", 0))
        .put(LOTES + "/" + id)
        .then()
        .statusCode(409)
        .body("code", equalTo("LOTE_VERSION_CONFLICT"));
  }

  // --- the saga, from this side ----------------------------------------------------------------

  @Test
  void eventsFromGiftcardsDriveTheLoteToGeneratedWhateverTheirOrder() {
    String id = crear(4000);
    List<String> deliveries = new ArrayList<>();
    deliveries.add(ContractExamples.instance("zonacards.giftcards.generacion-iniciada.v1", "pe", Map.of("loteId", id, "totalFragmentos", 4)));
    for (int n = 1; n <= 4; n++) {
      deliveries.add(
          ContractExamples.instance(
              "zonacards.giftcards.fragmento-generado.v1", "pe", Map.of("loteId", id, "fragmento", n, "totalFragmentos", 4, "cantidad", 1000)));
    }
    // At-least-once and unordered: every event twice, in random order.
    List<String> shuffled = new ArrayList<>(deliveries);
    shuffled.addAll(deliveries);
    Collections.shuffle(shuffled, new Random(7));
    for (String body : shuffled) {
      deliver(body.contains("fragmento-generado") ? "zonacards.giftcards.fragmento-generado.v1" : "zonacards.giftcards.generacion-iniciada.v1", body);
    }

    await().atMost(EVENTUALLY).until(() -> "GENERADO".equals(estado(id)));
    ana()
        .get(LOTES + "/" + id)
        .then()
        .body("progreso.fragmentosTotales", equalTo(4))
        .body("progreso.fragmentosCompletados", equalTo(4))
        .body("progreso.generadas", equalTo(4000));

    EventSpy.Received generado = published.await("zonacards.lotes.lote-generado.v1", id, EVENTUALLY);
    ContractExamples.assertMatches("zonacards.lotes.lote-generado.v1", generado.event());
    assertEquals(4000, generado.event().path("data").path("cantidad").asInt());
    assertEquals(1, published.count("zonacards.lotes.lote-generado.v1", id, Duration.ofSeconds(3)), "announced exactly once");
  }

  @Test
  void annullingIsAnEventAndItsConfirmationComesBackAsAnother() {
    String id = crear(2000);
    deliver(
        "zonacards.giftcards.fragmento-generado.v1",
        ContractExamples.instance(
            "zonacards.giftcards.fragmento-generado.v1", "pe", Map.of("loteId", id, "fragmento", 1, "totalFragmentos", 2, "cantidad", 1000)));
    await().atMost(EVENTUALLY).until(() -> "EN_GENERACION".equals(estado(id)));

    ana()
        .delete(LOTES + "/" + id + "?motivo=Campaña cancelada")
        .then()
        .statusCode(200)
        .body("estado", equalTo("ANULADO"))
        .body("motivo", equalTo("Campaña cancelada"));
    ana().delete(LOTES + "/" + id).then().statusCode(200).body("motivo", equalTo("Campaña cancelada"));

    EventSpy.Received anulado = published.await("zonacards.lotes.lote-anulado.v1", id, EVENTUALLY);
    ContractExamples.assertMatches("zonacards.lotes.lote-anulado.v1", anulado.event());
    assertEquals(1, published.count("zonacards.lotes.lote-anulado.v1", id, Duration.ofSeconds(2)), "annulled twice, announced once");

    // A fragment that was already in flight arrives late, then the confirmation of the annulment.
    deliver(
        "zonacards.giftcards.fragmento-generado.v1",
        ContractExamples.instance(
            "zonacards.giftcards.fragmento-generado.v1", "pe", Map.of("loteId", id, "fragmento", 2, "totalFragmentos", 2, "cantidad", 1000)));
    deliver(
        "zonacards.giftcards.lote-anulado-aplicado.v1",
        ContractExamples.instance("zonacards.giftcards.lote-anulado-aplicado.v1", "pe", Map.of("loteId", id, "giftcardsAnuladas", 2000)));

    await()
        .atMost(EVENTUALLY)
        .untilAsserted(() -> ana().get(LOTES + "/" + id).then().body("anulacionAplicadaEn", notNullValue()).body("giftcardsAnuladas", equalTo(2000)));
    ana().get(LOTES + "/" + id).then().body("estado", equalTo("ANULADO")).body("progreso.generadas", equalTo(1000));
    assertEquals(0, published.count("zonacards.lotes.lote-generado.v1", id, Duration.ofSeconds(2)), "an annulled lote is never announced as generated");
  }

  @Test
  void aFailedFragmentFailsTheLoteAndAnnouncesIt() {
    String id = crear(3000);
    deliver(
        "zonacards.giftcards.fragmento-fallido.v1",
        ContractExamples.instance("zonacards.giftcards.fragmento-fallido.v1", "pe", Map.of("loteId", id, "fragmento", 2, "motivo", "sin espacio de códigos")));

    await().atMost(EVENTUALLY).until(() -> "FALLIDO".equals(estado(id)));
    ana().get(LOTES + "/" + id).then().body("motivo", containsString("Fragmento 2"));
    EventSpy.Received fallido = published.await("zonacards.lotes.lote-fallido.v1", id, EVENTUALLY);
    ContractExamples.assertMatches("zonacards.lotes.lote-fallido.v1", fallido.event());
  }

  @Test
  void anEventForAnotherTenantCannotChangeALote() {
    String id = crear(1000);
    String forged =
        ContractExamples.instance(
            "zonacards.giftcards.fragmento-generado.v1", "ec", Map.of("loteId", id, "fragmento", 1, "totalFragmentos", 1, "cantidad", 1000));
    deliver("zonacards.giftcards.fragmento-generado.v1", forged);
    String marker = crear(1000);
    deliver(
        "zonacards.giftcards.fragmento-generado.v1",
        ContractExamples.instance(
            "zonacards.giftcards.fragmento-generado.v1", "pe", Map.of("loteId", marker, "fragmento", 1, "totalFragmentos", 1, "cantidad", 1000)));

    await().atMost(EVENTUALLY).until(() -> "GENERADO".equals(estado(marker)));
    assertEquals("SOLICITADO", estado(id), "the event named a lote of another tenant and was discarded");
    assertNotEquals(id, marker);
  }

  // --- operations ------------------------------------------------------------------------------

  @Test
  void healthAndMetricsDescribeTheEventLoops() {
    given()
        .get("/q/health/live")
        .then()
        .statusCode(200)
        .body("status", equalTo("UP"))
        .body("checks.find { it.name == 'eventx-loops' }.data.'outbox-relay'", equalTo("alive"))
        .body("checks.find { it.name == 'eventx-loops' }.data.'consumer-lotes-inbox'", equalTo("alive"));
    given().get("/q/health/ready").then().statusCode(200);
    crear(10);
    await()
        .atMost(EVENTUALLY)
        .untilAsserted(
            () ->
                given()
                    .get("/q/metrics")
                    .then()
                    .statusCode(200)
                    .body(containsString("zonacards_outbox_pending"))
                    .body(containsString("zonacards_outbox_oldest_pending_seconds"))
                    .body(containsString("zonacards_outbox_published_total")));
  }
}
