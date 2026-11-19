# Routing: one target group per SERVICE and one listener rule per MODULE.
#
# A module's rule always exists and always matches the same paths. What changes
# when the module is promoted is where the rule forwards to, and that is an
# in-place change of the rule: nothing is destroyed and the priority, the
# paths and the listener stay as they are.
#
# The forward action is weighted between two target groups:
#
#                                       nucleo   own service
#   module not promoted                  100     (not in the rule)
#   transition = "traffic-in-nucleo"     100        0     the service exists, no traffic
#   promoted                               0      100
#   transition = "traffic-in-service"      0      100     the nucleo serves it again, no traffic
#
# Weight 0 is what lets the new service exist before it receives traffic: ECS
# only accepts a service whose target group is attached to a load balancer,
# and a target group with weight 0 in a rule is attached and receives nothing.
# It is the arrangement Amazon ECS blue/green deployments use
# (docs.aws.amazon.com/AmazonECS/latest/developerguide/troubleshooting-blue-green.html).
#
# The target groups live here and not inside modules/ecs-service because a
# rule needs the target groups of two services, and a service must wait for
# the rules that attach its target group.

locals {
  health_check_path = "/q/health/live"
  container_port    = 8080

  service_names = toset(concat(["nucleo"], keys(var.promoted_modules)))
}

resource "aws_lb_target_group" "service" {
  for_each = local.service_names

  name        = "${local.name_prefix}-${each.key}"
  vpc_id      = module.network.vpc_id
  target_type = "ip" # required by Fargate (awsvpc network mode)
  port        = local.container_port
  protocol    = "HTTP"

  # Time the load balancer keeps draining in-flight requests of a task that is
  # going away, before ECS sends it SIGTERM.
  deregistration_delay = 30

  # Liveness, not readiness, on purpose: a failing target group health check
  # makes ECS replace the task, and readiness includes the database, so a short
  # database blip would restart every task at once. /q/health/ready stays informational.
  health_check {
    path                = local.health_check_path
    port                = "traffic-port"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  # Not for replacing it: for REMOVING it. When a module returns to the nucleo
  # its target group is deleted in the same apply that takes it out of the
  # module's rule, and AWS refuses to delete a target group a rule still uses.
  # By default Terraform deletes a resource before it updates what referred to
  # it; with this setting the rule is updated first. (A consequence: the name
  # is fixed, so changing an argument that forces a new target group, such as
  # the port, needs a new name too.)
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_lb_listener_rule" "module" {
  for_each = local.catalog

  listener_arn = module.cluster.listener_arn
  priority     = each.value.listener_priority

  action {
    type = "forward"

    forward {
      target_group {
        arn    = aws_lb_target_group.service["nucleo"].arn
        weight = local.placement[each.key].routed_to == "nucleo" ? 100 : 0
      }

      dynamic "target_group" {
        for_each = local.placement[each.key].own_service ? [each.key] : []

        content {
          arn    = aws_lb_target_group.service[target_group.value].arn
          weight = local.placement[each.key].routed_to == "nucleo" ? 0 : 100
        }
      }
    }
  }

  condition {
    path_pattern {
      values = each.value.path_patterns
    }
  }

  tags = {
    Module = each.key
  }
}
