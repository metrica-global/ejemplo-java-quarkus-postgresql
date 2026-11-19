package com.zonacards.giftcards;

import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.TenantId;
import java.time.Instant;
import java.util.Optional;

/**
 * Persistence port of the generation plan. Its methods are the concurrency control of the saga;
 * each one states the guarantee the handlers rely on.
 */
public interface PlanRepository {

  /**
   * Stores the plan and its fragments. False, storing nothing, when a plan for the lote already
   * exists: either the request was delivered twice, or the lote was annulled before its request
   * arrived and left a tombstone.
   */
  boolean crear(Plan plan, int tamanoFragmento);

  Optional<Plan> find(LoteId loteId);

  /**
   * Reads the state of the plan and holds a shared lock on it until the transaction ends. An
   * annulment needs the exclusive lock, so it waits for every fragment that is storing cards,
   * and every fragment that starts afterwards sees the plan annulled. Fragments do not block
   * each other.
   */
  Optional<Plan.Estado> estadoCompartido(LoteId loteId);

  boolean fragmentoPendiente(LoteId loteId, int numero);

  /**
   * Marks the fragment as generated if it is still pending. False when another delivery of the
   * same command already did: this, not the inbox, is what makes a fragment safe to redeliver
   * at any time.
   */
  boolean reclamarFragmento(LoteId loteId, int numero, Instant now);

  /**
   * Annuls the plan, creating it as a tombstone if the request has not arrived yet. False when it
   * was already annulled or belongs to another tenant.
   */
  boolean anular(TenantId tenantId, LoteId loteId, Instant now);

  /** Marks the fragment and the plan as failed. False when the fragment was not pending any more. */
  boolean fallar(LoteId loteId, int numero, String motivo, Instant now);
}
