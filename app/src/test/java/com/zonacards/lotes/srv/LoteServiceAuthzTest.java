package com.zonacards.lotes.srv;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.errx.ErrorCode;
import com.zonacards.errx.Errx;
import com.zonacards.eventx.EventCodec;
import com.zonacards.eventx.EventFactory;
import com.zonacards.eventx.memory.RecordingOutbox;
import com.zonacards.kernel.AuthContext;
import com.zonacards.kernel.KernelErrors;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.kernel.TenantId;
import com.zonacards.kernel.UnitOfWork;
import com.zonacards.lotes.Fixtures;
import com.zonacards.lotes.LoteRequests.ActualizarLoteRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Authorization comes first. The service is built with no repository at all and a unit of work
 * that refuses to run: if any use case touched storage before deciding that the caller may
 * proceed, these tests would fail with a different error than the one they expect.
 */
class LoteServiceAuthzTest {

  private static final UnitOfWork NO_TRANSACTIONS =
      new UnitOfWork() {
        @Override
        public <T> T inTx(Supplier<T> work) {
          throw new AssertionError("a transaction was opened before authorization");
        }
      };

  private final LoteService service =
      new LoteService(null, NO_TRANSACTIONS, new RecordingOutbox(), new EventFactory("/test", new EventCodec()), Fixtures.CLOCK);

  /** Every use case, with the scope it requires. */
  private Map<String, BiConsumer<AuthContext, TenantId>> useCases() {
    Map<String, BiConsumer<AuthContext, TenantId>> cases = new LinkedHashMap<>();
    LoteId id = LoteId.newId();
    cases.put("lotes:write crear", (auth, tenant) -> service.crear(auth, tenant, Fixtures.solicitud(10), "key"));
    cases.put("lotes:read obtener", (auth, tenant) -> service.obtener(auth, tenant, id));
    cases.put("lotes:read listar", (auth, tenant) -> service.listar(auth, tenant, null, PaginationOptions.of(1, 20)));
    cases.put("lotes:write actualizar", (auth, tenant) -> service.actualizar(auth, tenant, id, new ActualizarLoteRequest("x", null, 0)));
    cases.put("lotes:anular anular", (auth, tenant) -> service.anular(auth, tenant, id, "motivo"));
    return cases;
  }

  private static void assertRejected(ErrorCode expected, Runnable call, String useCase) {
    Errx error = assertThrows(Errx.class, call::run, useCase);
    assertTrue(error.hasCode(expected), useCase + ": expected " + expected.code() + " but was " + error.code());
  }

  @Test
  void anAnonymousCallerIsRejectedEverywhere() {
    useCases().forEach((name, call) -> assertRejected(KernelErrors.UNAUTHORIZED, () -> call.accept(null, Fixtures.PE), name));
  }

  @Test
  void aCallerFromAnotherTenantIsRejectedEvenWithTheWildcard() {
    AuthContext intruso = Fixtures.user(Fixtures.EC, "*");
    useCases().forEach((name, call) -> assertRejected(KernelErrors.ACCESS_DENIED, () -> call.accept(intruso, Fixtures.PE), name));
  }

  @Test
  void aCallerWithoutTheRequiredScopeIsRejected() {
    AuthContext sinPermisos = Fixtures.user(Fixtures.PE, "giftcards:*");
    useCases().forEach((name, call) -> assertRejected(KernelErrors.ACCESS_DENIED, () -> call.accept(sinPermisos, Fixtures.PE), name));
  }

  @Test
  void eachScopeGrantsOnlyItsOwnUseCases() {
    for (String held : new String[] {"lotes:read", "lotes:write", "lotes:anular"}) {
      AuthContext auth = Fixtures.user(Fixtures.PE, held);
      useCases()
          .forEach(
              (name, call) -> {
                if (!name.startsWith(held + " ")) {
                  assertRejected(KernelErrors.ACCESS_DENIED, () -> call.accept(auth, Fixtures.PE), held + " must not allow " + name);
                }
              });
    }
  }

  @Test
  void anAuthorizedCallerGetsPastTheCheck() {
    // With the right scope the call reaches storage, which this test deliberately does not have.
    AuthContext ana = Fixtures.user(Fixtures.PE, "lotes:*");
    useCases().forEach((name, call) -> assertThrows(AssertionError.class, () -> call.accept(ana, Fixtures.PE), name));
  }
}
