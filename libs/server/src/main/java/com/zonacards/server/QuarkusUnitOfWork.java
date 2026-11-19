package com.zonacards.server;

import com.zonacards.kernel.UnitOfWork;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.function.Supplier;

/**
 * The unit of work inside Quarkus: a JTA transaction, joined if one is active and started
 * otherwise. The datasource runs in strict mode, so any JDBC call outside this boundary fails
 * instead of silently auto-committing.
 */
@ApplicationScoped
public class QuarkusUnitOfWork implements UnitOfWork {

  @Override
  public <T> T inTx(Supplier<T> work) {
    return QuarkusTransaction.joiningExisting().call(work::get);
  }
}
