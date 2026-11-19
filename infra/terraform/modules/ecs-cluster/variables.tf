variable "name_prefix" {
  description = "Prefix for resource names, e.g. zonacards-dev."
  type        = string
}

variable "vpc_id" {
  description = "VPC the load balancer lives in."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR of the VPC. The load balancer may only open connections to it."
  type        = string
}

variable "subnet_ids" {
  description = "Subnets of the load balancer: public ones for an internet-facing balancer."
  type        = list(string)
}

variable "internal" {
  description = "true: the load balancer is reachable only from inside the VPC."
  type        = bool
  default     = false
}

variable "ingress_cidrs" {
  description = "IPv4 ranges allowed to reach the load balancer."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "certificate_arn" {
  description = "ACM certificate for the HTTPS listener. null: HTTP only (acceptable for a development environment, nothing else)."
  type        = string
  default     = null
}

variable "ssl_policy" {
  description = "TLS policy of the HTTPS listener."
  type        = string
  default     = "ELBSecurityPolicy-TLS13-1-2-2021-06"
}

variable "container_port" {
  description = "Port the tasks listen on. The only port the load balancer may open towards the VPC."
  type        = number
  default     = 8080
}

variable "deletion_protection" {
  description = "Protect the load balancer against accidental deletion."
  type        = bool
  default     = false
}

variable "idle_timeout_seconds" {
  description = "Idle timeout of the load balancer."
  type        = number
  default     = 60
}

variable "container_insights" {
  description = "Container Insights mode: enabled or enhanced. It cannot be turned off because queue-based autoscaling depends on its RunningTaskCount metric."
  type        = string
  default     = "enabled"

  validation {
    condition     = contains(["enabled", "enhanced"], var.container_insights)
    error_message = "container_insights must be \"enabled\" or \"enhanced\"."
  }
}

variable "log_retention_days" {
  description = "Retention of the Container Insights log group, in days."
  type        = number
  default     = 30
}
