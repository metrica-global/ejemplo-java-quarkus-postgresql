# Event bus: SNS topics fan out to one SQS queue per consumer.
#
# The whole module is driven by var.topics and var.queues. Nothing here knows
# about "lotes" or "giftcards": the topology lives in ./topology and is shared
# by the AWS environments and by the local emulator.

locals {
  kms_key_id = var.enable_encryption ? aws_kms_key.this[0].arn : null

  # Queues that are subscribed to a topic (the inboxes).
  subscribed_queues = { for name, q in var.queues : name => q if q.subscribe_to != null }
}

# -----------------------------------------------------------------------------
# Topics
# -----------------------------------------------------------------------------

resource "aws_sns_topic" "this" {
  for_each = var.topics

  name              = "${var.name_prefix}-${each.key}"
  kms_master_key_id = local.kms_key_id
}

# -----------------------------------------------------------------------------
# Queues, each with its own dead-letter queue
# -----------------------------------------------------------------------------

resource "aws_sqs_queue" "dlq" {
  for_each = var.queues

  name                      = "${var.name_prefix}-${each.key}-dlq"
  message_retention_seconds = var.dlq_message_retention_seconds
  receive_wait_time_seconds = var.receive_wait_time_seconds

  kms_master_key_id                 = local.kms_key_id
  kms_data_key_reuse_period_seconds = var.enable_encryption ? 300 : null
}

resource "aws_sqs_queue" "this" {
  for_each = var.queues

  name                       = "${var.name_prefix}-${each.key}"
  visibility_timeout_seconds = each.value.visibility_timeout_seconds
  message_retention_seconds  = each.value.message_retention_seconds
  receive_wait_time_seconds  = var.receive_wait_time_seconds

  # After max_receive_count failed receives the message is parked in the
  # dead-letter queue instead of blocking the consumer forever.
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = each.value.max_receive_count
  })

  kms_master_key_id                 = local.kms_key_id
  kms_data_key_reuse_period_seconds = var.enable_encryption ? 300 : null
}

# -----------------------------------------------------------------------------
# Subscriptions: topic -> inbox, filtered by event type
# -----------------------------------------------------------------------------

# An inbox accepts messages from its own topic and from nobody else.
data "aws_iam_policy_document" "queue" {
  for_each = local.subscribed_queues

  statement {
    sid       = "AllowDeliveryFromSubscribedTopic"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.this[each.key].arn]

    principals {
      type        = "Service"
      identifiers = ["sns.amazonaws.com"]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_sns_topic.this[each.value.subscribe_to.topic].arn]
    }
  }
}

resource "aws_sqs_queue_policy" "this" {
  for_each = local.subscribed_queues

  queue_url = aws_sqs_queue.this[each.key].url
  policy    = data.aws_iam_policy_document.queue[each.key].json
}

resource "aws_sns_topic_subscription" "this" {
  for_each = local.subscribed_queues

  topic_arn = aws_sns_topic.this[each.value.subscribe_to.topic].arn
  protocol  = "sqs"
  endpoint  = aws_sqs_queue.this[each.key].arn

  # The body that reaches the queue is the CloudEvents envelope the publisher
  # sent, not an SNS wrapper. The filter therefore runs on message attributes.
  raw_message_delivery = true
  filter_policy_scope  = "MessageAttributes"
  filter_policy = jsonencode({
    type = each.value.subscribe_to.event_types
  })

  # SNS must be allowed to write to the queue before the subscription exists.
  depends_on = [aws_sqs_queue_policy.this]
}
