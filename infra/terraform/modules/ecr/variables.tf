variable "repositories" {
  description = "Names of the repositories to create, e.g. zonacards/lotes-service."
  type        = set(string)
}

variable "max_image_count" {
  description = "Number of images kept per repository. Older ones are expired."
  type        = number
  default     = 50
}

variable "untagged_expiry_days" {
  description = "Days after which an untagged image is expired."
  type        = number
  default     = 7
}

variable "kms_key_arn" {
  description = "Customer managed KMS key for the repositories. null: AES-256 encryption with keys managed by ECR."
  type        = string
  default     = null
}

variable "force_delete" {
  description = "Allow terraform destroy to delete a repository that still contains images. Keep false outside throwaway environments."
  type        = bool
  default     = false
}
