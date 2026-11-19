output "service_name" {
  description = "Name of the ECS service."
  value       = local.service.name
}

output "service_arn" {
  description = "ARN of the ECS service."
  value       = local.service.arn
}

output "container_name" {
  description = "Name of the container inside both task definitions. The deploy pipeline replaces the image of this container."
  value       = var.service_name
}

output "task_definition_family" {
  description = "Family of the service task definition. The deploy pipeline registers new revisions in it."
  value       = aws_ecs_task_definition.service.family
}

output "migrate_task_definition_family" {
  description = "Family of the migration task definition, or null when the service has none."
  value       = one(aws_ecs_task_definition.migrate[*].family)
}

output "task_role_arn" {
  description = "ARN of the task role (application permissions)."
  value       = aws_iam_role.task.arn
}

output "execution_role_arn" {
  description = "ARN of the execution role of the service task."
  value       = aws_iam_role.execution.arn
}

output "migrate_execution_role_arn" {
  description = "ARN of the execution role of the migration task, or null when the service has none."
  value       = one(aws_iam_role.migrate_execution[*].arn)
}

output "security_group_id" {
  description = "Security group of the tasks. Also used to run the migration task."
  value       = aws_security_group.this.id
}

output "target_group_arn" {
  description = "ARN of the target group of the service."
  value       = aws_lb_target_group.this.arn
}

output "log_group_name" {
  description = "Name of the CloudWatch log group of the service and its migration task."
  value       = aws_cloudwatch_log_group.this.name
}

output "log_group_arn" {
  description = "ARN of the CloudWatch log group of the service and its migration task."
  value       = aws_cloudwatch_log_group.this.arn
}

output "autoscaling" {
  description = "Summary of the autoscaling configuration, or null for a fixed service. null here means no scalable target exists."
  value = local.autoscaled ? {
    min_capacity = aws_appautoscaling_target.this[0].min_capacity
    max_capacity = aws_appautoscaling_target.this[0].max_capacity
    policies     = concat(aws_appautoscaling_policy.cpu[*].name, aws_appautoscaling_policy.backlog[*].name)
  } : null
}

output "max_task_count" {
  description = "Most tasks the service may run in steady state: autoscaling.max, or desired_count for a fixed service."
  value       = local.max_task_count
}

output "peak_task_count" {
  description = "Most tasks that can exist at once during a rolling deployment: max_task_count x deployment_maximum_percent."
  value       = local.peak_task_count
}

output "environment_names" {
  description = "Names of the plain environment variables of the service container, sorted."
  value       = sort(keys(var.environment))
}

output "secret_names" {
  description = "Names of the secret environment variables of the service container, sorted."
  value       = sort(keys(var.secrets))
}

output "migrate_environment_names" {
  description = "Names of the plain environment variables of the migration container, sorted. Empty when the service has no migration task."
  value       = var.migrate == null ? [] : sort(keys(var.migrate.environment))
}

output "migrate_secret_names" {
  description = "Names of the secret environment variables of the migration container, sorted. Empty when the service has no migration task."
  value       = var.migrate == null ? [] : sort(keys(var.migrate.secrets))
}
