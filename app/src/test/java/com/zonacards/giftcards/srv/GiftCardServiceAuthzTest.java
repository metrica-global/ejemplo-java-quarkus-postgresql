package com.zonacards.giftcards.srv;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.errx.ErrorCode;
import com.zonacards.errx.Errx;
import com.zonacards.eventx.EventCodec;
import com.zonacards.eventx.EventFactory;
import com.zonacards.eventx.memory.RecordingOutbox;
import com.zonacards.giftcards.Fixtures;
import com.zonacards.giftcards.GiftCardRequests.EmitirGiftCardRequest;
import com.zonacards.giftcards.GiftCardRequests.ExtenderVigenciaRequest;
import com.zonacards.kernel.AuthContext;
import com.zonacards.kernel.GiftCardId;
import com.zonacards.kernel.KernelErrors;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.kernel.TenantId;
import com.zonacards.kernel.UnitOfWork;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Authorization comes first. The service has no repository, no card factory and a unit of work
 * that refuses to run: a use case that did anything before checking the caller would fail with
 * something other than the authorization error these tests expect. In particular, an
 * unauthorized caller must not be able to make the service spend CPU hashing a CVV.
 */
class GiftCardServiceAuthzTest {

  private static final UnitOfWork NO_TRANSACTIONS =
      new UnitOfWork() {
        @Override
        public <T> T inTx(Supplier<T> work) {
          throw new AssertionError("a transaction was opened before authorization");
        }
      };

  private final GiftCardService service =
      new GiftCardService(null, null, NO_TRANSACTIONS, new RecordingOutbox(), new EventFactory("/test", new EventCodec()), Fixtures.CLOCK);

  private Map<String, BiConsumer<AuthContext, TenantId>> useCases() {
    Map<String, BiConsumer<AuthContext, TenantId>> cases = new LinkedHashMap<>();
    GiftCardId id = GiftCardId.newId();
    LocalDate fecha = LocalDate.parse("2027-12-31");
    cases.put("giftcards:write emitir", (auth, tenant) -> service.emitir(auth, tenant, new EmitirGiftCardRequest("604", "PEN", BigDecimal.TEN, fecha)));
    cases.put("giftcards:read obtener", (auth, tenant) -> service.obtener(auth, tenant, id));
    cases.put("giftcards:read listar", (auth, tenant) -> service.listar(auth, tenant, null, null, PaginationOptions.of(1, 20)));
    cases.put("giftcards:write extenderVigencia", (auth, tenant) -> service.extenderVigencia(auth, tenant, id, new ExtenderVigenciaRequest(fecha, 0)));
    cases.put("giftcards:activar activar", (auth, tenant) -> service.activar(auth, tenant, id));
    cases.put("giftcards:anular anular", (auth, tenant) -> service.anular(auth, tenant, id, "motivo"));
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
  void eachScopeGrantsOnlyItsOwnUseCases() {
    for (String held : new String[] {"giftcards:read", "giftcards:write", "giftcards:activar", "giftcards:anular", "lotes:*"}) {
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
    AuthContext ana = Fixtures.user(Fixtures.PE, "giftcards:*");
    useCases()
        .forEach(
            (name, call) -> {
              Throwable error = assertThrows(Throwable.class, () -> call.accept(ana, Fixtures.PE), name);
              assertTrue(error instanceof AssertionError || error instanceof NullPointerException, name + " failed with " + error);
            });
  }
}
