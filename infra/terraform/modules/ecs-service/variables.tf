# -----------------------------------------------------------------------------
# Identity and placement
# -----------------------------------------------------------------------------

variable "name_prefix" {
  description = "Prefix for resource names, e.g. zonacards-dev."
  type        = string
}

variable "service_name" {
  description = "Short name of the service, e.g. lotes. Resources are named <name_prefix>-<service_name>."
  type        = string
}

variable "cluster_arn" {
  description = "ARN of the ECS cluster."
  type        = string
}

variable "cluster_name" {
  description = "Name of the ECS cluster (autoscaling resource id and CloudWatch dimension)."
  type        = string
}

variable "vpc_id" {
  description = "VPC of the service."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets the tasks run in."
  type        = list(string)
}

# -----------------------------------------------------------------------------
# Load balancer
# -----------------------------------------------------------------------------

variable "alb_security_group_id" {
  description = "Security group of the load balancer: the only source allowed to reach the container port."
  type        = string
}

variable "listener_arn" {
  description = "Listener the path rule is attached to."
  type        = string
}

variable "listener_rule_priority" {
  description = "Priority of the path rule. Must be unique per listener."
  type        = number
}

variable "path_patterns" {
  description = "Path patterns routed to this service, e.g. [\"/api/v1/lotes*\"]."
  type        = list(string)
}

variable "health_check_path" {
  description = "Path used by BOTH the target group health check and the container health check. Must be the liveness endpoint, see the comment in main.tf."
  type        = string
  default     = "/q/health/live"
}

variable "deregistration_delay_seconds" {
  description = "Time the load balancer keeps draining in-flight requests of a task that is going away."
  type        = number
  default     = 30
}

# -----------------------------------------------------------------------------
# Container
# -----------------------------------------------------------------------------

variable "image" {
  description = "Image of the first task definition revision, <repository>:<tag>. Later revisions are published by the deploy pipeline."
  type        = string
}

variable "ecr_repository_arns" {
  description = "Repositories the execution roles may pull from."
  type        = list(string)
}

variable "container_port" {
  description = "Port the container listens on."
  type        = number
  default     = 8080
}

variable "cpu" {
  description = "Fargate CPU units of the task (1024 = 1 vCPU)."
  type        = number
}

variable "memory" {
  description = "Fargate memory of the task, in MiB."
  type        = number
}

variable "cpu_architecture" {
  description = "CPU architecture of the tasks. Must match the image."
  type        = string
  default     = "ARM64"

  validation {
    condition     = contains(["ARM64", "X86_64"], var.cpu_architecture)
    error_message = "cpu_architecture must be ARM64 or X86_64."
  }
}

variable "environment" {
  description = "Plain environment variables of the service container. Never put a secret here."
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Secrets of the service container: environment variable name => Secrets Manager secret ARN. ECS injects the value at start; it never appears in the task definition."
  type        = map(string)
  default     = {}
}

variable "migrate" {
  description = <<-EOT
    Definition of the one-off migration task (<service>-migrate): same image,
    its own environment and secrets, its own execution role. The deploy
    pipeline runs it with `aws ecs run-task` before updating the service.
    null: no migration task definition.
  EOT
  type = object({
    environment = map(string)
    secrets     = map(string)
  })
  default = null
}

variable "container_health_check_command" {
  description = "Override of the container health check command. null: curl against health_check_path, which requires curl in the image."
  type        = list(string)
  default     = null
}

variable "stop_timeout_seconds" {
  description = "Time between SIGTERM and SIGKILL. The service uses it to stop polling, finish in-flight messages and flush the outbox. Fargate allows at most 120."
  type        = number
  default     = 90

  validation {
    condition     = var.stop_timeout_seconds >= 2 && var.stop_timeout_seconds <= 120
    error_message = "stop_timeout_seconds must be between 2 and 120 on Fargate."
  }
}

variable "log_retention_days" {
  description = "Retention of the service log group, in days."
  type        = number
  default     = 30
}

# -----------------------------------------------------------------------------
# Service and scaling
# -----------------------------------------------------------------------------

variable "desired_count" {
  description = "Number of tasks of a FIXED service. Ignored when autoscaling is set: the service then starts at autoscaling.min and Application Auto Scaling owns the count."
  type        = number
  default     = 2

  validation {
    condition     = var.desired_count >= 1
    error_message = "desired_count must be at least 1."
  }
}

variable "autoscaling" {
  description = <<-EOT
    Horizontal autoscaling. null (the default) means a fixed service: no
    scalable target and no scaling policy are created at all.

    When set, the service scales between min and max with target tracking on
    average CPU and, if `queue` is given, on backlog per task: visible
    messages in that SQS queue divided by the number of running tasks.
    `queue.name` is the physical queue name (the QueueName CloudWatch
    dimension). `backlog_per_task_target` is the number of waiting messages one
    task is allowed to have: acceptable wait / average processing time.
  EOT
  type = object({
    min        = number
    max        = number
    cpu_target = optional(number, 60)
    queue = optional(object({
      name                    = string
      backlog_per_task_target = number
    }))
    scale_out_cooldown = optional(number, 60)
    scale_in_cooldown  = optional(number, 300)
  })
  default = null

  validation {
    # min >= 1 also keeps the backlog-per-task division away from zero.
    condition     = var.autoscaling == null ? true : var.autoscaling.min >= 1 && var.autoscaling.max >= var.autoscaling.min
    error_message = "autoscaling requires 1 <= min <= max."
  }

  validation {
    condition     = var.autoscaling == null ? true : var.autoscaling.cpu_target > 0 && var.autoscaling.cpu_target <= 100
    error_message = "autoscaling.cpu_target is a percentage between 1 and 100."
  }

  validation {
    condition     = try(var.autoscaling.queue.backlog_per_task_target, 1) > 0
    error_message = "autoscaling.queue.backlog_per_task_target must be greater than 0."
  }
}

variable "deployment_minimum_healthy_percent" {
  description = "Lower bound of healthy tasks during a deployment, as a percentage of the desired count."
  type        = number
  default     = 100
}

variable "deployment_maximum_percent" {
  description = "Upper bound of running tasks during a deployment, as a percentage of the desired count."
  type        = number
  default     = 200
}

variable "health_check_grace_period_seconds" {
  description = "Time after a task starts during which failed load balancer health checks are ignored."
  type        = number
  default     = 60
}

variable "enable_execute_command" {
  description = "Allow ECS Exec into the running tasks. Off by default; when on, the task role gets the SSM channel permissions it needs."
  type        = bool
  default     = false
}

# -----------------------------------------------------------------------------
# Permissions of the task role (what the application code may do)
# -----------------------------------------------------------------------------

variable "publish_topic_arns" {
  description = "SNS topics the service may publish to."
  type        = list(string)
  default     = []
}

variable "consume_queue_arns" {
  description = "SQS queues the service may consume: receive, delete, change visibility, get attributes."
  type        = list(string)
  default     = []
}

variable "send_queue_arns" {
  description = "SQS queues the service may send messages to directly."
  type        = list(string)
  default     = []
}

variable "kms_key_arns" {
  description = "KMS keys that encrypt those topics and queues. Empty when messaging is not encrypted with a customer managed key."
  type        = list(string)
  default     = []
}
