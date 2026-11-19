output "vpc_id" {
  description = "ID of the VPC."
  value       = module.vpc.vpc_id
}

output "vpc_cidr" {
  description = "CIDR block of the VPC."
  value       = module.vpc.vpc_cidr_block
}

output "availability_zones" {
  description = "Availability zones the subnets were created in."
  value       = local.azs
}

output "public_subnet_ids" {
  description = "IDs of the public subnets (load balancer, NAT gateways)."
  value       = module.vpc.public_subnets
}

output "private_subnet_ids" {
  description = "IDs of the private subnets (tasks, database)."
  value       = module.vpc.private_subnets
}
