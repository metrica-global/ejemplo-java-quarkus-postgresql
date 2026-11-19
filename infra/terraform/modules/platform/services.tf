# The services of the environment: the deployments of the ONE application image.
#
#   nucleo                    always exists; serves every module that has not
#                             been promoted. By default, all of them.
#   one per promoted module   the same image serving one module, with its own
#                             size and its own autoscaling.
#
# Both come from the same module (modules/ecs-service). What differs between
# two deployments is configuration only: ZC_MODULES and, for a promoted
# module, the fact that it receives nothing of the other modules.

locals {
  # Environment variables every deployment receives.
  #
  # One variable the application understands is deliberately NOT set here:
  #   ZC_AWS_ENDPOINT  only for the local emulator; unset means real AWS
  # Migrations never run when a service starts: they run in the migration
  # task, the only one that receives the schema owners' credentials.
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

  # The environments name a scaling queue by its LOGICAL name (as in the
  # messaging topology). CloudWatch needs the physical queue name.
  autoscaling = {
    for name, cfg in merge({ nucleo = var.nucleo }, var.promoted_modules) : name => (
      cfg.autoscaling == null ? null : merge(cfg.autoscaling, {
        queue = cfg.autoscaling.queue == null ? null : {
          name                    = module.messaging.queue_names[cfg.autoscaling.queue.name]
          backlog_per_task_target = cfg.autoscaling.queue.backlog_per_task_target
        }
      })
    )
  }

  # The image a service is created with: the release that is deployed now.
  image = "${local.repository_url}:${local.release_tag}"
}

# -----------------------------------------------------------------------------
# nucleo
# -----------------------------------------------------------------------------

module "nucleo" {
  source = "../ecs-service"

  service_name          = "nucleo"
  name_prefix           = local.name_prefix
  cluster_arn           = module.cluster.cluster_arn
  cluster_name          = module.cluster.cluster_name
  vpc_id                = module.network.vpc_id
  subnet_ids            = module.network.private_subnet_ids
  alb_security_group_id = module.cluster.alb_security_group_id

  # Its target group is in the rule of every module (routing.tf).
  target_group_arn   = aws_lb_target_group.service["nucleo"].arn
  listener_rule_arns = [for name in local.module_names : aws_lb_listener_rule.module[name].arn]
  health_check_path  = local.health_check_path
  container_port     = local.container_port

  image               = local.image
  ecr_repository_arns = [local.repository_arn]
  cpu                 = var.nucleo.cpu
  memory              = var.nucleo.memory
  cpu_architecture    = var.cpu_architecture

  desired_count = var.nucleo.desired_count
  autoscaling   = local.autoscaling["nucleo"]

  # The nucleo is given what EVERY module needs, also the ones it is not
  # serving now. A module can therefore come back to it with a change of
  # ZC_MODULES and nothing else, and a promotion never takes anything away
  # from nucleo tasks that are still running the previous configuration.
  # Contract variables are merged last: the free-form map cannot override them.
  environment = merge(var.nucleo.environment, local.common_environment, merge(values(local.module_environment)...), {
    ZC_MODULES              = join(",", local.nucleo_modules)
    ZC_CONSUMER_CONCURRENCY = tostring(var.nucleo.consumer_concurrency)
    OTEL_SERVICE_NAME       = "nucleo"
  })

  secrets = merge(values(local.module_secrets)...)

  # The ONE migration task of the application. It migrates the schema of every
  # module, each with that module's owner role; those credentials exist
  # nowhere else.
  migrate = {
    environment = merge(
      {
        ZC_MODE   = "migrate"
        ZC_DB_URL = module.database.jdbc_url
      },
      { for name in local.module_names : "ZC_DB_${upper(name)}_OWNER_USER" => "${name}_owner" },
    )
    secrets = {
      for name in local.module_names :
      "ZC_DB_${upper(name)}_OWNER_PASSWORD" => module.database.service_secret_arns[name].owner_password
    }
  }

  # The union of what each module may do. No module shares a topic or a queue
  # with another, so the lists are simply concatenated (their LENGTH has to be
  # known at plan time: the service module decides with it whether a policy exists).
  publish_topic_arns = flatten([for p in values(local.module_permissions) : p.publish_topic_arns])
  consume_queue_arns = flatten([for p in values(local.module_permissions) : p.consume_queue_arns])
  send_queue_arns    = flatten([for p in values(local.module_permissions) : p.send_queue_arns])
  kms_key_arns       = module.messaging.kms_key_arns

  enable_execute_command = var.enable_execute_command
  log_retention_days     = var.log_retention_days
}

# -----------------------------------------------------------------------------
# One service per promoted module
# -----------------------------------------------------------------------------

module "promoted" {
  source   = "../ecs-service"
  for_each = var.promoted_modules

  service_name          = each.key
  name_prefix           = local.name_prefix
  cluster_arn           = module.cluster.cluster_arn
  cluster_name          = module.cluster.cluster_name
  vpc_id                = module.network.vpc_id
  subnet_ids            = module.network.private_subnet_ids
  alb_security_group_id = module.cluster.alb_security_group_id

  target_group_arn   = aws_lb_target_group.service[each.key].arn
  listener_rule_arns = [aws_lb_listener_rule.module[each.key].arn]
  health_check_path  = local.health_check_path
  container_port     = local.container_port

  # Created with the image that is deployed now: promoting a module builds
  # nothing and releases nothing.
  image               = local.image
  ecr_repository_arns = [local.repository_arn]
  cpu                 = each.value.cpu
  memory              = each.value.memory
  cpu_architecture    = var.cpu_architecture

  desired_count = each.value.desired_count
  autoscaling   = local.autoscaling[each.key]

  # Only what this module needs. This deployment could not serve another
  # module if it were asked to: it has neither its queues nor its credentials.
  environment = merge(each.value.environment, local.common_environment, local.module_environment[each.key], {
    ZC_MODULES              = each.key
    ZC_CONSUMER_CONCURRENCY = tostring(each.value.consumer_concurrency)
    OTEL_SERVICE_NAME       = each.key
  })

  secrets = local.module_secrets[each.key]

  # The application has one migration task; it is declared with the nucleo.
  migrate = null

  publish_topic_arns = local.module_permissions[each.key].publish_topic_arns
  consume_queue_arns = local.module_permissions[each.key].consume_queue_arns
  send_queue_arns    = local.module_permissions[each.key].send_queue_arns
  kms_key_arns       = module.messaging.kms_key_arns

  enable_execute_command = var.enable_execute_command
  log_retention_days     = var.log_retention_days
}

# -----------------------------------------------------------------------------
# Database connection budget
#
# Every task opens up to ZC_DB_<MODULE>_POOL_MAX connections with the
# <module>_app role, and that role has a CONNECTION LIMIT in PostgreSQL.
# Scaling out past what the limit allows does not add capacity: the new tasks
# cannot connect, fail their health check and are replaced in a loop. The same
# happens if the limits of all roles add up to more than the instance accepts.
#
# Both conditions are checked at plan time, so a change that breaks the budget
# (a promotion, a higher autoscaling.max, a bigger pool, a smaller instance)
# is rejected before anything is touched.
#
# A module's role must hold the connections of EVERY deployment that can serve
# the module at the same time:
#   - the nucleo, always. It is where a module lives by default and where it
#     can return, and while a promotion is in progress its tasks still hold
#     their connections;
#   - the module's own service, when it has one.
# The task count used is the PEAK one: during a rolling deployment old and new
# tasks overlap (deployment_maximum_percent).
# -----------------------------------------------------------------------------

locals {
  db_connection_budget = {
    for name in local.module_names : name => {
      pool_max               = var.modules[name].db_pool_max
      nucleo_peak_tasks      = module.nucleo.peak_task_count
      own_service_peak_tasks = local.placement[name].own_service ? module.promoted[name].peak_task_count : 0
      peak_connections = (
        (module.nucleo.peak_task_count + (local.placement[name].own_service ? module.promoted[name].peak_task_count : 0))
        * var.modules[name].db_pool_max
      )
      connection_limit = var.modules[name].db_connection_limit
    }
  }

  db_connections_committed = (
    sum([for b in values(local.db_connection_budget) : b.connection_limit])
    + var.database.reserved_connections
  )

  db_modules_over_budget = [
    for name, b in local.db_connection_budget :
    "${name}: (${b.nucleo_peak_tasks} nucleo tasks + ${b.own_service_peak_tasks} tasks of its own service) x ${b.pool_max} connections = ${b.peak_connections}, but the CONNECTION LIMIT of ${name}_app is ${b.connection_limit}"
    if b.peak_connections > b.connection_limit
  ]
}

resource "terraform_data" "db_connection_budget" {
  # Recorded so the numbers behind the check are visible in the plan.
  input = {
    modules              = local.db_connection_budget
    reserved_connections = var.database.reserved_connections
    committed            = local.db_connections_committed
    max_connections      = var.database.max_connections
  }

  lifecycle {
    precondition {
      condition     = length(local.db_modules_over_budget) == 0
      error_message = "Database connection budget exceeded. ${join("; ", local.db_modules_over_budget)}. Raise modules.<module>.db_connection_limit (and run the database bootstrap task again, which applies it), or lower db_pool_max or the maximum number of tasks."
    }

    precondition {
      condition     = local.db_connections_committed <= var.database.max_connections
      error_message = "The connection limits of the module roles plus the reserve add up to ${local.db_connections_committed}, but the instance accepts ${var.database.max_connections} connections (database.max_connections). Use a larger instance class or lower the limits."
    }
  }
}
