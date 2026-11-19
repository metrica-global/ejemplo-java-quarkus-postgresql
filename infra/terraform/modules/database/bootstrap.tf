# One-off task that creates the roles and the schema of every service.
#
# Terraform cannot (and should not) open a connection to a private database,
# so the work is done from inside the VPC: this file only REGISTERS a task
# definition. An operator runs it once per environment with
# `aws ecs run-task`, and again after a password_version change.
#
# The task runs psql from the public postgres image, once per service, over
# the same deploy/db/bootstrap-service.sql the tests and docker compose use.
# The script is embedded in the container command, so there is no image to
# build and nothing to download at run time. Every credential arrives as an
# ECS secret: the RDS-managed master secret to connect, and the per-service
# secrets of secrets.tf to set the role passwords.

data "aws_region" "current" {}

locals {
  bootstrap_name = "${var.name_prefix}-db-bootstrap"

  bootstrap_sql_path = (
    var.bootstrap_sql_path != null
    ? var.bootstrap_sql_path
    : "${path.module}/../../../../deploy/db/bootstrap-service.sql"
  )
  bootstrap_sql_found = fileexists(local.bootstrap_sql_path)

  # Used only when the script is missing, so that `terraform validate` works
  # from a partial checkout. It changes nothing and fails loudly if it is run.
  bootstrap_sql_placeholder = <<-SQL
    -- Placeholder rendered by Terraform: deploy/db/bootstrap-service.sql was not found.
    DO $zc$ BEGIN
      RAISE EXCEPTION 'bootstrap-service.sql was missing when this task definition was rendered. Apply Terraform again from a complete checkout.';
    END $zc$;
  SQL

  bootstrap_sql = local.bootstrap_sql_found ? file(local.bootstrap_sql_path) : local.bootstrap_sql_placeholder

  service_names = sort(keys(var.services))

  # sh -e: the first failing psql stops the task with a non-zero exit code.
  bootstrap_script = join("\n", concat(
    [
      "set -eu",
      "cat > /tmp/bootstrap-service.sql <<'ZC_BOOTSTRAP_SQL_EOF'",
      trimspace(local.bootstrap_sql),
      "ZC_BOOTSTRAP_SQL_EOF",
    ],
    [
      for svc in local.service_names : join(" ", [
        "psql -v ON_ERROR_STOP=1",
        "-v svc=${svc}",
        "-v owner_password=\"$ZC_${upper(svc)}_OWNER_PASSWORD\"",
        "-v app_password=\"$ZC_${upper(svc)}_APP_PASSWORD\"",
        "-v app_conn_limit=${var.services[svc].app_connection_limit}",
        "-f /tmp/bootstrap-service.sql",
      ])
    ],
    ["echo 'Database bootstrap finished.'"],
  ))

  master_secret_arn = aws_db_instance.this.master_user_secret[0].secret_arn
}

resource "aws_cloudwatch_log_group" "bootstrap" {
  name              = "/ecs/${var.name_prefix}/db-bootstrap"
  retention_in_days = var.log_retention_days
}

# -----------------------------------------------------------------------------
# Network
# -----------------------------------------------------------------------------

resource "aws_security_group" "bootstrap" {
  name        = local.bootstrap_name
  description = "Database bootstrap task of ${var.name_prefix}: no inbound traffic"
  vpc_id      = var.vpc_id

  tags = {
    Name = local.bootstrap_name
  }
}

resource "aws_vpc_security_group_egress_rule" "bootstrap_to_db" {
  security_group_id            = aws_security_group.bootstrap.id
  description                  = "PostgreSQL"
  referenced_security_group_id = aws_security_group.this.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

# HTTPS only, but to any address: the image registry, Secrets Manager and
# CloudWatch Logs have no fixed address range. The task runs for seconds, in a
# private subnet, behind the NAT gateway.
#trivy:ignore:AWS-0104
resource "aws_vpc_security_group_egress_rule" "bootstrap_https" {
  security_group_id = aws_security_group.bootstrap.id
  description       = "Image pull, Secrets Manager and CloudWatch Logs through NAT"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# -----------------------------------------------------------------------------
# Execution role: write the log, read the master secret and the role secrets.
# The task has no task role: psql makes no AWS API calls.
# -----------------------------------------------------------------------------

data "aws_iam_policy_document" "bootstrap_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "bootstrap_execution" {
  statement {
    sid       = "WriteBootstrapLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.bootstrap.arn}:*"]
  }

  statement {
    sid     = "ReadDatabaseSecrets"
    actions = ["secretsmanager:GetSecretValue"]
    resources = concat(
      [local.master_secret_arn],
      [for svc in local.service_names : local.owner_password_arns[svc]],
      [for svc in local.service_names : local.app_password_arns[svc]],
    )
  }
}

resource "aws_iam_role" "bootstrap_execution" {
  name               = "${local.bootstrap_name}-execution"
  description        = "Lets ECS start the database bootstrap task of ${var.name_prefix}"
  assume_role_policy = data.aws_iam_policy_document.bootstrap_assume.json
}

resource "aws_iam_role_policy" "bootstrap_execution" {
  name   = "start-task"
  role   = aws_iam_role.bootstrap_execution.id
  policy = data.aws_iam_policy_document.bootstrap_execution.json
}

# -----------------------------------------------------------------------------
# Task definition
# -----------------------------------------------------------------------------

resource "aws_ecs_task_definition" "bootstrap" {
  family                   = local.bootstrap_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.bootstrap_execution.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name      = "bootstrap"
      image     = var.bootstrap_image
      essential = true

      # Bypass the image entrypoint (it would try to start a server).
      entryPoint = ["sh", "-ec"]
      command    = [local.bootstrap_script]

      # libpq reads its connection settings from these variables.
      environment = [
        { name = "PGDATABASE", value = var.db_name },
        { name = "PGHOST", value = aws_db_instance.this.address },
        { name = "PGPORT", value = tostring(aws_db_instance.this.port) },
        { name = "PGSSLMODE", value = "require" },
      ]

      secrets = concat(
        [
          { name = "PGUSER", valueFrom = "${local.master_secret_arn}:username::" },
          { name = "PGPASSWORD", valueFrom = "${local.master_secret_arn}:password::" },
        ],
        flatten([
          for svc in local.service_names : [
            { name = "ZC_${upper(svc)}_OWNER_PASSWORD", valueFrom = local.owner_password_arns[svc] },
            { name = "ZC_${upper(svc)}_APP_PASSWORD", valueFrom = local.app_password_arns[svc] },
          ]
        ]),
      )

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.bootstrap.name
          "awslogs-region"        = data.aws_region.current.region
          "awslogs-stream-prefix" = "bootstrap"
        }
      }
    },
  ])
}
