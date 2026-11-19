variable "endpoint" {
  description = "URL of the local AWS emulator. SNS, SQS and STS calls go here and nowhere else."
  type        = string
  default     = "http://localhost:4566"

  validation {
    # A guard against pointing this root at something that is not an emulator.
    condition     = !can(regex("amazonaws\\.com", var.endpoint))
    error_message = "endpoint must be a local emulator, not an AWS endpoint."
  }
}

variable "region" {
  description = "Region name the emulator reports in ARNs and URLs."
  type        = string
  default     = "us-east-1"
}
