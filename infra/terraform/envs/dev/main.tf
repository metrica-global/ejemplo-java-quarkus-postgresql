# Zonacards environment.
#
# This file is IDENTICAL in envs/dev, envs/qa and envs/prod. Everything that
# differs between environments is a value in terraform.tfvars; everything that
# must not differ lives in modules/platform.

module "zonacards" {
  source = "../../modules/platform"

  environment        = var.environment
  log_retention_days = var.log_retention_days
  cpu_architecture   = var.cpu_architecture

  network = {
    vpc_cidr           = var.vpc_cidr
    az_count           = var.az_count
    single_nat_gateway = var.single_nat_gateway
    enable_flow_logs   = var.enable_flow_logs
  }

  load_balancer = {
    certificate_arn     = var.certificate_arn
    allow_plain_http    = var.alb_allow_plain_http
    ingress_cidrs       = var.alb_ingress_cidrs
    deletion_protection = var.alb_deletion_protection
  }

  database = {
    instance_class        = var.db_instance_class
    max_connections       = var.db_max_connections
    reserved_connections  = var.db_reserved_connections
    multi_az              = var.db_multi_az
    backup_retention_days = var.db_backup_retention_days
    deletion_protection   = var.db_deletion_protection
    skip_final_snapshot   = var.db_skip_final_snapshot
  }

  secret_recovery_window_days = var.secret_recovery_window_days

  # ---------------------------------------------------------------------------
  # The two services.
  #
  # Same module, same arguments. `lotes` runs a fixed number of tasks.
  # `giftcards` scales horizontally. The whole difference is the `autoscaling`
  # object: without it no scalable target and no scaling policy are created.
  # ---------------------------------------------------------------------------

  lotes = {
    cpu                 = var.lotes_cpu
    memory              = var.lotes_memory
    db_pool_max         = var.lotes_db_pool_max
    db_connection_limit = var.lotes_db_connection_limit

    desired_count = var.lotes_tasks # fixed: Terraform owns the task count
  }

  giftcards = {
    cpu                 = var.giftcards_cpu
    memory              = var.giftcards_memory
    db_pool_max         = var.giftcards_db_pool_max
    db_connection_limit = var.giftcards_db_connection_limit

    autoscaling = {
      min        = var.giftcards_min_tasks
      max        = var.giftcards_max_tasks
      cpu_target = var.giftcards_cpu_target # average CPU, percent

      # Backlog per task: visible messages in the work queue / running tasks.
      queue = {
        name                    = "giftcards-generacion"
        backlog_per_task_target = var.giftcards_backlog_per_task_target
      }

      scale_out_cooldown = 60  # add tasks quickly
      scale_in_cooldown  = 300 # remove them slowly
    }
  }

  # ---------------------------------------------------------------------------
  # Received, not provisioned
  # ---------------------------------------------------------------------------

  image_tag                   = var.image_tag
  oidc_issuer                 = var.oidc_issuer
  oidc_audience               = var.oidc_audience
  otel_exporter_otlp_endpoint = var.otel_exporter_otlp_endpoint
  alarm_topic_arn             = var.alarm_topic_arn

  # ---------------------------------------------------------------------------
  # Account-level resources and CI/CD
  # ---------------------------------------------------------------------------

  create_account_level_resources = var.create_account_level_resources
  github_repository              = var.github_repository
  state_bucket_arn               = var.state_bucket_arn
  enable_execute_command         = var.enable_execute_command
}
