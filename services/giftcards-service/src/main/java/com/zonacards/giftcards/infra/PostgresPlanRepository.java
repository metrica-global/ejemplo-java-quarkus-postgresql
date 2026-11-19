package com.zonacards.giftcards.infra;

import com.zonacards.dbx.Db;
import com.zonacards.giftcards.Plan;
import com.zonacards.giftcards.PlanRepository;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.TenantId;
import java.time.Instant;
import java.util.Optional;

/** The generation plan in PostgreSQL. The locking each method takes is its contract. */
public final class PostgresPlanRepository implements PlanRepository {

  private final Db db;

  public PostgresPlanRepository(Db db) {
    this.db = db;
  }

  @Override
  public boolean crear(Plan plan, int tamanoFragmento) {
    int inserted =
        db.update(
            """
            INSERT INTO plan_generacion (lote_id, tenant_id, pais, moneda, monto, cantidad, vigencia_hasta, estado, total_fragmentos)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (lote_id) DO NOTHING
            """,
            plan.loteId(),
            plan.tenantId(),
            plan.pais(),
            plan.moneda(),
            plan.monto(),
            plan.cantidad(),
            plan.vigenciaHasta(),
            plan.estado(),
            plan.totalFragmentos());
    if (inserted == 0) {
      return false;
    }
    // Every fragment in one statement; the last one takes what is left.
    db.update(
        """
        INSERT INTO plan_fragmento (lote_id, numero, desde_secuencia, cantidad)
        SELECT ?, n, (n - 1) * ? + 1, LEAST(?, ? - (n - 1) * ?)
          FROM generate_series(1, ?) AS n
        """,
        plan.loteId(),
        tamanoFragmento,
        tamanoFragmento,
        plan.cantidad(),
        tamanoFragmento,
        plan.totalFragmentos());
    return true;
  }

  @Override
  public Optional<Plan> find(LoteId loteId) {
    return db.queryOne(
        "SELECT lote_id, tenant_id, pais, moneda, monto, cantidad, vigencia_hasta, estado, total_fragmentos"
            + " FROM plan_generacion WHERE lote_id = ?",
        row ->
            new Plan(
                LoteId.of(row.uuid("lote_id").toString()),
                TenantId.of(row.string("tenant_id")),
                row.string("pais"),
                row.string("moneda"),
                row.decimal("monto"),
                row.integer("cantidad"),
                row.localDate("vigencia_hasta"),
                row.enumValue("estado", Plan.Estado.class),
                row.integer("total_fragmentos")),
        loteId);
  }

  @Override
  public Optional<Plan.Estado> estadoCompartido(LoteId loteId) {
    // FOR SHARE, not FOR KEY SHARE: it must conflict with the update that annuls the plan.
    return db.queryOne(
        "SELECT estado FROM plan_generacion WHERE lote_id = ? FOR SHARE", row -> row.enumValue("estado", Plan.Estado.class), loteId);
  }

  @Override
  public boolean fragmentoPendiente(LoteId loteId, int numero) {
    return db.queryLong("SELECT count(*) FROM plan_fragmento WHERE lote_id = ? AND numero = ? AND estado = 'PENDIENTE'", loteId, numero) == 1;
  }

  @Override
  public boolean reclamarFragmento(LoteId loteId, int numero, Instant now) {
    // The row lock of this update makes a concurrent delivery wait; when it resumes, the
    // condition no longer holds for it and it updates nothing.
    return db.update(
            "UPDATE plan_fragmento SET estado = 'GENERADO', terminado_en = ? WHERE lote_id = ? AND numero = ? AND estado = 'PENDIENTE'",
            now,
            loteId,
            numero)
        == 1;
  }

  @Override
  public boolean anular(TenantId tenantId, LoteId loteId, Instant now) {
    // Insert-or-update in one statement: with no plan yet, the row becomes a tombstone that the
    // late "lote solicitado" will find. The update only applies to this tenant's plan.
    return db.update(
            """
            INSERT INTO plan_generacion (lote_id, tenant_id, estado, anulado_en)
            VALUES (?, ?, 'ANULADO', ?)
            ON CONFLICT (lote_id) DO UPDATE
               SET estado = 'ANULADO', anulado_en = EXCLUDED.anulado_en, actualizado_en = EXCLUDED.anulado_en
             WHERE plan_generacion.tenant_id = EXCLUDED.tenant_id
               AND plan_generacion.estado <> 'ANULADO'
            """,
            loteId,
            tenantId,
            now)
        == 1;
  }

  @Override
  public boolean fallar(LoteId loteId, int numero, String motivo, Instant now) {
    int claimed =
        db.update(
            "UPDATE plan_fragmento SET estado = 'FALLIDO', motivo = ?, terminado_en = ? WHERE lote_id = ? AND numero = ? AND estado = 'PENDIENTE'",
            motivo,
            now,
            loteId,
            numero);
    if (claimed == 0) {
      return false;
    }
    db.update("UPDATE plan_generacion SET estado = 'FALLIDO', actualizado_en = ? WHERE lote_id = ? AND estado = 'EN_CURSO'", now, loteId);
    return true;
  }
}
