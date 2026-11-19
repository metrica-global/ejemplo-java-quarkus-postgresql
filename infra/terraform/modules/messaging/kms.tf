# One customer managed key for every topic and queue of the environment.
#
# The AWS managed keys (alias/aws/sns, alias/aws/sqs) cannot be used here: SNS
# has to call KMS to deliver into an encrypted queue, and only a customer
# managed key lets its policy grant that to the SNS service.

data "aws_caller_identity" "current" {
  count = var.enable_encryption ? 1 : 0
}

data "aws_partition" "current" {
  count = var.enable_encryption ? 1 : 0
}

data "aws_iam_policy_document" "kms" {
  count = var.enable_encryption ? 1 : 0

  # Standard key policy: the account decides, through IAM policies, who may use
  # the key. The task roles get their permissions in modules/ecs-service.
  statement {
    sid       = "EnableIamPolicies"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = ["arn:${data.aws_partition.current[0].partition}:iam::${data.aws_caller_identity.current[0].account_id}:root"]
    }
  }

  # SNS encrypts the message it stores in the topic and the copy it writes into
  # each subscribed queue. This is the statement AWS documents for delivering
  # from SNS to an encrypted SQS queue.
  statement {
    sid       = "AllowSnsToDeliverToEncryptedQueues"
    actions   = ["kms:GenerateDataKey*", "kms:Decrypt"]
    resources = ["*"]

    principals {
      type        = "Service"
      identifiers = ["sns.amazonaws.com"]
    }
  }
}

resource "aws_kms_key" "this" {
  count = var.enable_encryption ? 1 : 0

  description             = "Encrypts the SNS topics and SQS queues of ${var.name_prefix}"
  enable_key_rotation     = true
  deletion_window_in_days = var.kms_deletion_window_days
  policy                  = data.aws_iam_policy_document.kms[0].json
}

resource "aws_kms_alias" "this" {
  count = var.enable_encryption ? 1 : 0

  name          = "alias/${var.name_prefix}-messaging"
  target_key_id = aws_kms_key.this[0].key_id
}
