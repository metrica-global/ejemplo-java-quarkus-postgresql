variable "name_prefix" {
  description = "Prefix for resource names, e.g. zonacards-dev. Physical names are <name_prefix>-<logical name>."
  type        = string
}

variable "topics" {
  description = "SNS topics keyed by logical name. Use modules/messaging/topology as the source."
  type        = map(object({}))
}

variable "queues" {
  description = <<-EOT
    SQS queues keyed by logical name. Every queue gets a dead-letter queue named
    <name>-dlq. `subscribe_to` subscribes the queue to one of `topics`, filtered
    on the `type` message attribute; leave it out for a queue the owning service
    writes to directly.
  EOT
  type = map(object({
    visibility_timeout_seconds       = optional(number, 60)
    message_retention_seconds        = optional(number, 345600) # 4 days
    max_receive_count                = optional(number, 5)
    oldest_message_age_alarm_seconds = optional(number, 600)
    subscribe_to = optional(object({
      topic       = string
      event_types = list(string)
    }))
  }))

  validation {
    condition = alltrue([
      for q in values(var.queues) : q.subscribe_to == null ? true : contains(keys(var.topics), q.subscribe_to.topic)
    ])
    error_message = "Every subscribe_to.topic must be a key of var.topics."
  }

  validation {
    condition = alltrue([
      for q in values(var.queues) : q.subscribe_to == null ? true : length(q.subscribe_to.event_types) > 0
    ])
    error_message = "subscribe_to.event_types must list at least one event type: an inbox never receives everything by accident."
  }
}

variable "dlq_message_retention_seconds" {
  description = "How long a dead-letter queue keeps a message. 14 days is the SQS maximum."
  type        = number
  default     = 1209600
}

variable "receive_wait_time_seconds" {
  description = "Long polling wait of every queue."
  type        = number
  default     = 20
}

variable "enable_encryption" {
  description = "Encrypt topics and queues with a customer managed KMS key. Disable only against an emulator without KMS."
  type        = bool
  default     = true
}

variable "kms_deletion_window_days" {
  description = "Waiting period before the KMS key is deleted after a destroy."
  type        = number
  default     = 30
}

variable "enable_alarms" {
  description = "Create the CloudWatch alarms (dead-letter queue not empty, age of the oldest message). Disable only against an emulator without CloudWatch."
  type        = bool
  default     = true
}

variable "alarm_topic_arn" {
  description = "SNS topic that receives alarm and recovery notifications. null: the alarms exist but notify nobody."
  type        = string
  default     = null
}
