output "oidc_provider_arn" {
  description = "ARN of the GitHub OIDC identity provider (created here or derived)."
  value       = local.oidc_provider_arn
}

output "build_role_arn" {
  description = "ARN of the role that pushes images, or null when this configuration does not create it."
  value       = one(aws_iam_role.build[*].arn)
}

output "deploy_role_arn" {
  description = "ARN of the role that deploys to this environment."
  value       = aws_iam_role.deploy.arn
}

output "plan_role_arn" {
  description = "ARN of the read-only role for terraform plan."
  value       = aws_iam_role.plan.arn
}
