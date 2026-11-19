# Identical in every environment.

output "region" {
  description = "AWS region of the environment."
  value       = var.region
}

output "cluster_name" {
  description = "Name of the ECS cluster."
  value       = module.zonacards.cluster_name
}

output "topology" {
  description = "Where each module is: whether it has its own service, whether the nucleo serves it, and which service receives its API."
  value       = module.zonacards.topology
}

output "services" {
  description = "Per service (the nucleo and one per promoted module): modules served, ECS service, container, task definition family, security group, log group and autoscaling summary."
  value       = module.zonacards.services
}

output "routes" {
  description = "Per module, the paths of its API and the weight of each target group in its listener rule."
  value       = module.zonacards.routes
}

output "autoscaling" {
  description = "Which services scale: null for a fixed service, otherwise its capacity range and policies."
  value       = { for name, svc in module.zonacards.services : name => svc.autoscaling }
}

output "deploy_parameters" {
  description = "SSM parameters of the deploy pipeline: what to deploy (written by Terraform) and the tag that is deployed (written by the pipeline)."
  value       = module.zonacards.deploy_parameters
}

output "deploy_descriptor" {
  description = "Content of the deploy parameter: cluster, repository, container, subnets, migration task and the services in deployment order."
  value       = module.zonacards.deploy_descriptor
}

output "private_subnet_ids" {
  description = "Private subnets, for aws ecs run-task."
  value       = module.zonacards.private_subnet_ids
}

output "ecr_registry" {
  description = "Hostname of the ECR registry."
  value       = module.zonacards.ecr_registry
}

output "repository_url" {
  description = "URL of the image repository of the application."
  value       = module.zonacards.repository_url
}

output "github_roles" {
  description = "ARNs of the roles GitHub Actions assumes (build, deploy, plan)."
  value       = module.zonacards.github_roles
}

output "api_base_url" {
  description = "Base URL of the API on the load balancer's DNS name."
  value       = module.zonacards.api_base_url
}

output "alb_dns_name" {
  description = "DNS name of the load balancer."
  value       = module.zonacards.alb_dns_name
}

output "db_bootstrap" {
  description = "Task definition, security group and log group of the one-off database bootstrap task."
  value       = module.zonacards.db_bootstrap
}

output "db_connection_budget" {
  description = "Numbers behind the database connection budget check."
  value       = module.zonacards.db_connection_budget
}

output "messaging" {
  description = "Topic ARNs, queue URLs and dead-letter queue URLs keyed by logical name."
  value       = module.zonacards.messaging
}
