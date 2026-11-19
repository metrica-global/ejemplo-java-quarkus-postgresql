package com.zonacards.giftcards.events;

/**
 * Internal messages of the giftcards domain. A command is not an event: it is an instruction
 * this domain sends to its own workers, straight to its own work queue. It is not published to
 * the topic and it is not part of the public contract, so it can change with the code.
 */
public final class GeneracionCommands {

  /** The work queue. Its depth is the signal the service autoscales on. */
  public static final String QUEUE = "giftcards-generacion";

  public static final String GENERAR_FRAGMENTO = "zonacards.giftcards.generar-fragmento.v1";

  private GeneracionCommands() {}

  public record GenerarFragmento(String loteId, int fragmento, int totalFragmentos, int desdeSecuencia, int cantidad) {}
}
