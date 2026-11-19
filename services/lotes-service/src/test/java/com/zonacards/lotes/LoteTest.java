package com.zonacards.lotes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.errx.ErrorType;
import com.zonacards.errx.Errx;
import com.zonacards.lotes.LoteRequests.ActualizarLoteRequest;
import com.zonacards.lotes.LoteRequests.CrearLoteRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoteTest {

  private static Lote nuevo(int cantidad) {
    return Lote.solicitar(Fixtures.PE, Fixtures.solicitud(cantidad), "key-1", "hash", "ana", Fixtures.NOW);
  }

  @Test
  void aNewLoteStartsRequested() {
    Lote lote = nuevo(3000);
    assertEquals(LoteEstado.SOLICITADO, lote.estado());
    assertEquals(new BigDecimal("50.00"), lote.monto());
    assertEquals(0, lote.version());
    assertEquals(0, lote.generadas());
    assertEquals(36, lote.id().value().length());
  }

  @Test
  void theValidityDateMustBeInTheFuture() {
    CrearLoteRequest vencido =
        new CrearLoteRequest("x", null, "604", "PEN", BigDecimal.TEN, 1, LocalDate.parse("2026-11-18"));
    Errx error = assertThrows(Errx.class, () -> Lote.solicitar(Fixtures.PE, vencido, "k", "h", "ana", Fixtures.NOW));
    assertEquals(ErrorType.VALIDATION, error.type());
    assertEquals("vigenciaHasta", error.details().get("field"));
  }

  @Test
  void fragmentsAdvanceTheLoteUntilItIsGenerated() {
    Lote lote = nuevo(3000);
    assertTrue(lote.iniciarGeneracion(3, Fixtures.NOW));
    assertEquals(LoteEstado.EN_GENERACION, lote.estado());
    assertEquals(Lote.Progreso.REGISTRADO, lote.registrarFragmento(3, 1000, Fixtures.NOW));
    assertEquals(Lote.Progreso.REGISTRADO, lote.registrarFragmento(3, 1000, Fixtures.NOW));
    assertEquals(Lote.Progreso.COMPLETADO, lote.registrarFragmento(3, 1000, Fixtures.NOW));
    assertEquals(LoteEstado.GENERADO, lote.estado());
    assertEquals(3000, lote.generadas());
    assertEquals(Lote.Progreso.TARDIO, lote.registrarFragmento(3, 1000, Fixtures.NOW), "nothing counts after the end");
    assertEquals(3000, lote.generadas());
  }

  @Test
  void aFragmentMayArriveBeforeTheGenerationStartedEvent() {
    Lote lote = nuevo(2000);
    assertEquals(Lote.Progreso.REGISTRADO, lote.registrarFragmento(2, 1000, Fixtures.NOW));
    assertEquals(LoteEstado.EN_GENERACION, lote.estado());
    assertEquals(2, lote.fragmentosTotales());
    assertFalse(lote.iniciarGeneracion(2, Fixtures.NOW), "the late 'started' event changes nothing");
    assertEquals(Lote.Progreso.COMPLETADO, lote.registrarFragmento(2, 1000, Fixtures.NOW));
  }

  @Test
  void aLoteCanBeAnnulledInAnyStateButOnlyOnce() {
    for (int fragmentsBefore : List.of(0, 1, 2)) {
      Lote lote = nuevo(2000);
      for (int i = 0; i < fragmentsBefore; i++) {
        lote.registrarFragmento(2, 1000, Fixtures.NOW);
      }
      assertTrue(lote.anular("Campaña cancelada", Fixtures.NOW));
      assertEquals(LoteEstado.ANULADO, lote.estado());
      assertEquals("Campaña cancelada", lote.motivo());
      assertFalse(lote.anular("otra vez", Fixtures.NOW));
      assertEquals("Campaña cancelada", lote.motivo());
      assertEquals(Lote.Progreso.TARDIO, lote.registrarFragmento(2, 1000, Fixtures.NOW), "no progress on an annulled lote");
      assertFalse(lote.fallar("tarde", Fixtures.NOW));
      assertFalse(lote.iniciarGeneracion(2, Fixtures.NOW));
    }
  }

  @Test
  void theAnnulmentIsConfirmedOnlyOnAnAnnulledLote() {
    Lote lote = nuevo(1000);
    lote.confirmarAnulacion(10, Fixtures.NOW);
    assertNull(lote.anulacionAplicadaEn());
    lote.anular("x", Fixtures.NOW);
    lote.confirmarAnulacion(10, Fixtures.NOW);
    assertEquals(Fixtures.NOW, lote.anulacionAplicadaEn());
    assertEquals(10, lote.giftcardsAnuladas());
  }

  @Test
  void aFailedGenerationIsTerminalButCanStillBeAnnulled() {
    Lote lote = nuevo(2000);
    lote.registrarFragmento(2, 1000, Fixtures.NOW);
    assertTrue(lote.fallar("Fragmento 2: sin espacio de códigos", Fixtures.NOW));
    assertEquals(LoteEstado.FALLIDO, lote.estado());
    assertFalse(lote.fallar("otra vez", Fixtures.NOW));
    assertEquals(Lote.Progreso.TARDIO, lote.registrarFragmento(2, 1000, Fixtures.NOW));
    assertTrue(lote.anular("limpieza", Fixtures.NOW), "annulling a failed lote cleans up the cards already generated");
  }

  @Test
  void onlyDescriptiveDataCanChangeAndNotOnAnAnnulledLote() {
    Lote lote = nuevo(1000);
    lote.actualizarMetadatos("  Nueva descripción ", "OC-2", Fixtures.NOW);
    assertEquals("Nueva descripción", lote.descripcion());
    assertEquals("OC-2", lote.referenciaExterna());
    lote.anular("x", Fixtures.NOW);
    Errx error = assertThrows(Errx.class, () -> lote.actualizarMetadatos("y", null, Fixtures.NOW));
    assertTrue(error.hasCode(LoteErrors.ESTADO_INVALIDO));
  }

  @Test
  void requestsValidateTheirOwnShape() {
    Fixtures.solicitud(1).validate();
    Fixtures.solicitud(LoteRequests.CANTIDAD_MAXIMA).validate();
    LocalDate fecha = LocalDate.parse("2027-01-01");
    BigDecimal monto = new BigDecimal("10.50");
    record Caso(String campo, CrearLoteRequest request) {}
    List<Caso> invalidos =
        List.of(
            new Caso("descripcion", new CrearLoteRequest(" ", null, "604", "PEN", monto, 1, fecha)),
            new Caso("descripcion", new CrearLoteRequest("x".repeat(201), null, "604", "PEN", monto, 1, fecha)),
            new Caso("referenciaExterna", new CrearLoteRequest("x", "r".repeat(101), "604", "PEN", monto, 1, fecha)),
            new Caso("pais", new CrearLoteRequest("x", null, "PE", "PEN", monto, 1, fecha)),
            new Caso("pais", new CrearLoteRequest("x", null, null, "PEN", monto, 1, fecha)),
            new Caso("moneda", new CrearLoteRequest("x", null, "604", "pen", monto, 1, fecha)),
            new Caso("monto", new CrearLoteRequest("x", null, "604", "PEN", null, 1, fecha)),
            new Caso("monto", new CrearLoteRequest("x", null, "604", "PEN", BigDecimal.ZERO, 1, fecha)),
            new Caso("monto", new CrearLoteRequest("x", null, "604", "PEN", new BigDecimal("1.005"), 1, fecha)),
            new Caso("cantidad", new CrearLoteRequest("x", null, "604", "PEN", monto, null, fecha)),
            new Caso("cantidad", new CrearLoteRequest("x", null, "604", "PEN", monto, 0, fecha)),
            new Caso("cantidad", new CrearLoteRequest("x", null, "604", "PEN", monto, 200_001, fecha)),
            new Caso("vigenciaHasta", new CrearLoteRequest("x", null, "604", "PEN", monto, 1, null)));
    for (Caso caso : invalidos) {
      Errx error = assertThrows(Errx.class, caso.request()::validate, caso.campo());
      assertEquals(ErrorType.VALIDATION, error.type());
      assertEquals(caso.campo(), error.details().get("field"));
    }
    Errx sinVersion = assertThrows(Errx.class, () -> new ActualizarLoteRequest("x", null, null).validate());
    assertEquals("version", sinVersion.details().get("field"));
  }
}
