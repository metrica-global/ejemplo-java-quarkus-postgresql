# VPC with public and private subnets over N availability zones.
#
# This is the only place in the repository that uses a community module. The
# security groups are NOT created here: each module that needs one owns it
# (ALB, services, database), so the rules sit next to the thing they protect.

data "aws_region" "current" {}

data "aws_availability_zones" "available" {
  state = "available"

  # Regular zones only. Local Zones and Wavelength Zones are opt-in and do not
  # offer NAT gateways or Fargate in the same way.
  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, var.az_count)

  # For a /16: public subnets are /24 carved from the first /20, private
  # subnets are one /20 each starting at the second /20. They never overlap
  # for the 2..6 zones the variable allows.
  public_subnets  = [for i in range(var.az_count) : cidrsubnet(var.vpc_cidr, 8, i)]
  private_subnets = [for i in range(var.az_count) : cidrsubnet(var.vpc_cidr, 4, i + 1)]
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "6.8.0"

  name = "${var.name_prefix}-vpc"
  cidr = var.vpc_cidr
  azs  = local.azs

  public_subnets  = local.public_subnets
  private_subnets = local.private_subnets

  enable_dns_support      = true
  enable_dns_hostnames    = true
  map_public_ip_on_launch = false

  # Tasks live in private subnets and reach AWS APIs, the OIDC issuer and the
  # container registry through NAT. One gateway is enough for dev; production
  # uses one per zone so that losing a zone does not cut egress for the others.
  enable_nat_gateway     = true
  single_nat_gateway     = var.single_nat_gateway
  one_nat_gateway_per_az = !var.single_nat_gateway

  enable_flow_log                                 = var.enable_flow_logs
  create_flow_log_cloudwatch_log_group            = var.enable_flow_logs
  create_flow_log_cloudwatch_iam_role             = var.enable_flow_logs
  flow_log_cloudwatch_log_group_retention_in_days = var.flow_log_retention_days
}

# Gateway endpoints are free. Image layers are served from S3, so this keeps
# the bulk of every image pull off the NAT gateway.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = module.vpc.vpc_id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = module.vpc.private_route_table_ids

  tags = {
    Name = "${var.name_prefix}-s3"
  }
}
