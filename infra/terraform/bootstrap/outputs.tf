output "state_bucket_name" {
  description = "Name of the state bucket: the `bucket` value of each environment's backend.hcl."
  value       = aws_s3_bucket.state.bucket
}

output "state_bucket_arn" {
  description = "ARN of the state bucket: the `state_bucket_arn` variable of each environment."
  value       = aws_s3_bucket.state.arn
}

output "state_bucket_region" {
  description = "Region of the state bucket: the `region` value of each environment's backend.hcl."
  value       = var.region
}
