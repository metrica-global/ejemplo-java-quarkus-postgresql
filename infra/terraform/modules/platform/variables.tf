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
# The application: its modules, the nucleo, and the modules with their own service
# -----------------------------------------------------------------------------

variable "modules" {
  description = <<-EOT
    Database sizing of each module of the application (one entry per module:
    see catalog.tf). It belongs to the module, not to a service, because the
    module keeps its role and its limit wherever it runs.
      db_pool_max          ZC_DB_<MODULE>_POOL_MAX: JDBC connections per task that serves the module
      db_connection_limit  CONNECTION LIMIT of the <module>_app PostgreSQL role. Size it for the
                           day the module is promoted: nucleo tasks plus the tasks of its own
                           service. The database bootstrap task is what applies it.
  EOT
  type = map(object({
    db_pool_max         = optional(number, 10)
    db_connection_limit = number
  }))

  validation {
    condition     = sort(keys(var.modules)) == local.module_names
    error_message = "modules must have exactly one entry per module of the application: ${join(", ", local.module_names)}."
  }
}

variable "nucleo" {
  description = <<-EOT
    Sizing of the nucleo, the service that serves every module that is not in
    promoted_modules.
      cpu / memory          Fargate task size
      desired_count         tasks of a FIXED service (ignored when autoscaling is set)
      consumer_concurrency  ZC_CONSUMER_CONCURRENCY: messages handled in parallel per task
      environment           extra plain environment variables
      autoscaling           omit for a fixed service. `queue.name` is a LOGICAL
                            queue name of the messaging topology.
  EOT
  type = object({
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
  })
  default = {}
}

variable "promoted_modules" {
  description = <<-EOT
    Modules deployed as a service of their own, keyed by module name. Empty
    (the default): the nucleo serves every module.

    For each module listed, Terraform creates its service from the same image,
    its route in the load balancer and, if `autoscaling` is set, its scaling
    policies. The attributes are the ones of var.nucleo, plus:

      transition  set while the module is served TWICE, by the nucleo and by
                  its service, and says which of the two receives its API:

                    "traffic-in-nucleo"   the service runs and takes its share of the
                                          module's queues; the API still goes to the nucleo
                    omitted               promoted: the API goes to the service and the
                                          nucleo stops serving the module on its next deployment
                    "traffic-in-service"  the nucleo serves the module again on its next
                                          deployment; the API still goes to the service

    To promote:  add the module with transition = "traffic-in-nucleo", apply and
                 wait until the new service is stable; remove `transition`,
                 apply; redeploy the current release.
    To demote:   set transition = "traffic-in-service", apply; redeploy the
                 current release; set transition = "traffic-in-nucleo", apply;
                 remove the module from the map, apply.
    Every state is safe to stay in. The whole procedure, and what each apply
    changes, is in infra/terraform/README.md.
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

  validation {
    condition     = alltrue([for name in keys(var.promoted_modules) : contains(local.module_names, name)])
    error_message = "promoted_modules may only name modules of the application: ${join(", ", local.module_names)}."
  }

  validation {
    condition = alltrue([
      for cfg in values(var.promoted_modules) : cfg.transition == null || contains(["traffic-in-nucleo", "traffic-in-service"], coalesce(cfg.transition, "traffic-in-nucleo"))
    ])
    error_message = "transition must be \"traffic-in-nucleo\", \"traffic-in-service\" or omitted."
  }
}

variable "image_tag" {
  description = <<-EOT
    Tag of the image the environment is CREATED with. It must exist in ECR
    before the first apply. It is only the initial value of the release
    parameter (deploy.tf): after the first deployment the pipeline records
    there what is deployed, and this variable is not read again.
  EOT
  type        = string
}

variable "enable_execute_command" {
  description = "Allow ECS Exec into the tasks of every service."
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
  description = "OTEL_EXPORTER_OTLP_ENDPOINT of an existing collector. Empty: every deployment runs with QUARKUS_OTEL_SDK_DISABLED=true."
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
    account: the ECR repository (its name carries no environment), the
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
