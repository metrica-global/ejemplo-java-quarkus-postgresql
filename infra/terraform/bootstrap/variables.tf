variable "region" {
  description = "AWS region of the state bucket."
  type        = string
  default     = "us-east-1"
}

variable "state_bucket_name" {
  description = "Name of the state bucket. null: zonacards-tfstate-<account id>-<region>."
  type        = string
  default     = null
}

variable "kms_key_arn" {
  description = "Customer managed KMS key for the state bucket. null: the AWS managed S3 key."
  type        = string
  default     = null
}

variable "noncurrent_version_retention_days" {
  description = "Days an overwritten state version is kept (the 20 most recent versions are always kept)."
  type        = number
  default     = 90
}
