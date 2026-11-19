package com.zonacards.errx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class ErrxTest {

  private static final ErrorRegistry REGISTRY = new ErrorRegistry("LOTE");
  private static final ErrorCode NOT_FOUND = REGISTRY.register("NOT_FOUND", ErrorType.NOT_FOUND, "Lote not found");
  private static final ErrorCode ANNULLED = REGISTRY.register("ALREADY_ANNULLED", ErrorType.BUSINESS, 409, "Lote already annulled");

  @Test
  void registryPrefixesCodesAndKeepsTheHttpStatus() {
    assertEquals("LOTE_NOT_FOUND", NOT_FOUND.code());
    assertEquals(404, NOT_FOUND.httpStatus());
    assertEquals(409, ANNULLED.httpStatus());
    assertEquals(2, REGISTRY.codes().size());
  }

  @Test
  void registeringTheSameCodeTwiceFails() {
    ErrorRegistry registry = new ErrorRegistry("X");
    registry.register("A", ErrorType.INTERNAL, "a");
    assertThrows(IllegalStateException.class, () -> registry.register("A", ErrorType.INTERNAL, "again"));
  }

  @Test
  void errorsCompareByCodeNotByInstance() {
    Errx first = REGISTRY.create(NOT_FOUND);
    Errx second = REGISTRY.createWithMessage(NOT_FOUND, "another message").withDetail("id", "42");
    assertNotSame(first, second);
    assertTrue(first.is(second));
    assertTrue(second.hasCode(NOT_FOUND));
    assertFalse(first.is(REGISTRY.create(ANNULLED)));
  }

  @Test
  void wrapPreservesTheDomainErrorSemantics() {
    Errx domain = REGISTRY.create(NOT_FOUND).withDetail("id", "42");
    Errx wrapped = Errx.wrap(domain, "failed to load lote", ErrorType.INTERNAL);
    assertEquals("LOTE_NOT_FOUND", wrapped.code());
    assertEquals(ErrorType.NOT_FOUND, wrapped.type());
    assertEquals(404, wrapped.httpStatus());
    assertEquals("42", wrapped.details().get("id"));
    assertSame(domain, wrapped.getCause());
  }

  @Test
  void wrapOfAForeignErrorUsesTheGivenType() {
    Errx wrapped = Errx.wrap(new SQLException("connection refused"), "failed to save", ErrorType.INTERNAL);
    assertEquals("INTERNAL", wrapped.code());
    assertEquals(500, wrapped.httpStatus());
  }

  @Test
  void findWalksTheCauseChain() {
    Errx domain = REGISTRY.create(NOT_FOUND);
    RuntimeException outer = new RuntimeException("outer", new IllegalStateException("middle", domain));
    assertSame(domain, Errx.find(outer));
    assertTrue(Errx.isNotFound(outer));
    assertTrue(Errx.is(outer, NOT_FOUND));
    assertNull(Errx.find(new RuntimeException("no errx here")));
    assertFalse(Errx.isNotFound(new RuntimeException("x")));
  }

  @Test
  void httpResponseCarriesCodeTypeStatusAndDetails() {
    HttpErrorResponse response = REGISTRY.create(ANNULLED).withDetail("loteId", "L1").toHttpResponse();
    assertEquals("LOTE_ALREADY_ANNULLED", response.code());
    assertEquals("BUSINESS", response.type());
    assertEquals(409, response.status());
    assertEquals("L1", response.details().get("loteId"));
  }

  @Test
  void convenienceConstructorsMapToTheirTypes() {
    assertEquals(400, Errx.validation("x").httpStatus());
    assertEquals(401, Errx.unauthorized("x").httpStatus());
    assertEquals(404, Errx.notFound("x").httpStatus());
    assertEquals(409, Errx.conflict("x").httpStatus());
    assertEquals(422, Errx.business("x").httpStatus());
    assertEquals(502, Errx.external("x").httpStatus());
    assertEquals(500, Errx.internal("x").httpStatus());
  }
}
