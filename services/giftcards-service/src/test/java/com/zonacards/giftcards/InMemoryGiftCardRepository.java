package com.zonacards.giftcards;

import com.zonacards.kernel.GiftCardId;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.Paginated;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.kernel.TenantId;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A hand-written fake of the repository port: tenant scoping, unique codes, versions, snapshots. */
public final class InMemoryGiftCardRepository implements GiftCardRepository {

  private final Map<String, GiftCard> rows = new LinkedHashMap<>();
  private final Set<String> indices = new HashSet<>();

  @Override
  public synchronized boolean insert(GiftCard card) {
    if (!indices.add(Base64.getEncoder().encodeToString(card.codigoIndice()))) {
      return false;
    }
    rows.put(card.id().value(), copy(card, card.version()));
    return true;
  }

  @Override
  public synchronized Set<Integer> insertAll(List<GiftCard> cards) {
    Set<Integer> stored = new HashSet<>();
    for (GiftCard card : cards) {
      if (insert(card)) {
        stored.add(card.secuencia());
      }
    }
    return stored;
  }

  @Override
  public synchronized GiftCard get(TenantId tenantId, GiftCardId id) {
    return findForUpdate(tenantId, id).orElseThrow(() -> GiftCardErrors.notFound(id));
  }

  @Override
  public synchronized Optional<GiftCard> findForUpdate(TenantId tenantId, GiftCardId id) {
    GiftCard stored = rows.get(id.value());
    if (stored == null || !stored.tenantId().equals(tenantId)) {
      return Optional.empty();
    }
    return Optional.of(copy(stored, stored.version()));
  }

  @Override
  public synchronized Paginated<GiftCard> list(TenantId tenantId, LoteId loteId, GiftCardEstado estado, PaginationOptions pagination) {
    List<GiftCard> all =
        rows.values().stream()
            .filter(c -> c.tenantId().equals(tenantId))
            .filter(c -> loteId == null || loteId.equals(c.loteId()))
            .filter(c -> estado == null || c.estado() == estado)
            .sorted(Comparator.comparing(GiftCard::creadoEn).reversed())
            .toList();
    List<GiftCard> page = all.stream().skip(pagination.offset()).limit(pagination.pageSize()).toList();
    return Paginated.of(page, pagination, all.size());
  }

  @Override
  public synchronized void update(GiftCard card) {
    GiftCard stored = rows.get(card.id().value());
    if (stored == null || !stored.tenantId().equals(card.tenantId()) || stored.version() != card.version()) {
      throw GiftCardErrors.versionConflict(card.id());
    }
    rows.put(card.id().value(), copy(card, card.version() + 1));
  }

  @Override
  public synchronized int anularPorLote(TenantId tenantId, LoteId loteId, String motivo, Instant now) {
    int count = 0;
    for (GiftCard card : List.copyOf(rows.values())) {
      if (card.tenantId().equals(tenantId) && loteId.equals(card.loteId()) && card.anular(motivo, now)) {
        count++;
      }
    }
    return count;
  }

  public synchronized int size() {
    return rows.size();
  }

  private static GiftCard copy(GiftCard c, int version) {
    return GiftCard.restore(
        c.id(), c.tenantId(), c.loteId(), c.secuencia(), c.pais(), c.moneda(), c.monto(), c.vigenciaHasta(), c.estado(),
        c.codigoCifrado(), c.codigoIndice(), c.codigoMascara(), c.cvvHash(), c.motivo(), c.activadaEn(), c.anuladaEn(),
        c.creadoEn(), c.actualizadoEn(), version);
  }
}
