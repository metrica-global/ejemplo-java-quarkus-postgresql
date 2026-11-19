variable "secrets" {
  description = <<-EOT
    Secrets to create, keyed by a static label.
      name         name of the secret in Secrets Manager
      description  what it is and who reads it
      format       "password" (40 letters and digits) or
                   "base64-256-bit-key" (32 random bytes, base64 encoded)
  EOT
  type = map(object({
    name        = string
    description = string
    format      = optional(string, "password")
  }))

  validation {
    condition     = alltrue([for s in values(var.secrets) : contains(["password", "base64-256-bit-key"], s.format)])
    error_message = "format must be \"password\" or \"base64-256-bit-key\"."
  }
}

variable "value_version" {
  description = "Version of the generated values. Increasing it generates and stores NEW values for every secret of this module call. Whatever depends on the old values must be updated too."
  type        = number
  default     = 1
}

variable "recovery_window_days" {
  description = "Days a deleted secret can still be recovered. 0 deletes immediately, which lets a throwaway environment be recreated with the same names."
  type        = number
  default     = 7
}
