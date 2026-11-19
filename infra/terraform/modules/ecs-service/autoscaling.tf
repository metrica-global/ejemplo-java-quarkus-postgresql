# Horizontal autoscaling. Everything in this file has
# `count = local.autoscaled ? 1 : 0`: a service declared without
# var.autoscaling gets no scalable target and no policy.
#
# Two target tracking policies act on the same target. Application Auto
# Scaling scales OUT when either of them asks for more tasks and scales IN only
# when both agree, so the service follows whichever signal is under pressure.

resource "aws_appautoscaling_target" "this" {
  count = local.autoscaled ? 1 : 0

  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${var.cluster_name}/${aws_ecs_service.autoscaled[0].name}"
  min_capacity       = var.autoscaling.min
  max_capacity       = var.autoscaling.max
}

# Policy 1: average CPU of the service.
resource "aws_appautoscaling_policy" "cpu" {
  count = local.autoscaled ? 1 : 0

  name               = "${local.name}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this[0].service_namespace
  scalable_dimension = aws_appautoscaling_target.this[0].scalable_dimension
  resource_id        = aws_appautoscaling_target.this[0].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.autoscaling.cpu_target
    scale_out_cooldown = var.autoscaling.scale_out_cooldown
    scale_in_cooldown  = var.autoscaling.scale_in_cooldown

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

# Policy 2: backlog per task.
#
# Queue depth alone is a poor target: the right depth depends on how many
# tasks are already working on it. The pattern AWS documents for scaling on a
# queue is to track "messages waiting per task" with metric math:
#
#   m1 = AWS/SQS              ApproximateNumberOfMessagesVisible {QueueName}
#   m2 = ECS/ContainerInsights RunningTaskCount {ClusterName, ServiceName}
#   e1 = m1 / m2              <- the tracked value
#
# m2 exists only when Container Insights is enabled on the cluster
# (modules/ecs-cluster always enables it). The minimum capacity is at least 1,
# so m2 is never zero.
resource "aws_appautoscaling_policy" "backlog" {
  count = local.autoscaled && try(var.autoscaling.queue, null) != null ? 1 : 0

  name               = "${local.name}-backlog-per-task"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this[0].service_namespace
  scalable_dimension = aws_appautoscaling_target.this[0].scalable_dimension
  resource_id        = aws_appautoscaling_target.this[0].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.autoscaling.queue.backlog_per_task_target
    scale_out_cooldown = var.autoscaling.scale_out_cooldown
    scale_in_cooldown  = var.autoscaling.scale_in_cooldown

    customized_metric_specification {
      metrics {
        id          = "m1"
        label       = "Messages waiting in the queue"
        return_data = false

        metric_stat {
          stat = "Sum"

          metric {
            namespace   = "AWS/SQS"
            metric_name = "ApproximateNumberOfMessagesVisible"

            dimensions {
              name  = "QueueName"
              value = var.autoscaling.queue.name
            }
          }
        }
      }

      metrics {
        id          = "m2"
        label       = "Tasks currently running"
        return_data = false

        metric_stat {
          stat = "Average"

          metric {
            namespace   = "ECS/ContainerInsights"
            metric_name = "RunningTaskCount"

            dimensions {
              name  = "ClusterName"
              value = var.cluster_name
            }

            dimensions {
              name  = "ServiceName"
              value = aws_ecs_service.autoscaled[0].name
            }
          }
        }
      }

      metrics {
        id          = "e1"
        label       = "Backlog per task"
        expression  = "m1 / m2"
        return_data = true
      }
    }
  }
}
