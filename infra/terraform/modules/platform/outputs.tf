locals {
  # Every service of the environment: the nucleo and one per promoted module.
  service_modules = merge({ nucleo = module.nucleo }, module.promoted)

  service_modules_served = merge(
    { nucleo = local.nucleo_modules },
    { for name in keys(module.promoted) : name => [name] },
  )
}

# -----------------------------------------------------------------------------
# What the deploy pipeline needs. It reads it from the parameters below, not
# from these outputs; the outputs are for people.
# -----------------------------------------------------------------------------

output "cluster_name" {
  description = "Name of the ECS cluster."
  value       = module.cluster.cluster_name
}

output "topology" {
  description = "Where each module of the application is: whether it has its own service, whether the nucleo serves it, and which service receives its API."
  value       = terraform_data.topology.input
}

output "services" {
  description = <<-EOT
    Per service (the nucleo and one per promoted module): the modules it
    serves, the ECS service and its target group, the container whose image
    the pipeline replaces, the task definition family, and the autoscaling
    summary (null for a fixed service).
  EOT
  value = {
    for name, svc in local.service_modules : name => {
      modules                = local.service_modules_served[name]
      service_name           = svc.service_name
      service_arn            = svc.service_arn
      target_group_arn       = aws_lb_target_group.service[name].arn
      container_name         = svc.container_name
      task_definition_family = svc.task_definition_family
      security_group_id      = svc.security_group_id
      log_group_name         = svc.log_group_name
      max_task_count         = svc.max_task_count
      autoscaling            = svc.autoscaling
    }
  }
}

output "routes" {
  description = "Per module, its listener rule: the paths of its API and the weight of each target group. The rule is the same resource in every topology; only the weights change."
  value = {
    for name, rule in aws_lb_listener_rule.module : name => {
      listener_rule_arn = rule.arn
      path_patterns     = local.catalog[name].path_patterns
      priority          = rule.priority
      weights = merge(
        { nucleo = local.placement[name].routed_to == "nucleo" ? 100 : 0 },
        local.placement[name].own_service ? { (name) = local.placement[name].routed_to == "nucleo" ? 0 : 100 } : {},
      )
    }
  }
}

output "migrate" {
  description = "The one migration task of the application: task definition family, and the security group and log group it runs with."
  value       = local.deploy_descriptor.migrate
}

output "deploy_parameters" {
  description = "Names of the SSM parameters the deploy pipeline uses: `deploy` (what to deploy, written by Terraform) and `release` (the tag that is deployed, written by the pipeline)."
  value = {
    deploy  = aws_ssm_parameter.deploy.name
    release = aws_ssm_parameter.release.name
  }
}

output "deploy_descriptor" {
  description = "Content of the `deploy` parameter: cluster, image repository, container, subnets, migration task and the services in deployment order."
  value       = local.deploy_descriptor
}

output "repository_url" {
  description = "URL of the image repository of the application."
  value       = local.repository_url
}

output "private_subnet_ids" {
  description = "Private subnets, for the network configuration of aws ecs run-task (migration and bootstrap tasks)."
  value       = module.network.private_subnet_ids
}

output "ecr_registry" {
  description = "Hostname of the ECR registry the image is pushed to."
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
  description = "The numbers behind the connection budget check: per module, the peak tasks of every deployment that can serve it x its pool against the role's CONNECTION LIMIT, and the total against max_connections."
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
