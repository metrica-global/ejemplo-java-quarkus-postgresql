output "address" {
  description = "Hostname of the database endpoint."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "Port of the database endpoint."
  value       = aws_db_instance.this.port
}

output "db_name" {
  description = "Name of the database."
  value       = aws_db_instance.this.db_name
}

output "jdbc_url" {
  description = "JDBC URL the services connect with. TLS is required by the URL and enforced by the server."
  value       = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${var.db_name}?sslmode=require"
}

output "max_connections" {
  description = "Configured max_connections of the instance."
  value       = var.max_connections
}

output "security_group_id" {
  description = "Security group of the database."
  value       = aws_security_group.this.id
}

output "master_user_secret_arn" {
  description = "ARN of the RDS-managed secret of the master user. Only the bootstrap task reads it."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "service_secret_arns" {
  description = "Secrets Manager ARNs of the role passwords, keyed by service name: { owner_password, app_password }."
  value = {
    for name in keys(var.services) : name => {
      owner_password = local.owner_password_arns[name]
      app_password   = local.app_password_arns[name]
    }
  }
}

output "bootstrap_task_definition_family" {
  description = "Family of the one-off task definition that creates roles and schemas. Run it with aws ecs run-task."
  value       = aws_ecs_task_definition.bootstrap.family
}

output "bootstrap_security_group_id" {
  description = "Security group to run the bootstrap task with. It is the only non-service source the database accepts."
  value       = aws_security_group.bootstrap.id
}

output "bootstrap_log_group_name" {
  description = "Log group with the output of the bootstrap task."
  value       = aws_cloudwatch_log_group.bootstrap.name
}

output "bootstrap_sql_found" {
  description = "Whether the bootstrap SQL script was found and embedded. false means the task definition carries a placeholder that fails when run."
  value       = local.bootstrap_sql_found
}
