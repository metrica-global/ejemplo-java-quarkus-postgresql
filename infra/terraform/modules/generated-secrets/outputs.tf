output "secret_arns" {
  description = "Secret ARNs keyed by the labels of var.secrets. Taken from the secret VERSIONS, so whatever uses an ARN is created only after the secret has a value."
  value       = { for key, version in aws_secretsmanager_secret_version.this : key => version.secret_arn }
}
