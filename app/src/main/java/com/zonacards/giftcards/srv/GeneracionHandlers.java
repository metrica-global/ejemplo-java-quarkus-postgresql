package com.zonacards.giftcards.srv;

import com.zonacards.asyncx.ComputePool;
import com.zonacards.eventx.EventEnvelope;
import com.zonacards.eventx.EventFactory;
import com.zonacards.eventx.HandlerRegistry;
import com.zonacards.eventx.MessageContext;
import com.zonacards.eventx.Outbox;
import com.zonacards.giftcards.GiftCard;
import com.zonacards.giftcards.GiftCardRepository;
import com.zonacards.giftcards.Plan;
import com.zonacards.giftcards.PlanRepository;
import com.zonacards.giftcards.events.GeneracionCommands;
import com.zonacards.giftcards.events.GeneracionCommands.GenerarFragmento;
import com.zonacards.giftcards.events.GiftcardsEvents;
import com.zonacards.giftcards.events.LotesEvents;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.TenantId;
import com.zonacards.kernel.UnitOfWork;
import com.zonacards.logx.Logx;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The generation of a lote, as three reactions:
 *
 * <ol>
 *   <li><b>lote solicitado</b> (event from lotes): store a plan and split the work into fragments,
 *       one command per fragment on this domain's own work queue;
 *   <li><b>generar fragmento</b> (command to itself): create the cards of one fragment. Any task
 *       of the service may take any fragment, which is what lets the service scale out;
 *   <li><b>lote anulado</b> (event from lotes): stop the plan and annul what was generated.
 * </ol>
 *
 * Everything here assumes at-least-once, unordered delivery. The comments on each handler say
 * which statement makes it safe.
 */
public final class GeneracionHandlers {

  private static final Logx LOG = Logx.get(GeneracionHandlers.class);
  private static final int RONDAS_DE_REEMPLAZO = 5;

  /** The fragment can never be generated; retrying would not help. */
  static final class GeneracionImposible extends RuntimeException {
    GeneracionImposible(String message) {
      super(message);
    }
  }

  private final PlanRepository planes;
  private final GiftCardRepository giftcards;
  private final GiftCardFactory factory;
  private final ComputePool cpu;
  private final UnitOfWork uow;
  private final Outbox outbox;
  private final EventFactory events;
  private final Clock clock;
  private final int tamanoFragmento;

  public GeneracionHandlers(
      PlanRepository planes,
      GiftCardRepository giftcards,
      GiftCardFactory factory,
      ComputePool cpu,
      UnitOfWork uow,
      Outbox outbox,
      EventFactory events,
      Clock clock,
      int tamanoFragmento) {
    this.planes = planes;
    this.giftcards = giftcards;
    this.factory = factory;
    this.cpu = cpu;
    this.uow = uow;
    this.outbox = outbox;
    this.events = events;
    this.clock = clock;
    this.tamanoFragmento = tamanoFragmento;
  }

  /** The complete list of what this service consumes. */
  public HandlerRegistry register(HandlerRegistry registry) {
    return registry
        .on(LotesEvents.LOTE_SOLICITADO, this::onLoteSolicitado)
        .on(LotesEvents.LOTE_ANULADO, this::onLoteAnulado)
        .onSelfManaged(GeneracionCommands.GENERAR_FRAGMENTO, this::onGenerarFragmento);
  }

  /**
   * Plans the generation. The plan is inserted with ON CONFLICT DO NOTHING: a second delivery
   * finds it and does nothing, and so does a request that arrives after its own annulment
   * (the annulment left a tombstone). Plan, commands and event commit together.
   */
  void onLoteSolicitado(MessageContext context) {
    LotesEvents.LoteSolicitado event = context.data(LotesEvents.LoteSolicitado.class);
    TenantId tenantId = context.tenantId();
    LoteId loteId = LoteId.of(event.loteId());
    Plan plan =
        Plan.nuevo(loteId, tenantId, event.pais(), event.moneda(), event.monto(), event.cantidad(), event.vigenciaHasta(), tamanoFragmento);
    if (!planes.crear(plan, tamanoFragmento)) {
      LOG.with("lote_id", loteId).info("el lote ya tiene plan (reentrega o anulación previa); no se planifica");
      return;
    }
    List<EventEnvelope> commands = new ArrayList<>(plan.totalFragmentos());
    for (int numero = 1; numero <= plan.totalFragmentos(); numero++) {
      Plan.Fragmento fragmento = plan.fragmento(numero, tamanoFragmento);
      commands.add(
          events.create(
              GeneracionCommands.GENERAR_FRAGMENTO,
              tenantId,
              loteId.value(),
              new GenerarFragmento(loteId.value(), numero, plan.totalFragmentos(), fragmento.desdeSecuencia(), fragmento.cantidad())));
    }
    outbox.sendAll(GeneracionCommands.QUEUE, commands);
    outbox.publish(
        GiftcardsEvents.TOPIC,
        events.create(
            GiftcardsEvents.GENERACION_INICIADA,
            tenantId,
            loteId.value(),
            new GiftcardsEvents.GeneracionIniciada(loteId.value(), plan.totalFragmentos())));
    LOG.with("lote_id", loteId).with("cantidad", plan.cantidad()).with("fragmentos", plan.totalFragmentos()).info("generación planificada");
  }

  /**
   * Generates one fragment, in three steps:
   *
   * <ol>
   *   <li>a cheap look at the plan, to skip work that is no longer wanted;
   *   <li>the expensive part (codes, encryption, one CVV hash per card) with no transaction and
   *       no database connection, on the compute pool;
   *   <li>one short transaction that stores everything, guarded twice: a shared lock on the plan
   *       (an annulment in progress waits for us, a finished one stops us) and a conditional
   *       update of the fragment (a concurrent or later delivery of the same command stores
   *       nothing).
   * </ol>
   *
   * If the task dies anywhere before the commit, nothing was stored and the command comes back.
   */
  void onGenerarFragmento(MessageContext context) {
    GenerarFragmento command = context.data(GenerarFragmento.class);
    LoteId loteId = LoteId.of(command.loteId());

    Optional<Plan> found = uow.inTx(() -> planes.find(loteId));
    if (found.isEmpty() || !found.get().tenantId().equals(context.tenantId())) {
      LOG.with("lote_id", loteId).with("fragmento", command.fragmento()).warn("fragmento de un plan desconocido; se descarta");
      return;
    }
    Plan plan = found.get();
    boolean wanted = plan.estado() == Plan.Estado.EN_CURSO && uow.inTx(() -> planes.fragmentoPendiente(loteId, command.fragmento()));
    if (!wanted) {
      LOG.with("lote_id", loteId).with("fragmento", command.fragmento()).with("plan", plan.estado()).info("fragmento ya no requerido");
      return;
    }

    try {
      List<Integer> secuencias = new ArrayList<>(command.cantidad());
      for (int i = 0; i < command.cantidad(); i++) {
        secuencias.add(command.desdeSecuencia() + i);
      }
      List<GiftCard> cards = cpu.call(() -> fabricar(plan, secuencias));
      context.once(() -> guardar(plan, command, cards));
    } catch (GeneracionImposible | IllegalArgumentException e) {
      // Nothing of this fragment was stored: the transaction above rolled back.
      // The reason is part of the event contract, so it is never left empty.
      String motivo = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      context.once(() -> fallar(plan, command, motivo));
    }
  }

  private void guardar(Plan plan, GenerarFragmento command, List<GiftCard> cards) {
    LoteId loteId = plan.loteId();
    Plan.Estado estado = planes.estadoCompartido(loteId).orElse(Plan.Estado.ANULADO);
    if (estado != Plan.Estado.EN_CURSO) {
      LOG.with("lote_id", loteId).with("fragmento", command.fragmento()).with("plan", estado).info("plan detenido; el fragmento no se guarda");
      return;
    }
    if (!planes.reclamarFragmento(loteId, command.fragmento(), clock.instant())) {
      return;
    }
    Set<Integer> faltantes = faltantes(cards, giftcards.insertAll(cards));
    // Two cards with the same code cannot exist. The colliding ones were skipped by the insert;
    // they get a new code and are stored in this same transaction.
    for (int ronda = 1; !faltantes.isEmpty(); ronda++) {
      if (ronda > RONDAS_DE_REEMPLAZO) {
        throw new GeneracionImposible("no hay códigos únicos disponibles para " + faltantes.size() + " gift cards");
      }
      List<GiftCard> reemplazos = fabricar(plan, List.copyOf(faltantes));
      faltantes = faltantes(reemplazos, giftcards.insertAll(reemplazos));
    }
    outbox.publish(
        GiftcardsEvents.TOPIC,
        events.create(
            GiftcardsEvents.FRAGMENTO_GENERADO,
            plan.tenantId(),
            loteId.value(),
            new GiftcardsEvents.FragmentoGenerado(loteId.value(), command.fragmento(), command.totalFragmentos(), command.cantidad())));
    LOG.with("lote_id", loteId).with("fragmento", command.fragmento()).with("cantidad", command.cantidad()).info("fragmento generado");
  }

  private void fallar(Plan plan, GenerarFragmento command, String motivo) {
    if (!planes.fallar(plan.loteId(), command.fragmento(), motivo, clock.instant())) {
      return;
    }
    outbox.publish(
        GiftcardsEvents.TOPIC,
        events.create(
            GiftcardsEvents.FRAGMENTO_FALLIDO,
            plan.tenantId(),
            plan.loteId().value(),
            new GiftcardsEvents.FragmentoFallido(plan.loteId().value(), command.fragmento(), motivo)));
    LOG.with("lote_id", plan.loteId()).with("fragmento", command.fragmento()).with("motivo", motivo).error("fragmento fallido");
  }

  /**
   * Annuls the lote on this side. The update of the plan takes the lock that every fragment
   * shares: it waits for the fragments that are storing cards right now, and once it commits no
   * fragment can store another one. Then the cards that exist are annulled, all in the same
   * transaction, so there is never a live card in an annulled lote.
   */
  void onLoteAnulado(MessageContext context) {
    LotesEvents.LoteAnulado event = context.data(LotesEvents.LoteAnulado.class);
    TenantId tenantId = context.tenantId();
    LoteId loteId = LoteId.of(event.loteId());
    if (!planes.anular(tenantId, loteId, clock.instant())) {
      LOG.with("lote_id", loteId).info("el plan ya estaba anulado; nada que hacer");
      return;
    }
    int anuladas = giftcards.anularPorLote(tenantId, loteId, "Lote anulado: " + event.motivo(), clock.instant());
    outbox.publish(
        GiftcardsEvents.TOPIC,
        events.create(
            GiftcardsEvents.LOTE_ANULADO_APLICADO, tenantId, loteId.value(), new GiftcardsEvents.LoteAnuladoAplicado(loteId.value(), anuladas)));
    LOG.with("lote_id", loteId).with("giftcards_anuladas", anuladas).info("anulación de lote aplicada");
  }

  private List<GiftCard> fabricar(Plan plan, List<Integer> secuencias) {
    List<GiftCard> cards = new ArrayList<>(secuencias.size());
    for (int secuencia : secuencias) {
      cards.add(
          factory
              .crear(plan.tenantId(), plan.loteId(), secuencia, plan.pais(), plan.moneda(), plan.monto(), plan.vigenciaHasta(), clock.instant())
              .card());
    }
    return cards;
  }

  private static Set<Integer> faltantes(List<GiftCard> intentadas, Set<Integer> guardadas) {
    Set<Integer> faltantes = new TreeSet<>();
    for (GiftCard card : intentadas) {
      if (!guardadas.contains(card.secuencia())) {
        faltantes.add(card.secuencia());
      }
    }
    return faltantes;
  }
}
