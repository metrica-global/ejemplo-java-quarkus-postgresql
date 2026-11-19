variable "name_prefix" {
  description = "Prefix for role names, e.g. zonacards-dev."
  type        = string
}

variable "github_repository" {
  description = "Repository allowed to assume the roles, as owner/name."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must look like owner/name, without wildcards."
  }
}

variable "create_oidc_provider" {
  description = "Create the GitHub OIDC identity provider. There can be only one per AWS account: set it to true in exactly one configuration per account."
  type        = bool
}

# -----------------------------------------------------------------------------
# Build role (push images)
# -----------------------------------------------------------------------------

variable "create_build_role" {
  description = "Create the role that builds and pushes images."
  type        = bool
  default     = true
}

variable "build_subjects" {
  description = "What may assume the build role, as the part of the OIDC subject after repo:<owner>/<name>:. Default: workflows running on the main branch."
  type        = list(string)
  default     = ["ref:refs/heads/main"]
}

variable "ecr_repository_arns" {
  description = "Repositories the build role may push to and the deploy role may read."
  type        = list(string)
}

# -----------------------------------------------------------------------------
# Deploy role (one per environment)
# -----------------------------------------------------------------------------

variable "github_environment" {
  description = "GitHub environment whose jobs may assume the deploy role. Protection rules (required reviewers) are configured on that environment in GitHub."
  type        = string
}

variable "deploy" {
  description = <<-EOT
    What the deploy role may touch:
      cluster_arn / cluster_name  the ECS cluster
      service_arns                services it may update
      run_task_families           task definition families it may run (the migration tasks)
      passable_role_arns          task and execution roles it may hand to ECS
      log_group_arns              log groups it may read to show task output
  EOT
  type = object({
    cluster_arn        = string
    cluster_name       = string
    service_arns       = list(string)
    run_task_families  = list(string)
    passable_role_arns = list(string)
    log_group_arns     = list(string)
  })
}

# -----------------------------------------------------------------------------
# Plan role (read-only)
# -----------------------------------------------------------------------------

variable "plan_subjects" {
  description = "What may assume the read-only plan role, as the part of the OIDC subject after repo:<owner>/<name>:. Default: pull request workflows."
  type        = list(string)
  default     = ["pull_request"]
}

variable "state_bucket_arn" {
  description = "ARN of the Terraform state bucket. When set, the plan role may create and delete the S3 lock file. null: run the plan with -lock=false."
  type        = string
  default     = null
}

variable "plan_secret_arns" {
  description = "Secret ARNs or ARN patterns the plan role may read. Terraform refreshes aws_secretsmanager_secret_version with GetSecretValue, which the ReadOnlyAccess policy does not include."
  type        = list(string)
  default     = []
}
