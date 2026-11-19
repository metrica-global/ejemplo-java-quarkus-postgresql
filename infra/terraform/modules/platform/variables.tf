variable "environment" {
  description = "Environment name. Part of every resource name (zonacards-<environment>-...) and the value of ZC_ENV."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{1,7}$", var.environment))
    error_message = "environment must be 2 to 8 lowercase letters or digits, starting with a letter."
  }
}

variable "log_retention_days" {
  description = "Retention of every CloudWatch log group of the environment, in days."
  type        = number
  default     = 30
}

variable "cpu_architecture" {
  description = "CPU architecture of every task. Must match the images."
  type        = string
  default     = "ARM64"
}

# -----------------------------------------------------------------------------
# Network and edge
# -----------------------------------------------------------------------------

variable "network" {
  description = "VPC layout. single_nat_gateway = false creates one NAT gateway per availability zone."
  type = object({
    vpc_cidr           = string
    az_count           = optional(number, 2)
    single_nat_gateway = optional(bool, true)
    enable_flow_logs   = optional(bool, false)
  })
}

variable "load_balancer" {
  description = "Public load balancer. It serves HTTPS with certificate_arn. Without a certificate it can only serve plain HTTP, and only if allow_plain_http says so explicitly: acceptable for development, nothing else."
  type = object({
    certificate_arn     = optional(string)
    allow_plain_http    = optional(bool, false)
    ingress_cidrs       = optional(list(string), ["0.0.0.0/0"])
    deletion_protection = optional(bool, false)
  })

  validation {
    condition     = var.load_balancer.certificate_arn != null || var.load_balancer.allow_plain_http
    error_message = "The load balancer needs an ACM certificate (certificate_arn). To accept a plain HTTP listener in a development environment, set allow_plain_http = true."
  }
}

# -----------------------------------------------------------------------------
# Database
# -----------------------------------------------------------------------------

variable "database" {
  description = <<-EOT
    PostgreSQL instance. max_connections is written to the parameter group and
    is the ceiling of the connection budget; reserved_connections is the part
    of it kept for the master user, migrations and monitoring.
  EOT
  type = object({
    instance_class           = string
    max_connections          = number
    reserved_connections     = optional(number, 30)
    allocated_storage_gb     = optional(number, 20)
    max_allocated_storage_gb = optional(number, 100)
    multi_az                 = optional(bool, false)
    backup_retention_days    = optional(number, 7)
    deletion_protection      = optional(bool, false)
    skip_final_snapshot      = optional(bool, false)
    password_version         = optional(number, 1)
  })
}

variable "secret_recovery_window_days" {
  description = "Days a deleted secret can still be recovered. 0 deletes immediately, which lets a throwaway environment be recreated with the same names."
  type        = number
  default     = 7
}

# -----------------------------------------------------------------------------
# Services
#
# Both variables have the same type. A service scales horizontally if, and only
# if, its `autoscaling` attribute is set.
# -----------------------------------------------------------------------------

variable "lotes" {
  description = "Sizing of the lotes service. See var.giftcards for the attributes."
  type = object({
    cpu                  = optional(number, 512)
    memory               = optional(number, 1024)
    desired_count        = optional(number, 2)
    db_pool_max          = optional(number, 10)
    db_connection_limit  = number
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
  })
}

variable "giftcards" {
  description = <<-EOT
    Sizing of the giftcards service.
      cpu / memory          Fargate task size
      desired_count         tasks of a FIXED service (ignored when autoscaling is set)
      db_pool_max           ZC_DB_POOL_MAX: JDBC connections per task
      db_connection_limit   CONNECTION LIMIT of the <service>_app PostgreSQL role
      consumer_concurrency  ZC_CONSUMER_CONCURRENCY: messages handled in parallel per task
      environment           extra plain environment variables
      autoscaling           omit for a fixed service. `queue.name` is a LOGICAL
                            queue name of the messaging topology.
  EOT
  type = object({
    cpu                  = optional(number, 1024)
    memory               = optional(number, 2048)
    desired_count        = optional(number, 2)
    db_pool_max          = optional(number, 10)
    db_connection_limit  = number
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
  })
}

variable "image_tag" {
  description = "Tag of the images used for the FIRST task definition revision of each service. It must exist in ECR before the services are created. Later revisions are published by the deploy pipeline and ignored by Terraform."
  type        = string
}

variable "enable_execute_command" {
  description = "Allow ECS Exec into the service tasks."
  type        = bool
  default     = false
}

# -----------------------------------------------------------------------------
# Things this configuration does NOT provision and only receives
# -----------------------------------------------------------------------------

variable "oidc_issuer" {
  description = "ZC_OIDC_ISSUER: URL of the Keycloak realm that issues the API tokens. Keycloak belongs to the client and is not provisioned here."
  type        = string

  validation {
    condition     = startswith(var.oidc_issuer, "https://")
    error_message = "oidc_issuer must be an https:// URL."
  }
}

variable "oidc_audience" {
  description = "ZC_OIDC_AUDIENCE: audience the API tokens must carry."
  type        = string
  default     = "zonacards-api"
}

variable "otel_exporter_otlp_endpoint" {
  description = "OTEL_EXPORTER_OTLP_ENDPOINT of an existing collector. Empty: the services run with QUARKUS_OTEL_SDK_DISABLED=true."
  type        = string
  default     = ""
}

variable "alarm_topic_arn" {
  description = "Existing SNS topic that receives the queue alarms. null: the alarms exist but notify nobody."
  type        = string
  default     = null
}

# -----------------------------------------------------------------------------
# Account-level resources and CI/CD
# -----------------------------------------------------------------------------

variable "create_account_level_resources" {
  description = <<-EOT
    Whether THIS environment owns the resources that exist once per AWS
    account: the ECR repositories (their names carry no environment), the
    GitHub OIDC provider and the image build role. Set it to true in exactly
    one environment per account: in every environment when each has its own
    account, only in one when several share an account.
  EOT
  type        = bool
}

variable "github_repository" {
  description = "GitHub repository (owner/name) whose workflows may assume the CI/CD roles. null: no GitHub roles are created."
  type        = string
  default     = null
}

variable "state_bucket_arn" {
  description = "ARN of the Terraform state bucket, so the read-only plan role can take the state lock. null: plan with -lock=false."
  type        = string
  default     = null
}
