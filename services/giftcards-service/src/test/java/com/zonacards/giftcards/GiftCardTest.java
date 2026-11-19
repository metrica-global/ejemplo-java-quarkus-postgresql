package com.zonacards.giftcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.errx.ErrorType;
import com.zonacards.errx.Errx;
import com.zonacards.giftcards.GiftCardRequests.EmitirGiftCardRequest;
import com.zonacards.giftcards.GiftCardRequests.ExtenderVigenciaRequest;
import com.zonacards.kernel.LoteId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class GiftCardTest {

  private static final LocalDate HOY = LocalDate.parse("2026-11-18");
  private static final LocalDate VIGENCIA = LocalDate.parse("2027-12-31");

  private static GiftCard nueva() {
    return GiftCard.nueva(
        Fixtures.PE, null, null, "604", "PEN", new BigDecimal("50"), VIGENCIA, Fixtures.protector().proteger("6041234567894321"), "hash", Fixtures.NOW);
  }

  @Test
  void aNewCardIsGeneratedAndHoldsNoSecretInTheClear() {
    GiftCard card = nueva();
    assertEquals(GiftCardEstado.GENERADA, card.estado());
    assertEquals(new BigDecimal("50.00"), card.monto());
    assertEquals("604*********4321", card.codigoMascara());
    assertNull(card.loteId());
    assertFalse(new String(card.codigoCifrado()).contains("6041234567894321"));
  }

  @Test
  void activationHappensOnceAndNotOnExpiredOrAnnulledCards() {
    GiftCard card = nueva();
    assertTrue(card.activar(HOY, Fixtures.NOW));
    assertEquals(GiftCardEstado.ACTIVA, card.estado());
    assertEquals(Fixtures.NOW, card.activadaEn());
    assertFalse(card.activar(HOY, Fixtures.NOW), "already active");

    GiftCard vencida = nueva();
    Errx expired = assertThrows(Errx.class, () -> vencida.activar(VIGENCIA.plusDays(1), Fixtures.NOW));
    assertTrue(expired.hasCode(GiftCardErrors.VENCIDA));
    assertTrue(nueva().activar(VIGENCIA, Fixtures.NOW), "the last day of validity still counts");

    GiftCard anulada = nueva();
    anulada.anular("extraviada", Fixtures.NOW);
    Errx invalid = assertThrows(Errx.class, () -> anulada.activar(HOY, Fixtures.NOW));
    assertTrue(invalid.hasCode(GiftCardErrors.ESTADO_INVALIDO));
    assertEquals(422, invalid.httpStatus());
  }

  @Test
  void annulmentIsFinalAndHappensOnce() {
    GiftCard card = nueva();
    card.activar(HOY, Fixtures.NOW);
    assertTrue(card.anular("extraviada", Fixtures.NOW));
    assertEquals(GiftCardEstado.ANULADA, card.estado());
    assertFalse(card.anular("otra vez", Fixtures.NOW));
    assertEquals("extraviada", card.motivo());
  }

  @Test
  void validityOnlyMovesForward() {
    GiftCard card = nueva();
    card.extenderVigencia(VIGENCIA.plusMonths(6), Fixtures.NOW);
    assertEquals(VIGENCIA.plusMonths(6), card.vigenciaHasta());
    Errx backwards = assertThrows(Errx.class, () -> card.extenderVigencia(VIGENCIA, Fixtures.NOW));
    assertEquals(ErrorType.VALIDATION, backwards.type());
    card.anular("x", Fixtures.NOW);
    assertThrows(Errx.class, () -> card.extenderVigencia(VIGENCIA.plusYears(2), Fixtures.NOW));
  }

  @Test
  void aCardOfALoteKnowsItsPosition() {
    LoteId lote = LoteId.newId();
    GiftCard card =
        GiftCard.nueva(Fixtures.PE, lote, 7, "604", "PEN", BigDecimal.TEN, VIGENCIA, Fixtures.protector().proteger("6041234567894321"), "h", Fixtures.NOW);
    assertEquals(lote, card.loteId());
    assertEquals(7, card.secuencia());
  }

  @Test
  void thePlanSplitsALoteIntoFragmentsThatCoverItExactly() {
    assertEquals(1, Plan.totalFragmentos(1, 1000));
    assertEquals(1, Plan.totalFragmentos(1000, 1000));
    assertEquals(2, Plan.totalFragmentos(1001, 1000));
    assertEquals(200, Plan.totalFragmentos(200_000, 1000));

    Plan plan = Plan.nuevo(LoteId.newId(), Fixtures.PE, "604", "PEN", BigDecimal.TEN, 2350, VIGENCIA, 1000);
    assertEquals(3, plan.totalFragmentos());
    assertEquals(new Plan.Fragmento(1, 1, 1000), plan.fragmento(1, 1000));
    assertEquals(new Plan.Fragmento(2, 1001, 1000), plan.fragmento(2, 1000));
    assertEquals(new Plan.Fragmento(3, 2001, 350), plan.fragmento(3, 1000));
  }

  @Test
  void requestsValidateTheirOwnShape() {
    new EmitirGiftCardRequest("604", "PEN", new BigDecimal("10.50"), VIGENCIA).validate();
    record Caso(String campo, EmitirGiftCardRequest request) {}
    for (Caso caso :
        List.of(
            new Caso("pais", new EmitirGiftCardRequest("PER", "PEN", BigDecimal.TEN, VIGENCIA)),
            new Caso("moneda", new EmitirGiftCardRequest("604", "soles", BigDecimal.TEN, VIGENCIA)),
            new Caso("monto", new EmitirGiftCardRequest("604", "PEN", null, VIGENCIA)),
            new Caso("monto", new EmitirGiftCardRequest("604", "PEN", new BigDecimal("-1"), VIGENCIA)),
            new Caso("monto", new EmitirGiftCardRequest("604", "PEN", new BigDecimal("0.001"), VIGENCIA)),
            new Caso("vigenciaHasta", new EmitirGiftCardRequest("604", "PEN", BigDecimal.TEN, null)))) {
      Errx error = assertThrows(Errx.class, caso.request()::validate, caso.campo());
      assertEquals(caso.campo(), error.details().get("field"));
    }
    assertEquals("version", assertThrows(Errx.class, () -> new ExtenderVigenciaRequest(VIGENCIA, null).validate()).details().get("field"));
    assertEquals("vigenciaHasta", assertThrows(Errx.class, () -> new ExtenderVigenciaRequest(null, 0).validate()).details().get("field"));
  }
}
