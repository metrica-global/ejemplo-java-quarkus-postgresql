variable "name_prefix" {
  description = "Prefix for resource names, e.g. zonacards-dev."
  type        = string
}

variable "vpc_id" {
  description = "VPC of the database."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets of the database subnet group. At least two availability zones."
  type        = list(string)
}

variable "allowed_security_group_ids" {
  description = "Security groups allowed to open PostgreSQL connections, keyed by a static label (e.g. the service name). A map, not a list, so the keys are known at plan time while the IDs are not."
  type        = map(string)
}

# -----------------------------------------------------------------------------
# Instance
# -----------------------------------------------------------------------------

variable "engine_version" {
  description = "PostgreSQL version. Giving only the major version lets RDS pick the current minor and keeps Terraform quiet across automatic minor upgrades."
  type        = string
  default     = "18"
}

variable "parameter_group_family" {
  description = "Parameter group family. Must match the major version of engine_version."
  type        = string
  default     = "postgres18"
}

variable "instance_class" {
  description = "RDS instance class."
  type        = string
}

variable "max_connections" {
  description = "Value of the max_connections parameter. Set explicitly, instead of the memory-based RDS default, so that the connection budget of the services is checked against a known number. Do not set it above what the instance class can hold."
  type        = number
}

variable "db_name" {
  description = "Name of the database. Every service gets a schema in it."
  type        = string
  default     = "zonacards"
}

variable "master_username" {
  description = "Master user. Its password is generated and rotated by RDS in Secrets Manager; only the bootstrap task uses it."
  type        = string
  default     = "zonacards_admin"
}

variable "allocated_storage_gb" {
  description = "Initial storage, in GiB."
  type        = number
  default     = 20
}

variable "max_allocated_storage_gb" {
  description = "Upper limit of storage autoscaling, in GiB."
  type        = number
  default     = 100
}

variable "multi_az" {
  description = "Run a synchronous standby in another availability zone."
  type        = bool
  default     = false
}

variable "backup_retention_days" {
  description = "Days of automated backups (point-in-time recovery window)."
  type        = number
  default     = 7
}

variable "deletion_protection" {
  description = "Protect the instance against deletion."
  type        = bool
  default     = false
}

variable "skip_final_snapshot" {
  description = "Skip the final snapshot when the instance is destroyed. Only for throwaway environments."
  type        = bool
  default     = false
}

variable "performance_insights_enabled" {
  description = "Enable Performance Insights with the free 7 day retention."
  type        = bool
  default     = true
}

variable "kms_key_arn" {
  description = "Customer managed KMS key for storage and Performance Insights. null: the AWS managed RDS key."
  type        = string
  default     = null
}

variable "apply_immediately" {
  description = "Apply instance modifications immediately instead of in the next maintenance window."
  type        = bool
  default     = false
}

variable "log_retention_days" {
  description = "Retention of the PostgreSQL log group and of the bootstrap task log group, in days."
  type        = number
  default     = 30
}

# -----------------------------------------------------------------------------
# Per-service roles and secrets
# -----------------------------------------------------------------------------

variable "services" {
  description = <<-EOT
    Services that get a schema and two roles in the database, keyed by service
    name (lowercase letters, digits and underscores: it becomes a PostgreSQL
    identifier). `app_connection_limit` is the CONNECTION LIMIT of the
    <service>_app role.
  EOT
  type = map(object({
    app_connection_limit = number
  }))

  validation {
    condition     = alltrue([for name in keys(var.services) : can(regex("^[a-z][a-z0-9_]*$", name))])
    error_message = "Service names must match ^[a-z][a-z0-9_]*$."
  }
}

variable "password_version" {
  description = "Version of the generated role passwords. Increase it to generate new ones, then run the bootstrap task again so PostgreSQL gets them, then redeploy the services."
  type        = number
  default     = 1
}

variable "secret_recovery_window_days" {
  description = "Days a deleted secret can still be recovered. 0 deletes immediately, which lets a throwaway environment be recreated with the same names."
  type        = number
  default     = 7
}

# -----------------------------------------------------------------------------
# Bootstrap task
# -----------------------------------------------------------------------------

variable "bootstrap_sql_path" {
  description = "Path of the SQL script that creates the roles and the schema of ONE service. null: deploy/db/bootstrap-service.sql at the root of this repository."
  type        = string
  default     = null
}

variable "bootstrap_image" {
  description = "Image of the bootstrap task. It only needs psql; the default is the official postgres image from the ECR Public mirror, which has no anonymous pull limit."
  type        = string
  default     = "public.ecr.aws/docker/library/postgres:18"
}

variable "cpu_architecture" {
  description = "CPU architecture of the bootstrap task."
  type        = string
  default     = "ARM64"
}
