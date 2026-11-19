# The Zonacards environment, composed once.
#
# envs/dev, envs/qa and envs/prod are a single call to this module with
# different values. The wiring that must be identical everywhere (which queue
# feeds which module, which role may publish where, which secret reaches
# which container) therefore exists in one place and cannot drift between
# environments.
#
# The application is a modular monolith: ONE image that contains every module.
# This module deploys it as one service, the nucleo, plus one service for each
# module listed in var.promoted_modules. Moving a module between the two is a
# change of that variable; no file of this module changes.
#
#   main.tf      network, cluster and load balancer, registry, messaging, database
#   catalog.tf   the modules of the application and where each one is
#   services.tf  the nucleo, one service per promoted module, the connection budget
#   routing.tf   one listener rule per module
#   deploy.tf    what the deploy pipeline reads: services to deploy and the release
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

  # One application, one image, one repository. The name is part of the
  # contract with the build pipeline and carries no environment: the image is
  # built once and promoted from one environment to the next.
  repository_name = "${local.project}/app"

  # Derived, not read from module.ecr, so that an environment which does not
  # own the repository (create_account_level_resources = false) needs no
  # lookup of a resource that may not exist yet.
  ecr_registry   = "${local.account_id}.dkr.ecr.${local.region}.${data.aws_partition.current.dns_suffix}"
  repository_url = "${local.ecr_registry}/${local.repository_name}"
  repository_arn = "arn:${local.partition}:ecr:${local.region}:${local.account_id}:repository/${local.repository_name}"
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

  repositories = toset([local.repository_name])
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

  # The only clients: the tasks of the nucleo (and the migration task, which
  # runs with its security group) and of every promoted module's service.
  allowed_security_group_ids = merge(
    { nucleo = module.nucleo.security_group_id },
    { for name, service in module.promoted : name => service.security_group_id },
  )

  instance_class           = var.database.instance_class
  max_connections          = var.database.max_connections
  allocated_storage_gb     = var.database.allocated_storage_gb
  max_allocated_storage_gb = var.database.max_allocated_storage_gb
  multi_az                 = var.database.multi_az
  backup_retention_days    = var.database.backup_retention_days
  deletion_protection      = var.database.deletion_protection
  skip_final_snapshot      = var.database.skip_final_snapshot

  # One schema and two roles per MODULE, wherever the module runs: sharing a
  # process does not give a module access to another module's tables. The
  # connection limit of each application role is the number checked by the
  # budget in services.tf.
  services = { for name, cfg in var.modules : name => { app_connection_limit = cfg.db_connection_limit } }

  password_version            = var.database.password_version
  secret_recovery_window_days = var.secret_recovery_window_days

  cpu_architecture   = var.cpu_architecture
  log_retention_days = var.log_retention_days
}
