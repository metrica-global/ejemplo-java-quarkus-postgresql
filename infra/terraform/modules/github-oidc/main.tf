# GitHub Actions authenticates with short-lived OIDC tokens. No access key is
# ever stored in GitHub.
#
# Three roles, one per job of the pipeline, each trusted for a different kind
# of workflow run:
#
#   build   pushes images to ECR                  runs on the main branch
#   deploy  registers task definitions, runs the  jobs of ONE GitHub environment
#           migration task, updates the services
#   plan    read-only, for `terraform plan`       pull requests

data "aws_region" "current" {}
data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.region

  oidc_host = "token.actions.githubusercontent.com"

  # When another configuration of the same account owns the provider, its ARN
  # is derived instead of looked up, so a plan never depends on it existing.
  oidc_provider_arn = (
    var.create_oidc_provider
    ? aws_iam_openid_connect_provider.github[0].arn
    : "arn:${local.partition}:iam::${local.account_id}:oidc-provider/${local.oidc_host}"
  )

  subject_prefix = "repo:${var.github_repository}:"

  trusted_subjects = {
    build  = [for s in var.build_subjects : "${local.subject_prefix}${s}"]
    deploy = ["${local.subject_prefix}environment:${var.github_environment}"]
    plan   = [for s in var.plan_subjects : "${local.subject_prefix}${s}"]
  }
}

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 1 : 0

  url            = "https://${local.oidc_host}"
  client_id_list = ["sts.amazonaws.com"]
}

# One trust policy per role: the token must be issued for AWS STS and its
# subject must be this repository in the expected context.
data "aws_iam_policy_document" "assume" {
  for_each = local.trusted_subjects

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "${local.oidc_host}:sub"
      values   = each.value
    }
  }
}

# -----------------------------------------------------------------------------
# Build: push images
# -----------------------------------------------------------------------------

resource "aws_iam_role" "build" {
  count = var.create_build_role ? 1 : 0

  name               = "${var.name_prefix}-github-build"
  description        = "GitHub Actions: build and push images of ${var.github_repository}"
  assume_role_policy = data.aws_iam_policy_document.assume["build"].json
}

data "aws_iam_policy_document" "build" {
  count = var.create_build_role ? 1 : 0

  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # this action does not support resource-level permissions
  }

  statement {
    sid = "PushAndScanImages"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:DescribeImageScanFindings",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = var.ecr_repository_arns
  }
}

resource "aws_iam_role_policy" "build" {
  count = var.create_build_role ? 1 : 0

  name   = "push-images"
  role   = aws_iam_role.build[0].id
  policy = data.aws_iam_policy_document.build[0].json
}

# -----------------------------------------------------------------------------
# Deploy: migrate, then roll the services
# -----------------------------------------------------------------------------

resource "aws_iam_role" "deploy" {
  name               = "${var.name_prefix}-github-deploy"
  description        = "GitHub Actions: deploy ${var.github_repository} to the ${var.github_environment} environment"
  assume_role_policy = data.aws_iam_policy_document.assume["deploy"].json
}

data "aws_iam_policy_document" "deploy" {
  statement {
    sid       = "CheckImageExists"
    actions   = ["ecr:DescribeImages", "ecr:BatchGetImage"]
    resources = var.ecr_repository_arns
  }

  # These actions do not support resource-level permissions. What limits them
  # is PassRole below: a task definition can only use the listed roles.
  statement {
    sid       = "RegisterTaskDefinitions"
    actions   = ["ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition", "ecs:ListTaskDefinitions"]
    resources = ["*"]
  }

  statement {
    sid       = "TagNewTaskDefinitions"
    actions   = ["ecs:TagResource"]
    resources = ["arn:${local.partition}:ecs:${local.region}:${local.account_id}:task-definition/*"]

    condition {
      test     = "StringEquals"
      variable = "ecs:CreateAction"
      values   = ["RegisterTaskDefinition"]
    }
  }

  statement {
    sid       = "PassTaskRolesToEcs"
    actions   = ["iam:PassRole"]
    resources = var.deploy.passable_role_arns

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  statement {
    sid     = "RunMigrationTasks"
    actions = ["ecs:RunTask"]
    resources = [
      for family in var.deploy.run_task_families :
      "arn:${local.partition}:ecs:${local.region}:${local.account_id}:task-definition/${family}:*"
    ]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [var.deploy.cluster_arn]
    }
  }

  statement {
    sid       = "WatchTasks"
    actions   = ["ecs:DescribeTasks"]
    resources = ["arn:${local.partition}:ecs:${local.region}:${local.account_id}:task/${var.deploy.cluster_name}/*"]
  }

  statement {
    sid       = "UpdateServices"
    actions   = ["ecs:UpdateService", "ecs:DescribeServices"]
    resources = var.deploy.service_arns
  }

  statement {
    sid       = "ReadTaskLogs"
    actions   = ["logs:GetLogEvents", "logs:FilterLogEvents"]
    resources = [for arn in var.deploy.log_group_arns : "${arn}:*"]
  }

  # The list of services to deploy is published by Terraform in a parameter;
  # the release that was deployed is recorded in another one.
  dynamic "statement" {
    for_each = length(var.deploy.read_parameter_arns) > 0 ? [1] : []

    content {
      sid       = "ReadDeployParameters"
      actions   = ["ssm:GetParameter"]
      resources = var.deploy.read_parameter_arns
    }
  }

  dynamic "statement" {
    for_each = length(var.deploy.write_parameter_arns) > 0 ? [1] : []

    content {
      sid       = "RecordTheRelease"
      actions   = ["ssm:PutParameter"]
      resources = var.deploy.write_parameter_arns
    }
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "deploy"
  role   = aws_iam_role.deploy.id
  policy = data.aws_iam_policy_document.deploy.json
}

# -----------------------------------------------------------------------------
# Plan: read-only
# -----------------------------------------------------------------------------

resource "aws_iam_role" "plan" {
  name               = "${var.name_prefix}-github-plan"
  description        = "GitHub Actions: read-only terraform plan for ${var.github_repository}"
  assume_role_policy = data.aws_iam_policy_document.assume["plan"].json
}

resource "aws_iam_role_policy_attachment" "plan_read_only" {
  role       = aws_iam_role.plan.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/ReadOnlyAccess"
}

locals {
  plan_needs_state_lock = var.state_bucket_arn != null
  plan_needs_secrets    = length(var.plan_secret_arns) > 0
}

data "aws_iam_policy_document" "plan_extra" {
  count = local.plan_needs_state_lock || local.plan_needs_secrets ? 1 : 0

  # With `use_lockfile = true` the S3 backend takes the state lock by writing
  # <key>.tflock next to the state. That is the only write a plan performs.
  dynamic "statement" {
    for_each = local.plan_needs_state_lock ? [1] : []

    content {
      sid       = "StateLockFile"
      actions   = ["s3:PutObject", "s3:DeleteObject"]
      resources = ["${var.state_bucket_arn}/*.tflock"]
    }
  }

  dynamic "statement" {
    for_each = local.plan_needs_secrets ? [1] : []

    content {
      sid       = "RefreshSecretVersions"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = var.plan_secret_arns
    }
  }
}

resource "aws_iam_role_policy" "plan_extra" {
  count = local.plan_needs_state_lock || local.plan_needs_secrets ? 1 : 0

  name   = "plan-extras"
  role   = aws_iam_role.plan.id
  policy = data.aws_iam_policy_document.plan_extra[0].json
}
