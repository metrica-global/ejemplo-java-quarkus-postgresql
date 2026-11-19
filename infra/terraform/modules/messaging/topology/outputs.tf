# The Zonacards messaging topology, declared exactly once.
#
# This module has no resources and no providers: it only returns data. Both
# the AWS environments (through modules/platform) and the local emulator root
# (local/) feed these two outputs to modules/messaging, so the topics, queues
# and filters a developer runs against are the ones that get deployed.
#
# Event types must match the `type` message attribute the services publish
# (see docs/eventos/asyncapi.yaml).

output "topics" {
  description = "Topics, keyed by logical name. One per publishing domain."
  value = {
    "lotes-events"     = {}
    "giftcards-events" = {}
  }
}

output "queues" {
  description = "Queues, keyed by logical name. Every queue gets its own dead-letter queue."
  value = {
    # What giftcards needs to hear from lotes.
    "giftcards-inbox" = {
      visibility_timeout_seconds = 60
      subscribe_to = {
        topic = "lotes-events"
        event_types = [
          "zonacards.lotes.lote-solicitado.v1",
          "zonacards.lotes.lote-anulado.v1",
        ]
      }
    }

    # What lotes needs to hear from giftcards.
    "lotes-inbox" = {
      visibility_timeout_seconds = 60
      subscribe_to = {
        topic = "giftcards-events"
        event_types = [
          "zonacards.giftcards.generacion-iniciada.v1",
          "zonacards.giftcards.fragmento-generado.v1",
          "zonacards.giftcards.fragmento-fallido.v1",
          "zonacards.giftcards.lote-anulado-aplicado.v1",
        ]
      }
    }

    # Internal work queue of giftcards: no subscription, the service sends its
    # own "generate fragment" commands to it. Its depth is the autoscaling
    # signal. A fragment is CPU heavy, hence the longer visibility timeout and
    # the more tolerant age alarm.
    "giftcards-generacion" = {
      visibility_timeout_seconds       = 300
      oldest_message_age_alarm_seconds = 1800
    }
  }
}
