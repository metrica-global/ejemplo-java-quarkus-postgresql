# One Fargate service behind the shared load balancer: one DEPLOYMENT of the
# application. Every service runs the same image; which modules it serves is
# an environment variable (ZC_MODULES) the caller sets.
#
# The module is the same for a service that runs a fixed number of tasks and
# for one that scales horizontally. The only switch is var.autoscaling:
#   null  -> aws_ecs_service.fixed, nothing from autoscaling.tf
#   set   -> aws_ecs_service.autoscaled + scalable target + policies
#
# The module does NOT create the target group nor the listener rules that send
# traffic to it: it receives the target group its tasks register in. Routing
# belongs to the caller, because where a module's API goes can change (see
# modules/platform/routing.tf) while this service stays as it is.

data "aws_region" "current" {}
data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  name       = "${var.name_prefix}-${var.service_name}"
  autoscaled = var.autoscaling != null

  # The most tasks the service is allowed to run in steady state, and the most
  # that can exist for a moment while a rolling deployment starts the new tasks
  # before stopping the old ones. Callers size database connections from these.
  max_task_count  = local.autoscaled ? var.autoscaling.max : var.desired_count
  peak_task_count = ceil(local.max_task_count * var.deployment_maximum_percent / 100)

  health_check_command = (
    var.container_health_check_command != null
    ? var.container_health_check_command
    : ["CMD-SHELL", "curl -fsS http://localhost:${var.container_port}${var.health_check_path} || exit 1"]
  )

  log_options = {
    "awslogs-group"  = aws_cloudwatch_log_group.this.name
    "awslogs-region" = data.aws_region.current.region
  }
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${var.name_prefix}/${var.service_name}"
  retention_in_days = var.log_retention_days
}

# -----------------------------------------------------------------------------
# Network: the container port is reachable from the load balancer and from
# nothing else.
# -----------------------------------------------------------------------------

resource "aws_security_group" "this" {
  name        = local.name
  description = "Tasks of ${local.name}: container port from the load balancer only"
  vpc_id      = var.vpc_id

  tags = {
    Name = local.name
  }
}

resource "aws_vpc_security_group_ingress_rule" "from_alb" {
  security_group_id            = aws_security_group.this.id
  description                  = "Container port from the load balancer"
  referenced_security_group_id = var.alb_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = var.container_port
  to_port                      = var.container_port
}

# The tasks run in private subnets and leave only through the NAT gateway.
# What they call has no fixed address range: AWS APIs (SNS, SQS, KMS, Secrets
# Manager, ECR, CloudWatch), the client's Keycloak and, optionally, an OTLP
# collector. Inbound stays closed to everything but the load balancer.
#trivy:ignore:AWS-0104
resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.this.id
  description       = "AWS APIs through NAT, the database, the OIDC issuer and the OTLP collector"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# -----------------------------------------------------------------------------
# Task definitions
# -----------------------------------------------------------------------------

# The image this service was CREATED with. Captured once and never updated:
# after the first revision, the deploy pipeline is the one that publishes
# images (it registers a revision per release and Terraform ignores the
# service's task definition). Without this, every release would show up in the
# next plan as task definitions to replace.
#
# What it buys: var.image can follow the release that is deployed now, so a
# service created later (a module promoted to its own service) starts with the
# image the rest of the system is running, not with the one of the first day.
resource "terraform_data" "first_image" {
  input = var.image

  lifecycle {
    ignore_changes = [input]
  }
}

resource "aws_ecs_task_definition" "service" {
  family = local.name

  # A task definition cannot be modified: a change of configuration registers
  # a new revision ("must be replaced" in the plan). The previous revision is
  # kept, because the service may still be running it and the deployment
  # circuit breaker may have to go back to it.
  skip_destroy = true

  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name      = var.container_name
      image     = terraform_data.first_image.input
      essential = true

      portMappings = [
        { containerPort = var.container_port, protocol = "tcp" },
      ]

      # Sorted by name so the rendered JSON, and therefore the plan, is stable.
      environment = [for name in sort(keys(var.environment)) : { name = name, value = var.environment[name] }]
      secrets     = [for name in sort(keys(var.secrets)) : { name = name, valueFrom = var.secrets[name] }]

      # Liveness, not readiness, on purpose: a failing health check makes ECS
      # replace the task, and readiness includes the database, so a short
      # database blip would restart every task at once. The target group the
      # caller creates must use the same endpoint.
      healthCheck = {
        command     = local.health_check_command
        interval    = 15
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }

      # Time the consumers get to drain after SIGTERM before ECS sends SIGKILL.
      stopTimeout = var.stop_timeout_seconds

      logConfiguration = {
        logDriver = "awslogs"
        options   = merge(local.log_options, { "awslogs-stream-prefix" = "service" })
      }
    },
  ])
}

# Schema migrations never run inside a long-running service. This second task
# definition uses the same image with the schema owners' credentials and its
# own execution role, so the service's roles can never read an owner password.
# It has no task role: a migration makes no AWS API calls.
resource "aws_ecs_task_definition" "migrate" {
  count = var.migrate == null ? 0 : 1

  family                   = "${local.name}-migrate"
  skip_destroy             = true # as above: a change registers a revision, nothing is deregistered
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.migrate_execution[0].arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name      = var.container_name
      image     = terraform_data.first_image.input
      essential = true

      environment = [for name in sort(keys(var.migrate.environment)) : { name = name, value = var.migrate.environment[name] }]
      secrets     = [for name in sort(keys(var.migrate.secrets)) : { name = name, valueFrom = var.migrate.secrets[name] }]

      logConfiguration = {
        logDriver = "awslogs"
        options   = merge(local.log_options, { "awslogs-stream-prefix" = "migrate" })
      }
    },
  ])
}

# -----------------------------------------------------------------------------
# Service
#
# Terraform creates the service; the deploy pipeline publishes every new task
# definition revision. Terraform must therefore ignore `task_definition`, or
# each apply would roll the service back to the revision it knows about.
#
# `desired_count` is different for the two kinds of service:
#   - fixed:      Terraform owns it. Changing var.desired_count changes the service.
#   - autoscaled: Application Auto Scaling owns it. Terraform must ignore it, or
#                 each apply would undo whatever the scaling policies decided.
#
# `ignore_changes` only accepts a static list, so it cannot depend on a
# variable. Hence two resources with the same arguments and a different
# lifecycle block, of which exactly one is created. Keep them identical apart
# from `desired_count` and `lifecycle`.
#
# Moving an existing service from one kind to the other changes its resource
# address. Run `terraform state mv` between `fixed[0]` and `autoscaled[0]`
# first, otherwise Terraform would replace the service.
# -----------------------------------------------------------------------------

resource "aws_ecs_service" "fixed" {
  count = local.autoscaled ? 0 : 1

  name            = local.name
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.service.arn
  desired_count   = var.desired_count

  launch_type      = "FARGATE"
  platform_version = "LATEST"

  deployment_minimum_healthy_percent = var.deployment_minimum_healthy_percent
  deployment_maximum_percent         = var.deployment_maximum_percent
  health_check_grace_period_seconds  = var.health_check_grace_period_seconds
  availability_zone_rebalancing      = "ENABLED"

  enable_execute_command  = var.enable_execute_command
  enable_ecs_managed_tags = true
  propagate_tags          = "SERVICE"

  # A deployment whose tasks never become healthy is stopped and rolled back
  # to the last revision that worked.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.this.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = var.container_name
    container_port   = var.container_port
  }

  lifecycle {
    ignore_changes = [task_definition]
  }

  # ECS only accepts a target group that is attached to a load balancer, and
  # the rules that attach this one belong to the caller.
  depends_on = [var.listener_rule_arns]
}

resource "aws_ecs_service" "autoscaled" {
  count = local.autoscaled ? 1 : 0

  name            = local.name
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.service.arn
  desired_count   = var.autoscaling.min # initial value only, see lifecycle

  launch_type      = "FARGATE"
  platform_version = "LATEST"

  deployment_minimum_healthy_percent = var.deployment_minimum_healthy_percent
  deployment_maximum_percent         = var.deployment_maximum_percent
  health_check_grace_period_seconds  = var.health_check_grace_period_seconds
  availability_zone_rebalancing      = "ENABLED"

  enable_execute_command  = var.enable_execute_command
  enable_ecs_managed_tags = true
  propagate_tags          = "SERVICE"

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.this.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = var.container_name
    container_port   = var.container_port
  }

  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [var.listener_rule_arns]
}

locals {
  # Whichever of the two exists.
  service = one(concat(aws_ecs_service.fixed, aws_ecs_service.autoscaled))
}
