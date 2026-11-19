# The two services, side by side.
#
# Both blocks call the same module with the same shape. What differs is what
# the contract with each service says: its routes, its environment variables,
# its secrets and the exact topics and queues its role may touch. Whether a
# service scales is decided by the environment (var.lotes / var.giftcards): it
# does if its `autoscaling` attribute is set.

locals {
  # Environment variables both services receive.
  #
  # One variable the services understand is deliberately NOT set here:
  #   ZC_AWS_ENDPOINT  only for the local emulator; unset means real AWS
  # Migrations never run at service start: they run in the <service>-migrate
  # task, the only one that receives the schema owner's credentials.
  common_environment = merge(
    {
      ZC_ENV           = var.environment
      ZC_DB_URL        = module.database.jdbc_url
      ZC_OIDC_ISSUER   = var.oidc_issuer
      ZC_OIDC_AUDIENCE = var.oidc_audience
    },
    # Without a collector there is nowhere to export to: disable the SDK
    # instead of letting it retry against a default endpoint.
    var.otel_exporter_otlp_endpoint == ""
    ? { QUARKUS_OTEL_SDK_DISABLED = "true" }
    : { OTEL_EXPORTER_OTLP_ENDPOINT = var.otel_exporter_otlp_endpoint },
  )

  # The environments name the scaling queue by its LOGICAL name (as in the
  # messaging topology). CloudWatch needs the physical queue name.
  autoscaling = {
    for svc, cfg in { lotes = var.lotes, giftcards = var.giftcards } : svc => (
      cfg.autoscaling == null ? null : merge(cfg.autoscaling, {
        queue = cfg.autoscaling.queue == null ? null : {
          name                    = module.messaging.queue_names[cfg.autoscaling.queue.name]
          backlog_per_task_target = cfg.autoscaling.queue.backlog_per_task_target
        }
      })
    )
  }

  # Arguments that are the same for both services.
  service_placement = {
    name_prefix           = local.name_prefix
    cluster_arn           = module.cluster.cluster_arn
    cluster_name          = module.cluster.cluster_name
    vpc_id                = module.network.vpc_id
    subnet_ids            = module.network.private_subnet_ids
    alb_security_group_id = module.cluster.alb_security_group_id
    listener_arn          = module.cluster.listener_arn
  }
}

# -----------------------------------------------------------------------------
# lotes
# -----------------------------------------------------------------------------

module "lotes" {
  source = "../ecs-service"

  service_name          = "lotes"
  name_prefix           = local.service_placement.name_prefix
  cluster_arn           = local.service_placement.cluster_arn
  cluster_name          = local.service_placement.cluster_name
  vpc_id                = local.service_placement.vpc_id
  subnet_ids            = local.service_placement.subnet_ids
  alb_security_group_id = local.service_placement.alb_security_group_id
  listener_arn          = local.service_placement.listener_arn

  path_patterns          = ["/api/v1/lotes*"]
  listener_rule_priority = 100

  image               = "${local.repository_urls.lotes}:${var.image_tag}"
  ecr_repository_arns = [local.repository_arns.lotes]
  cpu                 = var.lotes.cpu
  memory              = var.lotes.memory
  cpu_architecture    = var.cpu_architecture

  desired_count = var.lotes.desired_count
  autoscaling   = local.autoscaling.lotes

  # Contract variables are merged last: the free-form map cannot override them.
  environment = merge(var.lotes.environment, local.common_environment, {
    ZC_DB_APP_USER            = "lotes_app"
    ZC_DB_POOL_MAX            = tostring(var.lotes.db_pool_max)
    ZC_CONSUMER_CONCURRENCY   = tostring(var.lotes.consumer_concurrency)
    ZC_TOPIC_LOTES_EVENTS_ARN = module.messaging.topic_arns["lotes-events"]
    ZC_QUEUE_LOTES_INBOX_URL  = module.messaging.queue_urls["lotes-inbox"]
  })

  secrets = {
    ZC_DB_APP_PASSWORD = module.database.service_secret_arns["lotes"].app_password
  }

  # The schema owner's credentials exist only in the migration task.
  migrate = {
    environment = {
      ZC_MODE          = "migrate"
      ZC_DB_URL        = module.database.jdbc_url
      ZC_DB_OWNER_USER = "lotes_owner"
    }
    secrets = {
      ZC_DB_OWNER_PASSWORD = module.database.service_secret_arns["lotes"].owner_password
    }
  }

  # Least privilege: its own topic, its own inbox, nothing of giftcards.
  publish_topic_arns = [module.messaging.topic_arns["lotes-events"]]
  consume_queue_arns = [module.messaging.queue_arns["lotes-inbox"]]
  send_queue_arns    = []
  kms_key_arns       = module.messaging.kms_key_arns

  enable_execute_command = var.enable_execute_command
  log_retention_days     = var.log_retention_days
}

# -----------------------------------------------------------------------------
# giftcards
# -----------------------------------------------------------------------------

module "giftcards" {
  source = "../ecs-service"

  service_name          = "giftcards"
  name_prefix           = local.service_placement.name_prefix
  cluster_arn           = local.service_placement.cluster_arn
  cluster_name          = local.service_placement.cluster_name
  vpc_id                = local.service_placement.vpc_id
  subnet_ids            = local.service_placement.subnet_ids
  alb_security_group_id = local.service_placement.alb_security_group_id
  listener_arn          = local.service_placement.listener_arn

  path_patterns          = ["/api/v1/giftcards*"]
  listener_rule_priority = 200

  image               = "${local.repository_urls.giftcards}:${var.image_tag}"
  ecr_repository_arns = [local.repository_arns.giftcards]
  cpu                 = var.giftcards.cpu
  memory              = var.giftcards.memory
  cpu_architecture    = var.cpu_architecture

  desired_count = var.giftcards.desired_count
  autoscaling   = local.autoscaling.giftcards

  environment = merge(var.giftcards.environment, local.common_environment, {
    ZC_DB_APP_USER                    = "giftcards_app"
    ZC_DB_POOL_MAX                    = tostring(var.giftcards.db_pool_max)
    ZC_CONSUMER_CONCURRENCY           = tostring(var.giftcards.consumer_concurrency)
    ZC_TOPIC_GIFTCARDS_EVENTS_ARN     = module.messaging.topic_arns["giftcards-events"]
    ZC_QUEUE_GIFTCARDS_INBOX_URL      = module.messaging.queue_urls["giftcards-inbox"]
    ZC_QUEUE_GIFTCARDS_GENERACION_URL = module.messaging.queue_urls["giftcards-generacion"]
  })

  secrets = {
    ZC_DB_APP_PASSWORD           = module.database.service_secret_arns["giftcards"].app_password
    ZC_GIFTCARDS_CODIGO_KEY      = module.giftcards_keys.secret_arns["codigo-key"]
    ZC_GIFTCARDS_CODIGO_HMAC_KEY = module.giftcards_keys.secret_arns["codigo-hmac-key"]
  }

  migrate = {
    environment = {
      ZC_MODE          = "migrate"
      ZC_DB_URL        = module.database.jdbc_url
      ZC_DB_OWNER_USER = "giftcards_owner"
    }
    secrets = {
      ZC_DB_OWNER_PASSWORD = module.database.service_secret_arns["giftcards"].owner_password
    }
  }

  # Its own topic and inbox, plus the internal work queue it both fills and
  # drains.
  publish_topic_arns = [module.messaging.topic_arns["giftcards-events"]]
  consume_queue_arns = [
    module.messaging.queue_arns["giftcards-inbox"],
    module.messaging.queue_arns["giftcards-generacion"],
  ]
  send_queue_arns = [module.messaging.queue_arns["giftcards-generacion"]]
  kms_key_arns    = module.messaging.kms_key_arns

  enable_execute_command = var.enable_execute_command
  log_retention_days     = var.log_retention_days
}

# -----------------------------------------------------------------------------
# Database connection budget
#
# Every task opens up to ZC_DB_POOL_MAX connections with the <service>_app
# role, and that role has a CONNECTION LIMIT in PostgreSQL. Scaling out past
# what the limit allows does not add capacity: the new tasks cannot connect,
# fail their health check and are replaced in a loop. The same happens if the
# limits of all roles add up to more than the instance accepts.
#
# Both conditions are checked at plan time, so a change that breaks the budget
# (a higher autoscaling.max, a bigger pool, a smaller instance) is rejected
# before anything is touched. The task count used is the PEAK one: during a
# rolling deployment old and new tasks overlap (deployment_maximum_percent).
# -----------------------------------------------------------------------------

locals {
  db_connection_budget = {
    lotes = {
      peak_tasks       = module.lotes.peak_task_count
      pool_max         = var.lotes.db_pool_max
      peak_connections = module.lotes.peak_task_count * var.lotes.db_pool_max
      connection_limit = var.lotes.db_connection_limit
    }
    giftcards = {
      peak_tasks       = module.giftcards.peak_task_count
      pool_max         = var.giftcards.db_pool_max
      peak_connections = module.giftcards.peak_task_count * var.giftcards.db_pool_max
      connection_limit = var.giftcards.db_connection_limit
    }
  }

  db_connections_committed = (
    sum([for b in values(local.db_connection_budget) : b.connection_limit])
    + var.database.reserved_connections
  )

  db_services_over_budget = [
    for name, b in local.db_connection_budget :
    "${name}: ${b.peak_tasks} tasks x ${b.pool_max} connections = ${b.peak_connections}, but the CONNECTION LIMIT of ${name}_app is ${b.connection_limit}"
    if b.peak_connections > b.connection_limit
  ]
}

resource "terraform_data" "db_connection_budget" {
  # Recorded so the numbers behind the check are visible in the plan.
  input = {
    services             = local.db_connection_budget
    reserved_connections = var.database.reserved_connections
    committed            = local.db_connections_committed
    max_connections      = var.database.max_connections
  }

  lifecycle {
    precondition {
      condition     = length(local.db_services_over_budget) == 0
      error_message = "Database connection budget exceeded. ${join("; ", local.db_services_over_budget)}. Raise db_connection_limit, or lower db_pool_max or the maximum number of tasks."
    }

    precondition {
      condition     = local.db_connections_committed <= var.database.max_connections
      error_message = "The connection limits of the service roles plus the reserve add up to ${local.db_connections_committed}, but the instance accepts ${var.database.max_connections} connections (database.max_connections). Use a larger instance class or lower the limits."
    }
  }
}
