package com.zonacards.lotes;

import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.Paginated;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.kernel.TenantId;
import java.util.Optional;

/**
 * Persistence port of the lote aggregate. Every query is scoped by tenant. Naming follows one
 * rule: {@code get} fails with NOT_FOUND when there is no row, {@code find} returns an optional,
 * {@code list} returns a page.
 */
public interface LoteRepository {

  /** Stores a new lote. False when the tenant already used its idempotency key; nothing is stored then. */
  boolean insert(Lote lote);

  Optional<Lote> findByIdempotencyKey(TenantId tenantId, String idempotencyKey);

  Lote get(TenantId tenantId, LoteId id);

  /** Like {@link #find} but locks the row until the transaction ends. Serialises event handlers. */
  Optional<Lote> findForUpdate(TenantId tenantId, LoteId id);

  Paginated<Lote> list(TenantId tenantId, LoteEstado estado, PaginationOptions pagination);

  /** Saves the lote if its version is still the stored one; otherwise fails with VERSION_CONFLICT. */
  void update(Lote lote);

  /** Records that a fragment was generated. False when that fragment had already been recorded. */
  boolean addFragmento(LoteId id, int fragmento, int cantidad);
}
