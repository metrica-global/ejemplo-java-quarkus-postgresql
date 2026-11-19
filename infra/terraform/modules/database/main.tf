# One PostgreSQL instance, one schema per owner of data.
#
# This module calls that owner a "service": whoever gets a schema and its two
# roles. In this application it is a MODULE of the monolith (lotes,
# giftcards), wherever the module runs: var.services is keyed by module name.
#
# Isolation is done inside PostgreSQL (roles and schemas, see bootstrap.tf),
# not with separate instances. This file owns the instance and its network
# boundary.

locals {
  identifier = "${var.name_prefix}-postgres"
}

resource "aws_db_subnet_group" "this" {
  name        = "${var.name_prefix}-db"
  description = "Private subnets of ${local.identifier}"
  subnet_ids  = var.subnet_ids
}

# -----------------------------------------------------------------------------
# Network: PostgreSQL is reachable from the listed security groups and from
# the bootstrap task. No CIDR rule, no egress rule.
# -----------------------------------------------------------------------------

resource "aws_security_group" "this" {
  name        = "${var.name_prefix}-db"
  description = "PostgreSQL of ${var.name_prefix}: only from the service tasks"
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.name_prefix}-db"
  }
}

resource "aws_vpc_security_group_ingress_rule" "clients" {
  for_each = var.allowed_security_group_ids

  security_group_id            = aws_security_group.this.id
  description                  = "PostgreSQL from ${each.key}"
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_ingress_rule" "bootstrap" {
  security_group_id            = aws_security_group.this.id
  description                  = "PostgreSQL from the bootstrap task"
  referenced_security_group_id = aws_security_group.bootstrap.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

# -----------------------------------------------------------------------------
# Instance
# -----------------------------------------------------------------------------

resource "aws_db_parameter_group" "this" {
  name_prefix = "${var.name_prefix}-${var.parameter_group_family}-"
  family      = var.parameter_group_family
  description = "Parameters of ${local.identifier}"

  # Reject any connection that is not TLS. The services connect with
  # sslmode=require.
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  # Static parameter: it takes effect when the instance is created or rebooted.
  parameter {
    name         = "max_connections"
    value        = tostring(var.max_connections)
    apply_method = "pending-reboot"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# RDS would create this group on first use, without retention.
resource "aws_cloudwatch_log_group" "postgresql" {
  name              = "/aws/rds/instance/${local.identifier}/postgresql"
  retention_in_days = var.log_retention_days
}

resource "aws_db_instance" "this" {
  identifier = local.identifier

  engine                     = "postgres"
  engine_version             = var.engine_version
  auto_minor_version_upgrade = true
  instance_class             = var.instance_class
  parameter_group_name       = aws_db_parameter_group.this.name

  db_name  = var.db_name
  port     = 5432
  username = var.master_username
  # RDS generates the master password, stores it in Secrets Manager and
  # rotates it. Terraform never sees it.
  manage_master_user_password = true

  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = var.max_allocated_storage_gb
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = var.kms_key_arn

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.this.id]
  publicly_accessible    = false
  multi_az               = var.multi_az

  backup_retention_period   = var.backup_retention_days
  copy_tags_to_snapshot     = true
  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : "${local.identifier}-final"

  performance_insights_enabled          = var.performance_insights_enabled
  performance_insights_retention_period = var.performance_insights_enabled ? 7 : null
  performance_insights_kms_key_id       = var.performance_insights_enabled ? var.kms_key_arn : null

  enabled_cloudwatch_logs_exports = ["postgresql"]
  apply_immediately               = var.apply_immediately

  depends_on = [aws_cloudwatch_log_group.postgresql]
}
