# Identical in every environment. Values live in terraform.tfvars.

variable "environment" {
  description = "Environment name: dev, qa or prod."
  type        = string

  validation {
    condition     = contains(["dev", "qa", "prod"], var.environment)
    error_message = "environment must be dev, qa or prod."
  }
}

variable "region" {
  description = "AWS region."
  type        = string
  default     = "us-east-1"
}

variable "log_retention_days" {
  description = "Retention of every CloudWatch log group, in days."
  type        = number
}

variable "cpu_architecture" {
  description = "CPU architecture of the tasks. Must match the images."
  type        = string
  default     = "ARM64"
}

# -----------------------------------------------------------------------------
# Network and load balancer
# -----------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "CIDR block of the VPC (a /16)."
  type        = string
}

variable "az_count" {
  description = "Number of availability zones."
  type        = number
}

variable "single_nat_gateway" {
  description = "true: one NAT gateway for the whole VPC. false: one per availability zone."
  type        = bool
}

variable "enable_flow_logs" {
  description = "Send VPC flow logs to CloudWatch Logs."
  type        = bool
}

variable "certificate_arn" {
  description = "ACM certificate of the load balancer. Required unless alb_allow_plain_http is true."
  type        = string
  default     = null
}

variable "alb_allow_plain_http" {
  description = "Accept a load balancer without certificate, listening on plain HTTP. Development only."
  type        = bool
  default     = false
}

variable "alb_ingress_cidrs" {
  description = "IPv4 ranges allowed to reach the load balancer."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "alb_deletion_protection" {
  description = "Protect the load balancer against deletion."
  type        = bool
}

# -----------------------------------------------------------------------------
# Database
# -----------------------------------------------------------------------------

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
}

variable "db_max_connections" {
  description = "max_connections of the instance. It is written to the parameter group and is the ceiling of the connection budget. Keep it at or below what the instance class supports."
  type        = number
}

variable "db_reserved_connections" {
  description = "Connections kept out of the services' budget: master user, migrations, monitoring."
  type        = number
  default     = 30
}

variable "db_multi_az" {
  description = "Run a standby in another availability zone."
  type        = bool
}

variable "db_backup_retention_days" {
  description = "Days of automated backups."
  type        = number
}

variable "db_deletion_protection" {
  description = "Protect the database against deletion."
  type        = bool
}

variable "db_skip_final_snapshot" {
  description = "Skip the final snapshot on destroy. Only for throwaway environments."
  type        = bool
}

variable "secret_recovery_window_days" {
  description = "Days a deleted secret can still be recovered. 0 deletes immediately."
  type        = number
}

# -----------------------------------------------------------------------------
# The application
#
# One image with every module. By default one service, the nucleo, serves them
# all; promoted_modules lists the modules that get a service of their own.
# -----------------------------------------------------------------------------

variable "nucleo_tasks" {
  description = "Number of tasks of the nucleo, the service that serves every module that has not been promoted."
  type        = number
}

variable "nucleo_cpu" {
  description = "Fargate CPU units of a nucleo task."
  type        = number
  default     = 1024
}

variable "nucleo_memory" {
  description = "Fargate memory of a nucleo task, in MiB."
  type        = number
  default     = 2048
}

variable "lotes_db_pool_max" {
  description = "ZC_DB_LOTES_POOL_MAX: JDBC connections of the lotes module per task that serves it."
  type        = number
  default     = 10
}

variable "lotes_db_connection_limit" {
  description = "CONNECTION LIMIT of the lotes_app PostgreSQL role. Must cover the peak tasks of every service that can serve the module x pool."
  type        = number
}

variable "giftcards_db_pool_max" {
  description = "ZC_DB_GIFTCARDS_POOL_MAX: JDBC connections of the giftcards module per task that serves it."
  type        = number
  default     = 10
}

variable "giftcards_db_connection_limit" {
  description = "CONNECTION LIMIT of the giftcards_app PostgreSQL role. Sized for the day the module is promoted: nucleo tasks plus the tasks of its own service, x pool."
  type        = number
}

variable "promoted_modules" {
  description = <<-EOT
    Modules deployed as a service of their own, keyed by module name. Empty by
    default: the nucleo serves every module. See promocion-giftcards.tfvars
    for a complete example and modules/platform/variables.tf for the meaning
    of every attribute, `transition` in particular.
  EOT
  type = map(object({
    cpu                  = optional(number, 1024)
    memory               = optional(number, 2048)
    desired_count        = optional(number, 2)
    consumer_concurrency = optional(number, 8)
    environment          = optional(map(string), {})
    autoscaling = optional(object({
      min        = number
      max        = number
      cpu_target = optional(number, 60)
      queue = optional(object({
        name                    = string
        backlog_per_task_target = number
      }))
      scale_out_cooldown = optional(number, 60)
      scale_in_cooldown  = optional(number, 300)
    }))
    transition = optional(string)
  }))
  default = {}
}

# -----------------------------------------------------------------------------
# Received, not provisioned
# -----------------------------------------------------------------------------

variable "image_tag" {
  description = "Tag of the image the environment is created with. Must exist in ECR before the first apply; after the first deployment the pipeline records the deployed tag and this value is not read again."
  type        = string
}

variable "oidc_issuer" {
  description = "URL of the Keycloak realm that issues the API tokens. Keycloak is not provisioned here."
  type        = string
}

variable "oidc_audience" {
  description = "Audience the API tokens must carry."
  type        = string
  default     = "zonacards-api"
}

variable "otel_exporter_otlp_endpoint" {
  description = "OTLP endpoint of an existing collector. Empty: the OpenTelemetry SDK is disabled in the services."
  type        = string
  default     = ""
}

variable "alarm_topic_arn" {
  description = "Existing SNS topic for the queue alarms. null: alarms without notification."
  type        = string
  default     = null
}

# -----------------------------------------------------------------------------
# Account-level resources and CI/CD
# -----------------------------------------------------------------------------

variable "create_account_level_resources" {
  description = "true in exactly one environment per AWS account: that one owns the ECR repositories, the GitHub OIDC provider and the image build role."
  type        = bool
}

variable "github_repository" {
  description = "GitHub repository (owner/name) whose workflows may assume the CI/CD roles. null: no GitHub roles."
  type        = string
  default     = null
}

variable "state_bucket_arn" {
  description = "ARN of the Terraform state bucket, so the read-only plan role can take the state lock. null: plan with -lock=false."
  type        = string
  default     = null
}

variable "enable_execute_command" {
  description = "Allow ECS Exec into the service tasks."
  type        = bool
  default     = false
}
