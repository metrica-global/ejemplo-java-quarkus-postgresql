# Application Load Balancer in front of the services.
#
# The listener answers 404 by default. Each service adds its own path rule
# (modules/ecs-service), so a path nobody claimed never reaches a container.

locals {
  https_enabled = var.certificate_arn != null
}

resource "aws_security_group" "alb" {
  name        = "${var.name_prefix}-alb"
  description = "Load balancer of ${var.name_prefix}: HTTP(S) in, container port out"
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.name_prefix}-alb"
  }
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  for_each = toset(var.ingress_cidrs)

  security_group_id = aws_security_group.alb.id
  description       = local.https_enabled ? "HTTP, redirected to HTTPS" : "HTTP"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  for_each = local.https_enabled ? toset(var.ingress_cidrs) : toset([])

  security_group_id = aws_security_group.alb.id
  description       = "HTTPS"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# The load balancer only ever talks to the tasks, on their container port.
resource "aws_vpc_security_group_egress_rule" "alb_to_tasks" {
  security_group_id = aws_security_group.alb.id
  description       = "Traffic and health checks to the tasks"
  cidr_ipv4         = var.vpc_cidr
  ip_protocol       = "tcp"
  from_port         = var.container_port
  to_port           = var.container_port
}

# Internet-facing on purpose: this is the public entry point of the API.
# var.ingress_cidrs narrows who can reach it and var.internal makes it private.
#trivy:ignore:AWS-0053
resource "aws_lb" "this" {
  name               = "${var.name_prefix}-alb"
  load_balancer_type = "application"
  internal           = var.internal
  subnets            = var.subnet_ids
  security_groups    = [aws_security_group.alb.id]

  drop_invalid_header_fields = true
  enable_deletion_protection = var.deletion_protection
  idle_timeout               = var.idle_timeout_seconds
}

# Port 80. With a certificate it only redirects to HTTPS. Without one it is the
# listener the services attach to, and traffic is plain HTTP: modules/platform
# refuses that unless the environment sets allow_plain_http (development only).
#trivy:ignore:AWS-0054
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  dynamic "default_action" {
    for_each = local.https_enabled ? [1] : []

    content {
      type = "redirect"

      redirect {
        protocol    = "HTTPS"
        port        = "443"
        status_code = "HTTP_301"
      }
    }
  }

  dynamic "default_action" {
    for_each = local.https_enabled ? [] : [1]

    content {
      type = "fixed-response"

      fixed_response {
        content_type = "application/json"
        message_body = jsonencode({ message = "Not Found" })
        status_code  = "404"
      }
    }
  }
}

resource "aws_lb_listener" "https" {
  count = local.https_enabled ? 1 : 0

  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = var.ssl_policy
  certificate_arn   = var.certificate_arn

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "application/json"
      message_body = jsonencode({ message = "Not Found" })
      status_code  = "404"
    }
  }
}
