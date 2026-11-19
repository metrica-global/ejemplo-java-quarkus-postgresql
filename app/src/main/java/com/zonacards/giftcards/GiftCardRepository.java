package com.zonacards.giftcards;

import com.zonacards.kernel.GiftCardId;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.Paginated;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.kernel.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Persistence port of the gift card aggregate. Every query is scoped by tenant. */
public interface GiftCardRepository {

  /** Stores a card. False when another card already has the same code; nothing is stored then. */
  boolean insert(GiftCard card);

  /**
   * Stores the cards of one fragment of a lote in a single statement. Cards whose code collides
   * with an existing one are skipped. Returns the sequence numbers that were stored.
   */
  Set<Integer> insertAll(List<GiftCard> cards);

  GiftCard get(TenantId tenantId, GiftCardId id);

  Optional<GiftCard> findForUpdate(TenantId tenantId, GiftCardId id);

  /** @param loteId optional filter; @param estado optional filter */
  Paginated<GiftCard> list(TenantId tenantId, LoteId loteId, GiftCardEstado estado, PaginationOptions pagination);

  /** Saves the card if its version is still the stored one; otherwise fails with VERSION_CONFLICT. */
  void update(GiftCard card);

  /** Annuls every card of the lote that is not annulled yet. Returns how many were annulled. */
  int anularPorLote(TenantId tenantId, LoteId loteId, String motivo, Instant now);
}
