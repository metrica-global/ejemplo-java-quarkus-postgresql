# The Zonacards environment, composed once.
#
# envs/dev, envs/qa and envs/prod are a single call to this module with
# different values. The wiring that must be identical everywhere (which queue
# feeds which service, which role may publish where, which secret reaches
# which container) therefore exists in one place and cannot drift between
# environments.
#
#   main.tf      network, cluster and load balancer, registry, messaging, database
#   services.tf  the two services, side by side, and the connection budget
#   secrets.tf   application secrets that are not database passwords
#   cicd.tf      GitHub Actions roles

data "aws_region" "current" {}
data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  project     = "zonacards"
  name_prefix = "${local.project}-${var.environment}"

  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.region

  # Repository names are part of the contract with the build pipeline and
  # carry no environment: one image is built once and promoted.
  repository_names = {
    lotes     = "${local.project}/lotes-service"
    giftcards = "${local.project}/giftcards-service"
  }

  # Derived, not read from module.ecr, so that an environment which does not
  # own the repositories (create_account_level_resources = false) needs no
  # lookup of resources that may not exist yet.
  ecr_registry    = "${local.account_id}.dkr.ecr.${local.region}.${data.aws_partition.current.dns_suffix}"
  repository_urls = { for svc, name in local.repository_names : svc => "${local.ecr_registry}/${name}" }
  repository_arns = { for svc, name in local.repository_names : svc => "arn:${local.partition}:ecr:${local.region}:${local.account_id}:repository/${name}" }
}

# -----------------------------------------------------------------------------
# Network
# -----------------------------------------------------------------------------

module "network" {
  source = "../network"

  name_prefix        = local.name_prefix
  vpc_cidr           = var.network.vpc_cidr
  az_count           = var.network.az_count
  single_nat_gateway = var.network.single_nat_gateway
  enable_flow_logs   = var.network.enable_flow_logs

  flow_log_retention_days = var.log_retention_days
}

# -----------------------------------------------------------------------------
# Cluster and load balancer
# -----------------------------------------------------------------------------

module "cluster" {
  source = "../ecs-cluster"

  name_prefix = local.name_prefix
  vpc_id      = module.network.vpc_id
  vpc_cidr    = module.network.vpc_cidr
  subnet_ids  = module.network.public_subnet_ids

  certificate_arn     = var.load_balancer.certificate_arn
  ingress_cidrs       = var.load_balancer.ingress_cidrs
  deletion_protection = var.load_balancer.deletion_protection

  log_retention_days = var.log_retention_days
}

# -----------------------------------------------------------------------------
# Image registry (once per account)
# -----------------------------------------------------------------------------

module "ecr" {
  count  = var.create_account_level_resources ? 1 : 0
  source = "../ecr"

  repositories = toset(values(local.repository_names))
}

# -----------------------------------------------------------------------------
# Messaging: the topology is declared in modules/messaging/topology and is the
# same one the local emulator uses.
# -----------------------------------------------------------------------------

module "topology" {
  source = "../messaging/topology"
}

module "messaging" {
  source = "../messaging"

  name_prefix = local.name_prefix
  topics      = module.topology.topics
  queues      = module.topology.queues

  alarm_topic_arn = var.alarm_topic_arn
}

# -----------------------------------------------------------------------------
# Database
# -----------------------------------------------------------------------------

module "database" {
  source = "../database"

  name_prefix = local.name_prefix
  vpc_id      = module.network.vpc_id
  subnet_ids  = module.network.private_subnet_ids

  # The only clients: the tasks of the two services.
  allowed_security_group_ids = {
    lotes     = module.lotes.security_group_id
    giftcards = module.giftcards.security_group_id
  }

  instance_class           = var.database.instance_class
  max_connections          = var.database.max_connections
  allocated_storage_gb     = var.database.allocated_storage_gb
  max_allocated_storage_gb = var.database.max_allocated_storage_gb
  multi_az                 = var.database.multi_az
  backup_retention_days    = var.database.backup_retention_days
  deletion_protection      = var.database.deletion_protection
  skip_final_snapshot      = var.database.skip_final_snapshot

  # One schema and two roles per service. The connection limit of each
  # application role is the number checked by the budget in services.tf.
  services = {
    lotes     = { app_connection_limit = var.lotes.db_connection_limit }
    giftcards = { app_connection_limit = var.giftcards.db_connection_limit }
  }

  password_version            = var.database.password_version
  secret_recovery_window_days = var.secret_recovery_window_days

  cpu_architecture   = var.cpu_architecture
  log_retention_days = var.log_retention_days
}
