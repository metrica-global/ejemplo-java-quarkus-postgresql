package com.zonacards.giftcards.srv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.errx.ErrorType;
import com.zonacards.errx.Errx;
import com.zonacards.eventx.EventCodec;
import com.zonacards.eventx.EventEnvelope;
import com.zonacards.eventx.EventFactory;
import com.zonacards.eventx.memory.RecordingOutbox;
import com.zonacards.giftcards.CodigoGenerator;
import com.zonacards.giftcards.CodigoProtector;
import com.zonacards.giftcards.CvvHasher;
import com.zonacards.giftcards.Fixtures;
import com.zonacards.giftcards.GiftCard;
import com.zonacards.giftcards.GiftCardErrors;
import com.zonacards.giftcards.GiftCardEstado;
import com.zonacards.giftcards.GiftCardRequests.EmitirGiftCardRequest;
import com.zonacards.giftcards.GiftCardRequests.ExtenderVigenciaRequest;
import com.zonacards.giftcards.InMemoryGiftCardRepository;
import com.zonacards.giftcards.events.GiftcardsEvents;
import com.zonacards.kernel.AuthContext;
import com.zonacards.kernel.GiftCardId;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.kernel.UnitOfWork;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GiftCardServiceTest {

  private static final AuthContext ANA = Fixtures.user(Fixtures.PE, "giftcards:*");
  private static final EventCodec CODEC = new EventCodec();
  private static final EmitirGiftCardRequest SOLICITUD =
      new EmitirGiftCardRequest("604", "PEN", new BigDecimal("50.00"), LocalDate.parse("2027-12-31"));

  private final CodigoProtector protector = Fixtures.protector();
  private final CvvHasher hasher = Fixtures.fastHasher();
  private InMemoryGiftCardRepository repository;
  private RecordingOutbox outbox;
  private GiftCardService service;

  @BeforeEach
  void setUp() {
    repository = new InMemoryGiftCardRepository();
    outbox = new RecordingOutbox();
    service = service(protector, Fixtures.CLOCK);
  }

  private GiftCardService service(CodigoProtector codigoProtector, Clock clock) {
    return new GiftCardService(
        repository,
        new GiftCardFactory(codigoProtector, hasher, new SecureRandom()),
        UnitOfWork.direct(),
        outbox,
        new EventFactory("/zonacards/giftcards", CODEC),
        clock);
  }

  /** A protector whose first {@code collisions} codes all get the blind index of an existing card. */
  private CodigoProtector colliding(byte[] existingIndex, int collisions) {
    AtomicInteger calls = new AtomicInteger();
    return new CodigoProtector() {
      @Override
      public Protegido proteger(String codigo) {
        Protegido real = protector.proteger(codigo);
        return calls.incrementAndGet() <= collisions ? new Protegido(real.cifrado(), existingIndex, real.mascara()) : real;
      }

      @Override
      public String revelar(byte[] cifrado) {
        return protector.revelar(cifrado);
      }
    };
  }

  @Test
  void issuingReturnsTheSecretsOnceAndStoresOnlyTheirProtectedForm() {
    GiftCardService.Emision emision = service.emitir(ANA, Fixtures.PE, SOLICITUD);

    assertTrue(CodigoGenerator.esValido(emision.codigo()));
    assertTrue(emision.codigo().startsWith("604"));
    assertTrue(emision.cvv().matches("\\d{3}"));

    GiftCard stored = service.obtener(ANA, Fixtures.PE, emision.card().id());
    assertEquals(GiftCardEstado.GENERADA, stored.estado());
    assertEquals(CodigoGenerator.mascara(emision.codigo()), stored.codigoMascara());
    assertEquals(emision.codigo(), protector.revelar(stored.codigoCifrado()));
    assertTrue(hasher.verificar(emision.cvv(), stored.cvvHash()));
    assertFalse(hasher.verificar("000".equals(emision.cvv()) ? "001" : "000", stored.cvvHash()));

    EventEnvelope event = outbox.ofType(GiftcardsEvents.GIFTCARD_EMITIDA).get(0);
    String wire = CODEC.encode(event);
    assertFalse(wire.contains(emision.codigo()), "the code never leaves in an event");
    assertFalse(wire.contains("cvv"));
    assertEquals(stored.id().value(), CODEC.data(event, GiftcardsEvents.GiftCardEmitida.class).giftCardId());
  }

  @Test
  void aCollidingCodeIsReplacedAndAnExhaustedCodeSpaceIsAnError() {
    GiftCard existing = service.emitir(ANA, Fixtures.PE, SOLICITUD).card();

    GiftCardService unlucky = service(colliding(existing.codigoIndice(), 3), Fixtures.CLOCK);
    GiftCardService.Emision emision = unlucky.emitir(ANA, Fixtures.PE, SOLICITUD);
    assertEquals(2, repository.size(), "the three colliding attempts stored nothing");
    assertEquals(emision.codigo(), protector.revelar(emision.card().codigoCifrado()));

    GiftCardService doomed = service(colliding(existing.codigoIndice(), Integer.MAX_VALUE), Fixtures.CLOCK);
    Errx error = assertThrows(Errx.class, () -> doomed.emitir(ANA, Fixtures.PE, SOLICITUD));
    assertTrue(error.hasCode(GiftCardErrors.CODIGO_NO_DISPONIBLE));
    assertEquals(2, repository.size());
    assertEquals(2, outbox.ofType(GiftcardsEvents.GIFTCARD_EMITIDA).size(), "no event without a stored card");
  }

  @Test
  void anInvalidRequestIssuesNothing() {
    EmitirGiftCardRequest vencida = new EmitirGiftCardRequest("604", "PEN", BigDecimal.TEN, LocalDate.parse("2026-11-18"));
    Errx error = assertThrows(Errx.class, () -> service.emitir(ANA, Fixtures.PE, vencida));
    assertEquals(ErrorType.VALIDATION, error.type());
    assertThrows(Errx.class, () -> service.emitir(ANA, Fixtures.PE, null));
    assertEquals(0, repository.size());
  }

  @Test
  void activationIsAnnouncedOnce() {
    GiftCardId id = service.emitir(ANA, Fixtures.PE, SOLICITUD).card().id();

    assertEquals(GiftCardEstado.ACTIVA, service.activar(ANA, Fixtures.PE, id).estado());
    assertEquals(GiftCardEstado.ACTIVA, service.activar(ANA, Fixtures.PE, id).estado());
    assertEquals(1, outbox.ofType(GiftcardsEvents.GIFTCARD_ACTIVADA).size());

    Clock later = Clock.fixed(Instant.parse("2028-01-01T00:00:00Z"), ZoneOffset.UTC);
    GiftCardId otra = service.emitir(ANA, Fixtures.PE, SOLICITUD).card().id();
    Errx expired = assertThrows(Errx.class, () -> service(protector, later).activar(ANA, Fixtures.PE, otra));
    assertTrue(expired.hasCode(GiftCardErrors.VENCIDA));
  }

  @Test
  void annulmentIsAnnouncedOnceAndBlocksActivation() {
    GiftCardId id = service.emitir(ANA, Fixtures.PE, SOLICITUD).card().id();

    GiftCard anulada = service.anular(ANA, Fixtures.PE, id, " Reportada como extraviada ");
    assertEquals(GiftCardEstado.ANULADA, anulada.estado());
    assertEquals("Reportada como extraviada", anulada.motivo());
    service.anular(ANA, Fixtures.PE, id, "otra vez");
    assertEquals(1, outbox.ofType(GiftcardsEvents.GIFTCARD_ANULADA).size());

    Errx invalid = assertThrows(Errx.class, () -> service.activar(ANA, Fixtures.PE, id));
    assertTrue(invalid.hasCode(GiftCardErrors.ESTADO_INVALIDO));
    assertTrue(assertThrows(Errx.class, () -> service.anular(ANA, Fixtures.PE, GiftCardId.newId(), null)).hasCode(GiftCardErrors.NOT_FOUND));
  }

  @Test
  void validityCanBeExtendedWithTheCurrentVersion() {
    GiftCardId id = service.emitir(ANA, Fixtures.PE, SOLICITUD).card().id();
    LocalDate nueva = LocalDate.parse("2028-06-30");

    GiftCard updated = service.extenderVigencia(ANA, Fixtures.PE, id, new ExtenderVigenciaRequest(nueva, 0));
    assertEquals(nueva, updated.vigenciaHasta());
    assertEquals(1, updated.version());
    Errx stale = assertThrows(Errx.class, () -> service.extenderVigencia(ANA, Fixtures.PE, id, new ExtenderVigenciaRequest(nueva.plusDays(1), 0)));
    assertTrue(stale.hasCode(GiftCardErrors.VERSION_CONFLICT));
    Errx backwards = assertThrows(Errx.class, () -> service.extenderVigencia(ANA, Fixtures.PE, id, new ExtenderVigenciaRequest(nueva, 1)));
    assertEquals(ErrorType.VALIDATION, backwards.type());
  }

  @Test
  void cardsAreInvisibleToOtherTenants() {
    GiftCardId id = service.emitir(ANA, Fixtures.PE, SOLICITUD).card().id();
    AuthContext otro = Fixtures.user(Fixtures.EC, "giftcards:*");

    assertTrue(assertThrows(Errx.class, () -> service.obtener(otro, Fixtures.EC, id)).hasCode(GiftCardErrors.NOT_FOUND));
    assertTrue(assertThrows(Errx.class, () -> service.activar(otro, Fixtures.EC, id)).hasCode(GiftCardErrors.NOT_FOUND));
    assertTrue(assertThrows(Errx.class, () -> service.anular(otro, Fixtures.EC, id, null)).hasCode(GiftCardErrors.NOT_FOUND));
    assertTrue(service.listar(otro, Fixtures.EC, null, null, PaginationOptions.of(1, 20)).empty());
    assertEquals(1, service.listar(ANA, Fixtures.PE, null, GiftCardEstado.GENERADA, PaginationOptions.of(1, 20)).page().total());
  }
}
