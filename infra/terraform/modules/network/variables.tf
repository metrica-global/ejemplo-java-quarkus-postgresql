variable "name_prefix" {
  description = "Prefix for resource names, e.g. zonacards-dev."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC. Must be a /16: the subnet layout is derived from it."
  type        = string

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0)) && endswith(var.vpc_cidr, "/16")
    error_message = "vpc_cidr must be a valid /16 CIDR block, for example 10.20.0.0/16."
  }
}

variable "az_count" {
  description = "Number of availability zones to spread the subnets over."
  type        = number
  default     = 2

  validation {
    condition     = var.az_count >= 2 && var.az_count <= 6 && floor(var.az_count) == var.az_count
    error_message = "az_count must be an integer between 2 and 6 (an ALB and a Multi-AZ database need at least two zones)."
  }
}

variable "single_nat_gateway" {
  description = "true: one NAT gateway shared by every private subnet (cheaper, dev). false: one NAT gateway per availability zone."
  type        = bool
  default     = true
}

variable "enable_flow_logs" {
  description = "Send VPC flow logs to CloudWatch Logs."
  type        = bool
  default     = false
}

variable "flow_log_retention_days" {
  description = "Retention of the flow log group, in days."
  type        = number
  default     = 30
}
