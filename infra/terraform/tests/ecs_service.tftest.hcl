# modules/ecs-service: the autoscaling switch.
#
# The same module, with the same inputs, is planned twice: once without
# `autoscaling` and once with it. The assertions are the claim the reference
# makes: a fixed service has NO Application Auto Scaling resources at all, and
# an autoscaled one has a target plus the CPU and backlog-per-task policies.

mock_provider "aws" {
  source = "./mocks/aws"
}

variables {
  name_prefix  = "zonacards-test"
  service_name = "giftcards"

  cluster_arn  = "arn:aws:ecs:us-east-1:123456789012:cluster/zonacards-test-cluster"
  cluster_name = "zonacards-test-cluster"
  vpc_id       = "vpc-00000000000000000"
  subnet_ids   = ["subnet-00000000000000001", "subnet-00000000000000002"]

  alb_security_group_id  = "sg-00000000000000000"
  listener_arn           = "arn:aws:elasticloadbalancing:us-east-1:123456789012:listener/app/zonacards-test-alb/0000000000000000/0000000000000000"
  listener_rule_priority = 200
  path_patterns          = ["/api/v1/giftcards*"]

  image               = "123456789012.dkr.ecr.us-east-1.amazonaws.com/zonacards/giftcards-service:test"
  ecr_repository_arns = ["arn:aws:ecr:us-east-1:123456789012:repository/zonacards/giftcards-service"]
  cpu                 = 1024
  memory              = 2048
}

run "fixed_service_has_no_autoscaling_resources" {
  command = plan

  module {
    source = "../modules/ecs-service"
  }

  variables {
    desired_count = 2
    # autoscaling is not set: null by default
  }

  assert {
    condition     = length(aws_appautoscaling_target.this) == 0
    error_message = "A fixed service must not create a scalable target."
  }

  assert {
    condition     = length(aws_appautoscaling_policy.cpu) + length(aws_appautoscaling_policy.backlog) == 0
    error_message = "A fixed service must not create any scaling policy."
  }

  assert {
    condition     = length(aws_ecs_service.fixed) == 1 && length(aws_ecs_service.autoscaled) == 0
    error_message = "A fixed service must be the resource whose desired_count Terraform owns."
  }

  assert {
    condition     = aws_ecs_service.fixed[0].desired_count == 2
    error_message = "A fixed service must run exactly desired_count tasks."
  }

  assert {
    condition     = output.autoscaling == null
    error_message = "The autoscaling output of a fixed service must be null."
  }
}

run "autoscaled_service_has_target_and_two_policies" {
  command = plan

  module {
    source = "../modules/ecs-service"
  }

  variables {
    autoscaling = {
      min        = 2
      max        = 10
      cpu_target = 60
      queue = {
        name                    = "zonacards-test-giftcards-generacion"
        backlog_per_task_target = 10
      }
    }
  }

  # --- exactly one of the two service resources -----------------------------

  assert {
    condition     = length(aws_ecs_service.autoscaled) == 1 && length(aws_ecs_service.fixed) == 0
    error_message = "An autoscaled service must be the resource that ignores desired_count."
  }

  assert {
    condition     = aws_ecs_service.autoscaled[0].desired_count == 2
    error_message = "An autoscaled service must start at autoscaling.min."
  }

  # --- one scalable target with the declared range ---------------------------

  assert {
    condition     = length(aws_appautoscaling_target.this) == 1
    error_message = "An autoscaled service must create exactly one scalable target."
  }

  assert {
    condition = (
      aws_appautoscaling_target.this[0].min_capacity == 2 &&
      aws_appautoscaling_target.this[0].max_capacity == 10
    )
    error_message = "The scalable target must use autoscaling.min and autoscaling.max."
  }

  assert {
    condition = (
      aws_appautoscaling_target.this[0].service_namespace == "ecs" &&
      aws_appautoscaling_target.this[0].scalable_dimension == "ecs:service:DesiredCount" &&
      aws_appautoscaling_target.this[0].resource_id == "service/zonacards-test-cluster/zonacards-test-giftcards"
    )
    error_message = "The scalable target must point at the desired count of this ECS service."
  }

  # --- policy 1: CPU ----------------------------------------------------------

  assert {
    condition     = length(aws_appautoscaling_policy.cpu) == 1 && length(aws_appautoscaling_policy.backlog) == 1
    error_message = "An autoscaled service with a queue must create two policies: CPU and backlog per task."
  }

  assert {
    condition = (
      aws_appautoscaling_policy.cpu[0].policy_type == "TargetTrackingScaling" &&
      aws_appautoscaling_policy.cpu[0].target_tracking_scaling_policy_configuration[0].target_value == 60 &&
      aws_appautoscaling_policy.cpu[0].target_tracking_scaling_policy_configuration[0].predefined_metric_specification[0].predefined_metric_type == "ECSServiceAverageCPUUtilization"
    )
    error_message = "The CPU policy must track ECSServiceAverageCPUUtilization at cpu_target."
  }

  assert {
    condition = (
      aws_appautoscaling_policy.cpu[0].target_tracking_scaling_policy_configuration[0].scale_out_cooldown == 60 &&
      aws_appautoscaling_policy.cpu[0].target_tracking_scaling_policy_configuration[0].scale_in_cooldown == 300
    )
    error_message = "Default cooldowns must be 60 s to scale out and 300 s to scale in."
  }

  # --- policy 2: backlog per task, with metric math ---------------------------

  assert {
    condition = (
      aws_appautoscaling_policy.backlog[0].policy_type == "TargetTrackingScaling" &&
      aws_appautoscaling_policy.backlog[0].target_tracking_scaling_policy_configuration[0].target_value == 10
    )
    error_message = "The backlog policy must track backlog_per_task_target."
  }

  assert {
    condition = one([
      for m in aws_appautoscaling_policy.backlog[0].target_tracking_scaling_policy_configuration[0].customized_metric_specification[0].metrics :
      m.expression if m.id == "e1" && m.return_data
    ]) == "m1 / m2"
    error_message = "The tracked value must be the expression m1 / m2 (queue depth / running tasks)."
  }

  assert {
    condition = one([
      for m in aws_appautoscaling_policy.backlog[0].target_tracking_scaling_policy_configuration[0].customized_metric_specification[0].metrics :
      {
        namespace   = m.metric_stat[0].metric[0].namespace
        metric_name = m.metric_stat[0].metric[0].metric_name
        stat        = m.metric_stat[0].stat
        dimensions  = { for d in m.metric_stat[0].metric[0].dimensions : d.name => d.value }
        return_data = m.return_data
      } if m.id == "m1"
      ]) == {
      namespace   = "AWS/SQS"
      metric_name = "ApproximateNumberOfMessagesVisible"
      stat        = "Sum"
      dimensions  = { QueueName = "zonacards-test-giftcards-generacion" }
      return_data = false
    }
    error_message = "m1 must be the visible messages of the work queue (AWS/SQS ApproximateNumberOfMessagesVisible)."
  }

  assert {
    condition = one([
      for m in aws_appautoscaling_policy.backlog[0].target_tracking_scaling_policy_configuration[0].customized_metric_specification[0].metrics :
      {
        namespace   = m.metric_stat[0].metric[0].namespace
        metric_name = m.metric_stat[0].metric[0].metric_name
        stat        = m.metric_stat[0].stat
        dimensions  = { for d in m.metric_stat[0].metric[0].dimensions : d.name => d.value }
        return_data = m.return_data
      } if m.id == "m2"
      ]) == {
      namespace   = "ECS/ContainerInsights"
      metric_name = "RunningTaskCount"
      stat        = "Average"
      dimensions = {
        ClusterName = "zonacards-test-cluster"
        ServiceName = "zonacards-test-giftcards"
      }
      return_data = false
    }
    error_message = "m2 must be the running tasks of this service (ECS/ContainerInsights RunningTaskCount)."
  }

  assert {
    condition = output.autoscaling == {
      min_capacity = 2
      max_capacity = 10
      policies     = ["zonacards-test-giftcards-cpu", "zonacards-test-giftcards-backlog-per-task"]
    }
    error_message = "The autoscaling output must summarise the target and both policies."
  }
}

run "autoscaling_without_queue_scales_on_cpu_only" {
  command = plan

  module {
    source = "../modules/ecs-service"
  }

  variables {
    autoscaling = {
      min = 1
      max = 4
    }
  }

  assert {
    condition     = length(aws_appautoscaling_policy.cpu) == 1 && length(aws_appautoscaling_policy.backlog) == 0
    error_message = "Without a queue only the CPU policy must exist."
  }
}

run "health_checks_use_the_liveness_endpoint" {
  command = plan

  module {
    source = "../modules/ecs-service"
  }

  assert {
    condition     = aws_lb_target_group.this.health_check[0].path == "/q/health/live"
    error_message = "The target group must check liveness: a readiness check would replace every task on a database blip."
  }

  assert {
    condition     = strcontains(aws_ecs_task_definition.service.container_definitions, "http://localhost:8080/q/health/live")
    error_message = "The container health check must use the same liveness endpoint."
  }

  assert {
    condition     = aws_lb_target_group.this.port == 8080 && aws_lb_target_group.this.target_type == "ip"
    error_message = "The target group must send traffic to port 8080 of the task IPs."
  }
}

run "migration_task_is_separate_from_the_service_task" {
  command = plan

  module {
    source = "../modules/ecs-service"
  }

  variables {
    environment = {
      ZC_DB_APP_USER = "giftcards_app"
    }
    secrets = {
      ZC_DB_APP_PASSWORD = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-test-giftcards-db-app-password-AAAAAA"
    }
    migrate = {
      environment = {
        ZC_MODE          = "migrate"
        ZC_DB_URL        = "jdbc:postgresql://db.example.internal:5432/zonacards?sslmode=require"
        ZC_DB_OWNER_USER = "giftcards_owner"
      }
      secrets = {
        ZC_DB_OWNER_PASSWORD = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-test-giftcards-db-owner-password-AAAAAA"
      }
    }
  }

  assert {
    condition     = aws_ecs_task_definition.migrate[0].family == "zonacards-test-giftcards-migrate"
    error_message = "The migration task definition must be the family <service>-migrate."
  }

  assert {
    condition = jsondecode(aws_ecs_task_definition.migrate[0].container_definitions)[0].environment == [
      { name = "ZC_DB_OWNER_USER", value = "giftcards_owner" },
      { name = "ZC_DB_URL", value = "jdbc:postgresql://db.example.internal:5432/zonacards?sslmode=require" },
      { name = "ZC_MODE", value = "migrate" },
    ]
    error_message = "The migration container must receive exactly the environment it is given, nothing inherited from the service."
  }

  assert {
    condition = jsondecode(aws_ecs_task_definition.migrate[0].container_definitions)[0].secrets == [
      {
        name      = "ZC_DB_OWNER_PASSWORD"
        valueFrom = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-test-giftcards-db-owner-password-AAAAAA"
      },
    ]
    error_message = "The migration container must receive the schema owner's password as an ECS secret."
  }

  assert {
    condition = (
      jsondecode(aws_ecs_task_definition.migrate[0].container_definitions)[0].image ==
      jsondecode(aws_ecs_task_definition.service.container_definitions)[0].image
    )
    error_message = "The migration task must use the same image as the service."
  }

  assert {
    condition     = !strcontains(aws_ecs_task_definition.service.container_definitions, "OWNER")
    error_message = "The long-running service task must never receive the schema owner's credentials."
  }

  assert {
    condition     = length(aws_iam_role.migrate_execution) == 1 && aws_ecs_task_definition.migrate[0].task_role_arn == null
    error_message = "The migration task must have its own execution role and no task role."
  }

  assert {
    condition     = jsondecode(aws_ecs_task_definition.service.container_definitions)[0].stopTimeout == 90
    error_message = "The service container must get 90 s to drain after SIGTERM."
  }
}

# The policy JSON is rendered by a data source the mock cannot evaluate, so
# these assertions read the STATEMENTS the module declares, which is what the
# real provider renders.
run "roles_get_only_what_the_caller_lists" {
  command = plan

  module {
    source = "../modules/ecs-service"
  }

  variables {
    publish_topic_arns = ["arn:aws:sns:us-east-1:123456789012:zonacards-test-giftcards-events"]
    consume_queue_arns = [
      "arn:aws:sqs:us-east-1:123456789012:zonacards-test-giftcards-inbox",
      "arn:aws:sqs:us-east-1:123456789012:zonacards-test-giftcards-generacion",
    ]
    send_queue_arns = ["arn:aws:sqs:us-east-1:123456789012:zonacards-test-giftcards-generacion"]
    kms_key_arns    = ["arn:aws:kms:us-east-1:123456789012:key/00000000-0000-0000-0000-000000000000"]

    secrets = {
      ZC_DB_APP_PASSWORD = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-test-giftcards-db-app-password-AAAAAA"
    }
    migrate = {
      environment = { ZC_MODE = "migrate" }
      secrets = {
        ZC_DB_OWNER_PASSWORD = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-test-giftcards-db-owner-password-AAAAAA"
      }
    }
  }

  assert {
    condition = {
      for s in data.aws_iam_policy_document.task.statement :
      s.sid => { actions = toset(s.actions), resources = toset(s.resources) }
      } == {
      PublishOwnEvents = {
        actions   = toset(["sns:Publish"])
        resources = toset(["arn:aws:sns:us-east-1:123456789012:zonacards-test-giftcards-events"])
      }
      ConsumeOwnQueues = {
        actions = toset([
          "sqs:ChangeMessageVisibility",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:ReceiveMessage",
        ])
        resources = toset([
          "arn:aws:sqs:us-east-1:123456789012:zonacards-test-giftcards-generacion",
          "arn:aws:sqs:us-east-1:123456789012:zonacards-test-giftcards-inbox",
        ])
      }
      SendToOwnWorkQueues = {
        actions   = toset(["sqs:SendMessage"])
        resources = toset(["arn:aws:sqs:us-east-1:123456789012:zonacards-test-giftcards-generacion"])
      }
      UseMessagingKey = {
        actions   = toset(["kms:Decrypt", "kms:GenerateDataKey"])
        resources = toset(["arn:aws:kms:us-east-1:123456789012:key/00000000-0000-0000-0000-000000000000"])
      }
    }
    error_message = "The task role must contain exactly the publish, consume, send and KMS statements built from the caller's lists."
  }

  assert {
    condition = one([
      for s in data.aws_iam_policy_document.execution.statement : toset(s.resources)
      if contains(s.actions, "secretsmanager:GetSecretValue")
    ]) == toset(["arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-test-giftcards-db-app-password-AAAAAA"])
    error_message = "The service execution role must read only the service's own secrets."
  }

  assert {
    condition = one([
      for s in data.aws_iam_policy_document.migrate_execution[0].statement : toset(s.resources)
      if contains(s.actions, "secretsmanager:GetSecretValue")
    ]) == toset(["arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-test-giftcards-db-owner-password-AAAAAA"])
    error_message = "Only the migration execution role may read the schema owner's password."
  }

  assert {
    condition     = length(aws_iam_role_policy.task_exec_command) == 0
    error_message = "ECS Exec permissions must not exist unless enable_execute_command is set."
  }
}

run "rejects_an_autoscaling_range_that_starts_at_zero" {
  command = plan

  module {
    source = "../modules/ecs-service"
  }

  variables {
    autoscaling = {
      min = 0
      max = 4
    }
  }

  expect_failures = [var.autoscaling]
}
