package com.zonacards.giftcards.infra;

import com.zonacards.dbx.Db;
import com.zonacards.dbx.Row;
import com.zonacards.dbx.SqlArray;
import com.zonacards.giftcards.GiftCard;
import com.zonacards.giftcards.GiftCardErrors;
import com.zonacards.giftcards.GiftCardEstado;
import com.zonacards.giftcards.GiftCardRepository;
import com.zonacards.kernel.GiftCardId;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.Paginated;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.kernel.TenantId;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The gift card aggregate in PostgreSQL. Every statement carries the tenant. */
public final class PostgresGiftCardRepository implements GiftCardRepository {

  private static final String COLUMNS =
      "id, tenant_id, lote_id, secuencia, pais, moneda, monto, vigencia_hasta, estado, codigo_cifrado, codigo_indice,"
          + " codigo_mascara, cvv_hash, motivo, activada_en, anulada_en, creado_en, actualizado_en, version";

  /**
   * One statement for a whole fragment: the columns travel as arrays and {@code unnest} turns
   * them back into rows. A thousand cards are one round trip, not a thousand. A code that
   * already exists is skipped (not an error, which would abort the transaction), and RETURNING
   * tells the caller which cards made it.
   */
  private static final String INSERT_ALL =
      """
      INSERT INTO giftcard (id, tenant_id, lote_id, secuencia, pais, moneda, monto, vigencia_hasta, estado,
                            codigo_cifrado, codigo_indice, codigo_mascara, cvv_hash, creado_en, actualizado_en)
      SELECT t.id, ?::text, ?::uuid, t.secuencia, ?::text, ?::text, ?::numeric, ?::date, 'GENERADA',
             t.cifrado, t.indice, t.mascara, t.cvv, ?::timestamptz, ?::timestamptz
        FROM unnest(?::uuid[], ?::int[], ?::bytea[], ?::bytea[], ?::text[], ?::text[])
             AS t(id, secuencia, cifrado, indice, mascara, cvv)
      ON CONFLICT (codigo_indice) DO NOTHING
      RETURNING secuencia
      """;

  private final Db db;

  public PostgresGiftCardRepository(Db db) {
    this.db = db;
  }

  @Override
  public boolean insert(GiftCard card) {
    return db.update(
            """
            INSERT INTO giftcard (id, tenant_id, lote_id, secuencia, pais, moneda, monto, vigencia_hasta, estado,
                                  codigo_cifrado, codigo_indice, codigo_mascara, cvv_hash, creado_en, actualizado_en)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (codigo_indice) DO NOTHING
            """,
            card.id(),
            card.tenantId(),
            card.loteId(),
            card.secuencia(),
            card.pais(),
            card.moneda(),
            card.monto(),
            card.vigenciaHasta(),
            card.estado(),
            card.codigoCifrado(),
            card.codigoIndice(),
            card.codigoMascara(),
            card.cvvHash(),
            card.creadoEn(),
            card.actualizadoEn())
        == 1;
  }

  @Override
  public Set<Integer> insertAll(List<GiftCard> cards) {
    if (cards.isEmpty()) {
      return Set.of();
    }
    GiftCard first = cards.get(0);
    List<UUID> ids = new ArrayList<>(cards.size());
    List<Integer> secuencias = new ArrayList<>(cards.size());
    List<byte[]> cifrados = new ArrayList<>(cards.size());
    List<byte[]> indices = new ArrayList<>(cards.size());
    List<String> mascaras = new ArrayList<>(cards.size());
    List<String> cvvs = new ArrayList<>(cards.size());
    for (GiftCard card : cards) {
      ids.add(UUID.fromString(card.id().value()));
      secuencias.add(card.secuencia());
      cifrados.add(card.codigoCifrado());
      indices.add(card.codigoIndice());
      mascaras.add(card.codigoMascara());
      cvvs.add(card.cvvHash());
    }
    List<Integer> stored =
        db.queryList(
            INSERT_ALL,
            row -> row.integer("secuencia"),
            first.tenantId().value(),
            first.loteId().value(),
            first.pais(),
            first.moneda(),
            first.monto(),
            first.vigenciaHasta(),
            first.creadoEn(),
            first.actualizadoEn(),
            SqlArray.uuid(ids),
            SqlArray.integers(secuencias),
            SqlArray.bytes(cifrados),
            SqlArray.bytes(indices),
            SqlArray.text(mascaras),
            SqlArray.text(cvvs));
    return new HashSet<>(stored);
  }

  @Override
  public GiftCard get(TenantId tenantId, GiftCardId id) {
    return db.queryOne(
            "SELECT " + COLUMNS + " FROM giftcard WHERE tenant_id = ? AND id = ?", PostgresGiftCardRepository::map, tenantId, id)
        .orElseThrow(() -> GiftCardErrors.notFound(id));
  }

  @Override
  public Optional<GiftCard> findForUpdate(TenantId tenantId, GiftCardId id) {
    return db.queryOne(
        "SELECT " + COLUMNS + " FROM giftcard WHERE tenant_id = ? AND id = ? FOR UPDATE", PostgresGiftCardRepository::map, tenantId, id);
  }

  @Override
  public Paginated<GiftCard> list(TenantId tenantId, LoteId loteId, GiftCardEstado estado, PaginationOptions pagination) {
    StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenantId);
    if (loteId != null) {
      where.append(" AND lote_id = ?");
      params.add(loteId);
    }
    if (estado != null) {
      where.append(" AND estado = ?");
      params.add(estado);
    }
    long total = db.queryLong("SELECT count(*) FROM giftcard" + where, params.toArray());
    // Inside a lote the natural order is the sequence; across lotes, newest first.
    String order = loteId != null ? " ORDER BY secuencia" : " ORDER BY creado_en DESC, id DESC";
    params.add(pagination.pageSize());
    params.add(pagination.offset());
    List<GiftCard> items =
        db.queryList("SELECT " + COLUMNS + " FROM giftcard" + where + order + " LIMIT ? OFFSET ?", PostgresGiftCardRepository::map, params.toArray());
    return Paginated.of(items, pagination, total);
  }

  @Override
  public void update(GiftCard card) {
    int updated =
        db.update(
            """
            UPDATE giftcard
               SET vigencia_hasta = ?, estado = ?, motivo = ?, activada_en = ?, anulada_en = ?, actualizado_en = ?,
                   version = version + 1
             WHERE tenant_id = ? AND id = ? AND version = ?
            """,
            card.vigenciaHasta(),
            card.estado(),
            card.motivo(),
            card.activadaEn(),
            card.anuladaEn(),
            card.actualizadoEn(),
            card.tenantId(),
            card.id(),
            card.version());
    if (updated == 0) {
      throw GiftCardErrors.versionConflict(card.id());
    }
  }

  @Override
  public int anularPorLote(TenantId tenantId, LoteId loteId, String motivo, Instant now) {
    return db.update(
        """
        UPDATE giftcard
           SET estado = 'ANULADA', motivo = ?, anulada_en = ?, actualizado_en = ?, version = version + 1
         WHERE tenant_id = ? AND lote_id = ? AND estado <> 'ANULADA'
        """,
        motivo,
        now,
        now,
        tenantId,
        loteId);
  }

  private static GiftCard map(Row row) throws SQLException {
    UUID lote = row.uuid("lote_id");
    return GiftCard.restore(
        GiftCardId.of(row.uuid("id").toString()),
        TenantId.of(row.string("tenant_id")),
        lote == null ? null : LoteId.of(lote.toString()),
        row.integerOrNull("secuencia"),
        row.string("pais"),
        row.string("moneda"),
        row.decimal("monto"),
        row.localDate("vigencia_hasta"),
        row.enumValue("estado", GiftCardEstado.class),
        row.bytes("codigo_cifrado"),
        row.bytes("codigo_indice"),
        row.string("codigo_mascara"),
        row.string("cvv_hash"),
        row.string("motivo"),
        row.instant("activada_en"),
        row.instant("anulada_en"),
        row.instant("creado_en"),
        row.instant("actualizado_en"),
        row.integer("version"));
  }
}
