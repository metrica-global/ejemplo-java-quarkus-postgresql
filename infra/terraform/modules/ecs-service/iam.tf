# Three roles per service, each with the least it needs:
#
#   task role               what the application code may do (SNS, SQS, KMS)
#   execution role          what ECS needs to START the service task
#                           (pull the image, write logs, read the service's secrets)
#   migrate execution role  the same for the migration task, with the schema
#                           owner's secret instead of the application's
#
# None of them uses an AWS managed policy: every permission is listed here.

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.region

  has_messaging_permissions = (
    length(var.publish_topic_arns) + length(var.consume_queue_arns) + length(var.send_queue_arns)
  ) > 0
}

# -----------------------------------------------------------------------------
# Trust policies
# -----------------------------------------------------------------------------

# Task role: only tasks of this account and region may assume it (the
# confused-deputy guard AWS documents for task roles).
data "aws_iam_policy_document" "task_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:ecs:${local.region}:${local.account_id}:*"]
    }
  }
}

data "aws_iam_policy_document" "execution_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# -----------------------------------------------------------------------------
# Task role
# -----------------------------------------------------------------------------

resource "aws_iam_role" "task" {
  name               = "${local.name}-task"
  description        = "Application permissions of ${local.name}"
  assume_role_policy = data.aws_iam_policy_document.task_assume.json
}

data "aws_iam_policy_document" "task" {
  dynamic "statement" {
    for_each = length(var.publish_topic_arns) > 0 ? [1] : []

    content {
      sid       = "PublishOwnEvents"
      actions   = ["sns:Publish"]
      resources = var.publish_topic_arns
    }
  }

  dynamic "statement" {
    for_each = length(var.consume_queue_arns) > 0 ? [1] : []

    content {
      sid = "ConsumeOwnQueues"
      actions = [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:ChangeMessageVisibility",
        "sqs:GetQueueAttributes",
      ]
      resources = var.consume_queue_arns
    }
  }

  dynamic "statement" {
    for_each = length(var.send_queue_arns) > 0 ? [1] : []

    content {
      sid       = "SendToOwnWorkQueues"
      actions   = ["sqs:SendMessage"]
      resources = var.send_queue_arns
    }
  }

  # Publishing to an encrypted topic or queue needs a data key; receiving from
  # an encrypted queue needs to decrypt it.
  dynamic "statement" {
    for_each = length(var.kms_key_arns) > 0 ? [1] : []

    content {
      sid       = "UseMessagingKey"
      actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
      resources = var.kms_key_arns
    }
  }
}

resource "aws_iam_role_policy" "task_messaging" {
  count = local.has_messaging_permissions ? 1 : 0

  name   = "messaging"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}

# ECS Exec opens its session through SSM channels. These four actions do not
# support resource-level permissions.
data "aws_iam_policy_document" "task_exec_command" {
  count = var.enable_execute_command ? 1 : 0

  statement {
    sid = "EcsExecChannels"
    actions = [
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "task_exec_command" {
  count = var.enable_execute_command ? 1 : 0

  name   = "ecs-exec"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_exec_command[0].json
}

# -----------------------------------------------------------------------------
# Execution roles
# -----------------------------------------------------------------------------

# Statements shared by both execution roles: pull this service's image and
# write to this service's log group.
data "aws_iam_policy_document" "execution_base" {
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # this action does not support resource-level permissions
  }

  statement {
    sid = "PullServiceImage"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchGetImage",
    ]
    resources = var.ecr_repository_arns
  }

  statement {
    sid       = "WriteServiceLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.this.arn}:*"]
  }
}

resource "aws_iam_role" "execution" {
  name               = "${local.name}-execution"
  description        = "Lets ECS start the ${local.name} service task"
  assume_role_policy = data.aws_iam_policy_document.execution_assume.json
}

data "aws_iam_policy_document" "execution" {
  source_policy_documents = [data.aws_iam_policy_document.execution_base.json]

  dynamic "statement" {
    for_each = length(var.secrets) > 0 ? [1] : []

    content {
      sid       = "ReadServiceSecrets"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = values(var.secrets)
    }
  }
}

resource "aws_iam_role_policy" "execution" {
  name   = "start-task"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

resource "aws_iam_role" "migrate_execution" {
  count = var.migrate == null ? 0 : 1

  name               = "${local.name}-migrate-execution"
  description        = "Lets ECS start the ${local.name} migration task"
  assume_role_policy = data.aws_iam_policy_document.execution_assume.json
}

data "aws_iam_policy_document" "migrate_execution" {
  count = var.migrate == null ? 0 : 1

  source_policy_documents = [data.aws_iam_policy_document.execution_base.json]

  dynamic "statement" {
    for_each = length(var.migrate.secrets) > 0 ? [1] : []

    content {
      sid       = "ReadMigrationSecrets"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = values(var.migrate.secrets)
    }
  }
}

resource "aws_iam_role_policy" "migrate_execution" {
  count = var.migrate == null ? 0 : 1

  name   = "start-task"
  role   = aws_iam_role.migrate_execution[0].id
  policy = data.aws_iam_policy_document.migrate_execution[0].json
}
