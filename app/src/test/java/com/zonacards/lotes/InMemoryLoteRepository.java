package com.zonacards.lotes;

import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.Paginated;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.kernel.TenantId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A hand-written fake of the repository port, with the behaviour the services rely on: tenant
 * scoping, the idempotency key, optimistic versions, and snapshots (changing a loaded lote does
 * not change what is stored until it is saved). No mocking library: the fake is small, readable
 * and reused by every unit test.
 */
public final class InMemoryLoteRepository implements LoteRepository {

  private final Map<String, Lote> rows = new LinkedHashMap<>();
  private final Set<String> fragmentos = new HashSet<>();

  @Override
  public synchronized boolean insert(Lote lote) {
    if (findByIdempotencyKey(lote.tenantId(), lote.idempotencyKey()).isPresent()) {
      return false;
    }
    rows.put(lote.id().value(), copy(lote, lote.version()));
    return true;
  }

  @Override
  public synchronized Optional<Lote> findByIdempotencyKey(TenantId tenantId, String idempotencyKey) {
    return rows.values().stream()
        .filter(l -> l.tenantId().equals(tenantId) && l.idempotencyKey().equals(idempotencyKey))
        .findFirst()
        .map(l -> copy(l, l.version()));
  }

  @Override
  public synchronized Lote get(TenantId tenantId, LoteId id) {
    return findForUpdate(tenantId, id).orElseThrow(() -> LoteErrors.notFound(id));
  }

  @Override
  public synchronized Optional<Lote> findForUpdate(TenantId tenantId, LoteId id) {
    Lote stored = rows.get(id.value());
    if (stored == null || !stored.tenantId().equals(tenantId)) {
      return Optional.empty();
    }
    return Optional.of(copy(stored, stored.version()));
  }

  @Override
  public synchronized Paginated<Lote> list(TenantId tenantId, LoteEstado estado, PaginationOptions pagination) {
    List<Lote> all =
        rows.values().stream()
            .filter(l -> l.tenantId().equals(tenantId) && (estado == null || l.estado() == estado))
            .sorted(Comparator.comparing(Lote::creadoEn).reversed())
            .toList();
    List<Lote> page = all.stream().skip(pagination.offset()).limit(pagination.pageSize()).map(l -> copy(l, l.version())).toList();
    return Paginated.of(page, pagination, all.size());
  }

  @Override
  public synchronized void update(Lote lote) {
    Lote stored = rows.get(lote.id().value());
    if (stored == null || !stored.tenantId().equals(lote.tenantId()) || stored.version() != lote.version()) {
      throw LoteErrors.versionConflict(lote.id());
    }
    rows.put(lote.id().value(), copy(lote, lote.version() + 1));
  }

  @Override
  public synchronized boolean addFragmento(LoteId id, int fragmento, int cantidad) {
    return fragmentos.add(id.value() + "#" + fragmento);
  }

  public synchronized int size() {
    return rows.size();
  }

  private static Lote copy(Lote l, int version) {
    return Lote.restore(
        l.id(), l.tenantId(), l.descripcion(), l.referenciaExterna(), l.pais(), l.moneda(), l.monto(), l.cantidad(),
        l.vigenciaHasta(), l.estado(), l.fragmentosTotales(), l.fragmentosCompletados(), l.generadas(), l.motivo(),
        l.anulacionAplicadaEn(), l.giftcardsAnuladas(), l.idempotencyKey(), l.requestHash(), l.creadoPor(), l.creadoEn(),
        l.actualizadoEn(), version);
  }
}
