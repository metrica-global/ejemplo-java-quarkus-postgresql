locals {
  service_modules = {
    lotes     = module.lotes
    giftcards = module.giftcards
  }
}

# -----------------------------------------------------------------------------
# What the deploy pipeline needs
# -----------------------------------------------------------------------------

output "cluster_name" {
  description = "Name of the ECS cluster."
  value       = module.cluster.cluster_name
}

output "services" {
  description = <<-EOT
    Per service, everything the deploy pipeline needs: the ECS service, the
    container whose image it replaces, the task definition families it
    registers revisions in, the migration task family it runs first, the image
    repository, and the autoscaling summary (null for a fixed service).
  EOT
  value = {
    for name, svc in local.service_modules : name => {
      service_name                   = svc.service_name
      container_name                 = svc.container_name
      task_definition_family         = svc.task_definition_family
      migrate_task_definition_family = svc.migrate_task_definition_family
      security_group_id              = svc.security_group_id
      log_group_name                 = svc.log_group_name
      repository_url                 = local.repository_urls[name]
      max_task_count                 = svc.max_task_count
      autoscaling                    = svc.autoscaling
    }
  }
}

output "private_subnet_ids" {
  description = "Private subnets, for the network configuration of aws ecs run-task (migration and bootstrap tasks)."
  value       = module.network.private_subnet_ids
}

output "ecr_registry" {
  description = "Hostname of the ECR registry the images are pushed to."
  value       = local.ecr_registry
}

output "github_roles" {
  description = "ARNs of the roles GitHub Actions assumes: build (null unless this environment owns the account-level resources), deploy and plan. null when no repository was given."
  value = one([
    for m in module.github_oidc : {
      build  = m.build_role_arn
      deploy = m.deploy_role_arn
      plan   = m.plan_role_arn
    }
  ])
}

# -----------------------------------------------------------------------------
# What an operator needs
# -----------------------------------------------------------------------------

output "api_base_url" {
  description = "Base URL of the API on the load balancer's own DNS name."
  value       = module.cluster.base_url
}

output "alb_dns_name" {
  description = "DNS name of the load balancer, for a CNAME or alias record."
  value       = module.cluster.alb_dns_name
}

output "alb_zone_id" {
  description = "Route 53 zone ID of the load balancer, for an alias record."
  value       = module.cluster.alb_zone_id
}

output "db_bootstrap" {
  description = "How to run the one-off task that creates the database roles and schemas: task definition family, security group and log group. sql_found = false means the SQL script was missing and the task would fail."
  value = {
    task_definition_family = module.database.bootstrap_task_definition_family
    security_group_id      = module.database.bootstrap_security_group_id
    log_group_name         = module.database.bootstrap_log_group_name
    sql_found              = module.database.bootstrap_sql_found
  }
}

output "db_connection_budget" {
  description = "The numbers behind the connection budget check: per service peak tasks x pool against the role's CONNECTION LIMIT, and the total against max_connections."
  value       = terraform_data.db_connection_budget.input
}

output "messaging" {
  description = "Topic ARNs, queue URLs and dead-letter queue URLs, keyed by logical name."
  value = {
    topic_arns = module.messaging.topic_arns
    queue_urls = module.messaging.queue_urls
    dlq_urls   = module.messaging.dlq_urls
  }
}
