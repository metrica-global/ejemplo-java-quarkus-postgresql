package com.zonacards.lotes.infra;

import com.zonacards.dbx.Db;
import com.zonacards.dbx.Row;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.Paginated;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.kernel.TenantId;
import com.zonacards.lotes.Lote;
import com.zonacards.lotes.LoteErrors;
import com.zonacards.lotes.LoteEstado;
import com.zonacards.lotes.LoteRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The lote aggregate in PostgreSQL, with explicit SQL. Every statement carries the tenant in its
 * WHERE clause: tenant isolation does not depend on the caller remembering to check.
 */
public final class PostgresLoteRepository implements LoteRepository {

  private static final String COLUMNS =
      "id, tenant_id, descripcion, referencia_externa, pais, moneda, monto, cantidad, vigencia_hasta, estado,"
          + " fragmentos_totales, fragmentos_completados, generadas, motivo, anulacion_aplicada_en, giftcards_anuladas,"
          + " idempotency_key, request_hash, creado_por, creado_en, actualizado_en, version";

  private final Db db;

  public PostgresLoteRepository(Db db) {
    this.db = db;
  }

  @Override
  public boolean insert(Lote lote) {
    // ON CONFLICT instead of catching the unique violation: an error would abort the transaction.
    return db.update(
            """
            INSERT INTO lote (id, tenant_id, descripcion, referencia_externa, pais, moneda, monto, cantidad,
                              vigencia_hasta, estado, idempotency_key, request_hash, creado_por, creado_en, actualizado_en)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            """,
            lote.id(),
            lote.tenantId(),
            lote.descripcion(),
            lote.referenciaExterna(),
            lote.pais(),
            lote.moneda(),
            lote.monto(),
            lote.cantidad(),
            lote.vigenciaHasta(),
            lote.estado(),
            lote.idempotencyKey(),
            lote.requestHash(),
            lote.creadoPor(),
            lote.creadoEn(),
            lote.actualizadoEn())
        == 1;
  }

  @Override
  public Optional<Lote> findByIdempotencyKey(TenantId tenantId, String idempotencyKey) {
    return db.queryOne(
        "SELECT " + COLUMNS + " FROM lote WHERE tenant_id = ? AND idempotency_key = ?",
        PostgresLoteRepository::map,
        tenantId,
        idempotencyKey);
  }

  @Override
  public Lote get(TenantId tenantId, LoteId id) {
    return db.queryOne("SELECT " + COLUMNS + " FROM lote WHERE tenant_id = ? AND id = ?", PostgresLoteRepository::map, tenantId, id)
        .orElseThrow(() -> LoteErrors.notFound(id));
  }

  @Override
  public Optional<Lote> findForUpdate(TenantId tenantId, LoteId id) {
    return db.queryOne(
        "SELECT " + COLUMNS + " FROM lote WHERE tenant_id = ? AND id = ? FOR UPDATE", PostgresLoteRepository::map, tenantId, id);
  }

  @Override
  public Paginated<Lote> list(TenantId tenantId, LoteEstado estado, PaginationOptions pagination) {
    StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenantId);
    if (estado != null) {
      where.append(" AND estado = ?");
      params.add(estado);
    }
    long total = db.queryLong("SELECT count(*) FROM lote" + where, params.toArray());
    params.add(pagination.pageSize());
    params.add(pagination.offset());
    List<Lote> items =
        db.queryList(
            "SELECT " + COLUMNS + " FROM lote" + where + " ORDER BY creado_en DESC, id DESC LIMIT ? OFFSET ?",
            PostgresLoteRepository::map,
            params.toArray());
    return Paginated.of(items, pagination, total);
  }

  @Override
  public void update(Lote lote) {
    int updated =
        db.update(
            """
            UPDATE lote
               SET descripcion = ?, referencia_externa = ?, estado = ?, fragmentos_totales = ?,
                   fragmentos_completados = ?, generadas = ?, motivo = ?, anulacion_aplicada_en = ?,
                   giftcards_anuladas = ?, actualizado_en = ?, version = version + 1
             WHERE tenant_id = ? AND id = ? AND version = ?
            """,
            lote.descripcion(),
            lote.referenciaExterna(),
            lote.estado(),
            lote.fragmentosTotales(),
            lote.fragmentosCompletados(),
            lote.generadas(),
            lote.motivo(),
            lote.anulacionAplicadaEn(),
            lote.giftcardsAnuladas(),
            lote.actualizadoEn(),
            lote.tenantId(),
            lote.id(),
            lote.version());
    if (updated == 0) {
      throw LoteErrors.versionConflict(lote.id());
    }
  }

  @Override
  public boolean addFragmento(LoteId id, int fragmento, int cantidad) {
    return db.update(
            "INSERT INTO lote_fragmento (lote_id, fragmento, cantidad) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            id,
            fragmento,
            cantidad)
        == 1;
  }

  private static Lote map(Row row) throws SQLException {
    return Lote.restore(
        LoteId.of(row.uuid("id").toString()),
        TenantId.of(row.string("tenant_id")),
        row.string("descripcion"),
        row.string("referencia_externa"),
        row.string("pais"),
        row.string("moneda"),
        row.decimal("monto"),
        row.integer("cantidad"),
        row.localDate("vigencia_hasta"),
        row.enumValue("estado", LoteEstado.class),
        row.integer("fragmentos_totales"),
        row.integer("fragmentos_completados"),
        row.integer("generadas"),
        row.string("motivo"),
        row.instant("anulacion_aplicada_en"),
        row.integerOrNull("giftcards_anuladas"),
        row.string("idempotency_key"),
        row.string("request_hash"),
        row.string("creado_por"),
        row.instant("creado_en"),
        row.instant("actualizado_en"),
        row.integer("version"));
  }
}
