# The modules of the application, as the infrastructure sees them.
#
# This is the Terraform side of com.zonacards.app.AppContainer: one entry per
# module, with what a deployment needs in order to serve it. Adding a module
# to the application is one entry here (plus its topics and queues in
# modules/messaging/topology and its sizing in var.modules).
#
# Nothing in this file says WHERE a module runs. Every module lives in the
# `nucleo` service unless var.promoted_modules gives it a service of its own;
# the application is the same image either way.

locals {
  catalog = {
    lotes = {
      # Requests the load balancer sends to whoever serves the module.
      path_patterns     = ["/api/v1/lotes*"]
      listener_priority = 100

      # Environment variable of the application => logical name in the
      # messaging topology. The variable is what com.zonacards.server reads;
      # the permission follows from the kind: publish to its topics, consume
      # its queues.
      topic_variables = { ZC_TOPIC_LOTES_EVENTS_ARN = "lotes-events" }
      queue_variables = { ZC_QUEUE_LOTES_INBOX_URL = "lotes-inbox" }
      # Queues the module also SENDS to (its own work queues).
      send_queues = []

      # Environment variable => key of local.module_secret_arns[<module>].
      secret_variables = {}
    }

    giftcards = {
      path_patterns     = ["/api/v1/giftcards*"]
      listener_priority = 200

      topic_variables = { ZC_TOPIC_GIFTCARDS_EVENTS_ARN = "giftcards-events" }
      queue_variables = {
        ZC_QUEUE_GIFTCARDS_INBOX_URL      = "giftcards-inbox"
        ZC_QUEUE_GIFTCARDS_GENERACION_URL = "giftcards-generacion"
      }
      send_queues = ["giftcards-generacion"]

      secret_variables = {
        ZC_GIFTCARDS_CODIGO_KEY      = "codigo-key"
        ZC_GIFTCARDS_CODIGO_HMAC_KEY = "codigo-hmac-key"
      }
    }
  }

  module_names = sort(keys(local.catalog))

  # Secrets of a module other than its database password (see secrets.tf).
  module_secret_arns = {
    giftcards = module.giftcards_keys.secret_arns
  }
}

# -----------------------------------------------------------------------------
# Where each module is
#
#   var.promoted_modules                  own service   the nucleo serves it   its API goes to
#   -----------------------------------   -----------   --------------------   ---------------
#   module not listed                         no               yes               nucleo
#   transition = "traffic-in-nucleo"          yes              yes               nucleo
#   transition = null (promoted)              yes              no                own service
#   transition = "traffic-in-service"         yes              yes               own service
#
# The two transition states are the ones in which the module is served TWICE.
# They exist so that every change is a move between two neighbouring rows, and
# in every row the module's API goes to a deployment that serves it:
#
#   promote   not listed -> traffic-in-nucleo -> promoted
#   demote    promoted -> traffic-in-service -> traffic-in-nucleo -> not listed
#
# Bringing a module back takes one step more than promoting it, on purpose.
# Removing a service destroys it at once, so the apply that removes it must
# not be the one that takes its traffic away; stopping to serve a module in
# the nucleo only takes effect on its next deployment, so it can share an
# apply with the change of route. See infra/terraform/README.md.
# -----------------------------------------------------------------------------

locals {
  placement = {
    for name in local.module_names : name => {
      own_service = contains(keys(var.promoted_modules), name)
      in_nucleo   = !contains(keys(var.promoted_modules), name) || try(var.promoted_modules[name].transition, null) != null
      routed_to = (
        contains(keys(var.promoted_modules), name) && try(var.promoted_modules[name].transition, null) != "traffic-in-nucleo"
        ? name
        : "nucleo"
      )
    }
  }

  nucleo_modules = [for name in local.module_names : name if local.placement[name].in_nucleo]
}

# -----------------------------------------------------------------------------
# What a deployment receives for each module it serves
# -----------------------------------------------------------------------------

locals {
  module_environment = {
    for name, m in local.catalog : name => merge(
      { for variable, topic in m.topic_variables : variable => module.messaging.topic_arns[topic] },
      { for variable, queue in m.queue_variables : variable => module.messaging.queue_urls[queue] },
      {
        # One datasource per module, with that module's own database role.
        ("ZC_DB_${upper(name)}_APP_USER") = "${name}_app"
        ("ZC_DB_${upper(name)}_POOL_MAX") = tostring(var.modules[name].db_pool_max)
      },
    )
  }

  module_secrets = {
    for name, m in local.catalog : name => merge(
      { for variable, key in m.secret_variables : variable => local.module_secret_arns[name][key] },
      { ("ZC_DB_${upper(name)}_APP_PASSWORD") = module.database.service_secret_arns[name].app_password },
    )
  }

  # Least privilege per module: its own topics, its own queues, nothing else.
  module_permissions = {
    for name, m in local.catalog : name => {
      publish_topic_arns = [for topic in values(m.topic_variables) : module.messaging.topic_arns[topic]]
      consume_queue_arns = [for queue in values(m.queue_variables) : module.messaging.queue_arns[queue]]
      send_queue_arns    = [for queue in m.send_queues : module.messaging.queue_arns[queue]]
    }
  }
}

# -----------------------------------------------------------------------------
# Checked at plan time, so a wrong topology is rejected before anything changes.
# -----------------------------------------------------------------------------

resource "terraform_data" "topology" {
  # Recorded so the plan shows where every module is and where it is going.
  input = {
    nucleo    = local.nucleo_modules
    placement = local.placement
  }

  lifecycle {
    precondition {
      condition     = length(local.nucleo_modules) > 0
      error_message = "Every module is promoted and none is left in the nucleo. The nucleo must serve at least one module: leave one out of promoted_modules."
    }
  }
}
