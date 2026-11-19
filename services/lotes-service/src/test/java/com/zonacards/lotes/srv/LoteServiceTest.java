package com.zonacards.lotes.srv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.errx.ErrorType;
import com.zonacards.errx.Errx;
import com.zonacards.eventx.DestinationKind;
import com.zonacards.eventx.EventCodec;
import com.zonacards.eventx.EventEnvelope;
import com.zonacards.eventx.EventFactory;
import com.zonacards.eventx.memory.RecordingOutbox;
import com.zonacards.kernel.AuthContext;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.Paginated;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.kernel.UnitOfWork;
import com.zonacards.lotes.Fixtures;
import com.zonacards.lotes.InMemoryLoteRepository;
import com.zonacards.lotes.Lote;
import com.zonacards.lotes.LoteErrors;
import com.zonacards.lotes.LoteEstado;
import com.zonacards.lotes.LoteRequests.ActualizarLoteRequest;
import com.zonacards.lotes.LoteRequests.CrearLoteRequest;
import com.zonacards.lotes.events.LoteEvents;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The use cases, with in-memory fakes: no framework, no database, milliseconds. */
class LoteServiceTest {

  private static final AuthContext ANA = Fixtures.user(Fixtures.PE, "lotes:*");
  private static final EventCodec CODEC = new EventCodec();

  private InMemoryLoteRepository repository;
  private RecordingOutbox outbox;
  private LoteService service;

  @BeforeEach
  void setUp() {
    repository = new InMemoryLoteRepository();
    outbox = new RecordingOutbox();
    service = new LoteService(repository, UnitOfWork.direct(), outbox, new EventFactory("/zonacards/lotes", CODEC), Fixtures.CLOCK);
  }

  @Test
  void requestingALoteStoresItAndAnnouncesIt() {
    LoteService.Creacion creacion = service.crear(ANA, Fixtures.PE, Fixtures.solicitud(5000), "key-1");

    assertTrue(creacion.nuevo());
    Lote lote = creacion.lote();
    assertEquals(LoteEstado.SOLICITADO, lote.estado());
    assertEquals("ana", lote.creadoPor());
    assertEquals(1, outbox.all().size());
    RecordingOutbox.Recorded recorded = outbox.all().get(0);
    assertEquals(DestinationKind.TOPIC, recorded.kind());
    assertEquals(LoteEvents.TOPIC, recorded.destination());
    EventEnvelope event = recorded.envelope();
    assertEquals(LoteEvents.SOLICITADO, event.type());
    assertEquals(lote.id().value(), event.subject());
    assertEquals("pe", event.tenantid());
    LoteEvents.LoteSolicitado data = CODEC.data(event, LoteEvents.LoteSolicitado.class);
    assertEquals(new LoteEvents.LoteSolicitado(lote.id().value(), "604", "PEN", new BigDecimal("50.00"), 5000, LocalDate.parse("2027-12-31")), data);
  }

  @Test
  void aRetryWithTheSameKeyReturnsTheSameLoteAndPublishesNothing() {
    Lote first = service.crear(ANA, Fixtures.PE, Fixtures.solicitud(5000), "key-1").lote();
    LoteService.Creacion replay = service.crear(ANA, Fixtures.PE, Fixtures.solicitud(5000), " key-1 ");

    assertFalse(replay.nuevo());
    assertEquals(first.id(), replay.lote().id());
    assertEquals(1, repository.size());
    assertEquals(1, outbox.all().size(), "the event was announced once");
  }

  @Test
  void theSameKeyWithADifferentRequestIsAConflict() {
    service.crear(ANA, Fixtures.PE, Fixtures.solicitud(5000), "key-1");
    Errx error = assertThrows(Errx.class, () -> service.crear(ANA, Fixtures.PE, Fixtures.solicitud(6000), "key-1"));
    assertTrue(error.hasCode(LoteErrors.IDEMPOTENCY_KEY_REUSED));
    assertEquals(409, error.httpStatus());
    assertEquals(1, outbox.all().size());
  }

  @Test
  void theKeyIsRequiredAndBelongsToATenant() {
    Errx missing = assertThrows(Errx.class, () -> service.crear(ANA, Fixtures.PE, Fixtures.solicitud(10), " "));
    assertTrue(missing.hasCode(LoteErrors.IDEMPOTENCY_KEY_REQUIRED));

    Lote pe = service.crear(ANA, Fixtures.PE, Fixtures.solicitud(10), "shared-key").lote();
    Lote ec = service.crear(Fixtures.user(Fixtures.EC, "lotes:write"), Fixtures.EC, Fixtures.solicitud(10), "shared-key").lote();
    assertNotEquals(pe.id(), ec.id(), "two tenants may use the same key");
  }

  @Test
  void anInvalidRequestChangesNothing() {
    CrearLoteRequest invalid = new CrearLoteRequest("x", null, "604", "PEN", BigDecimal.ONE, 200_001, LocalDate.parse("2027-01-01"));
    Errx error = assertThrows(Errx.class, () -> service.crear(ANA, Fixtures.PE, invalid, "key-1"));
    assertEquals(ErrorType.VALIDATION, error.type());
    assertThrows(Errx.class, () -> service.crear(ANA, Fixtures.PE, null, "key-1"));
    assertEquals(0, repository.size());
    assertTrue(outbox.all().isEmpty());
  }

  @Test
  void readsAreScopedToTheTenant() {
    Lote lote = service.crear(ANA, Fixtures.PE, Fixtures.solicitud(10), "key-1").lote();
    assertEquals(lote.id(), service.obtener(ANA, Fixtures.PE, lote.id()).id());

    AuthContext otro = Fixtures.user(Fixtures.EC, "lotes:*");
    Errx error = assertThrows(Errx.class, () -> service.obtener(otro, Fixtures.EC, lote.id()));
    assertTrue(error.hasCode(LoteErrors.NOT_FOUND), "another tenant's lote does not exist, as far as this tenant can tell");
    assertTrue(service.listar(otro, Fixtures.EC, null, PaginationOptions.of(1, 20)).empty());
  }

  @Test
  void listingFiltersByStateAndPaginates() {
    for (int i = 0; i < 5; i++) {
      service.crear(ANA, Fixtures.PE, Fixtures.solicitud(10 + i), "key-" + i);
    }
    Lote anulado = service.crear(ANA, Fixtures.PE, Fixtures.solicitud(99), "key-x").lote();
    service.anular(ANA, Fixtures.PE, anulado.id(), null);

    Paginated<Lote> page = service.listar(ANA, Fixtures.PE, null, PaginationOptions.of(2, 4));
    assertEquals(6, page.page().total());
    assertEquals(2, page.items().size());
    assertEquals(2, page.page().pages());
    assertEquals(5, service.listar(ANA, Fixtures.PE, LoteEstado.SOLICITADO, PaginationOptions.of(1, 20)).page().total());
    assertEquals(1, service.listar(ANA, Fixtures.PE, LoteEstado.ANULADO, PaginationOptions.of(1, 20)).page().total());
  }

  @Test
  void updatingChangesDescriptiveDataAndDetectsLostUpdates() {
    Lote lote = service.crear(ANA, Fixtures.PE, Fixtures.solicitud(10), "key-1").lote();

    Lote updated = service.actualizar(ANA, Fixtures.PE, lote.id(), new ActualizarLoteRequest("Nueva", "OC-9", 0));
    assertEquals("Nueva", updated.descripcion());
    assertEquals(1, updated.version());
    assertEquals(1, outbox.all().size(), "descriptive changes are not announced to other domains");

    Errx stale = assertThrows(Errx.class, () -> service.actualizar(ANA, Fixtures.PE, lote.id(), new ActualizarLoteRequest("Otra", null, 0)));
    assertTrue(stale.hasCode(LoteErrors.VERSION_CONFLICT));
    Errx unknown = assertThrows(Errx.class, () -> service.actualizar(ANA, Fixtures.PE, LoteId.newId(), new ActualizarLoteRequest("x", null, 0)));
    assertTrue(unknown.hasCode(LoteErrors.NOT_FOUND));
  }

  @Test
  void annullingAnnouncesItOnceHoweverManyTimesItIsCalled() {
    Lote lote = service.crear(ANA, Fixtures.PE, Fixtures.solicitud(10), "key-1").lote();

    Lote anulado = service.anular(ANA, Fixtures.PE, lote.id(), "  Campaña cancelada ");
    assertEquals(LoteEstado.ANULADO, anulado.estado());
    assertEquals("Campaña cancelada", anulado.motivo());
    Lote again = service.anular(ANA, Fixtures.PE, lote.id(), "otro motivo");
    assertEquals("Campaña cancelada", again.motivo());

    assertEquals(1, outbox.ofType(LoteEvents.ANULADO).size());
    LoteEvents.LoteAnulado data = CODEC.data(outbox.ofType(LoteEvents.ANULADO).get(0), LoteEvents.LoteAnulado.class);
    assertEquals(new LoteEvents.LoteAnulado(lote.id().value(), "Campaña cancelada"), data);

    Errx unknown = assertThrows(Errx.class, () -> service.anular(ANA, Fixtures.PE, LoteId.newId(), null));
    assertTrue(unknown.hasCode(LoteErrors.NOT_FOUND));
    assertThrows(Errx.class, () -> service.anular(ANA, Fixtures.PE, lote.id(), "m".repeat(301)));
  }
}
