# Two alarms per queue. Together they answer "is something stuck?":
#   - a message reached the dead-letter queue (a handler gave up on it);
#   - the oldest message has waited too long (consumers are down or too slow).

locals {
  alarm_queues  = var.enable_alarms ? var.queues : {}
  alarm_actions = var.alarm_topic_arn == null ? [] : [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "dlq_not_empty" {
  for_each = local.alarm_queues

  alarm_name        = "${var.name_prefix}-${each.key}-dlq-not-empty"
  alarm_description = "Messages in ${aws_sqs_queue.dlq[each.key].name}: a consumer failed ${each.value.max_receive_count} times on the same message. See the DLQ runbook before redriving."

  namespace   = "AWS/SQS"
  metric_name = "ApproximateNumberOfMessagesVisible"
  dimensions = {
    QueueName = aws_sqs_queue.dlq[each.key].name
  }

  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "oldest_message_age" {
  for_each = local.alarm_queues

  alarm_name        = "${var.name_prefix}-${each.key}-oldest-message-age"
  alarm_description = "The oldest message in ${aws_sqs_queue.this[each.key].name} has waited more than ${each.value.oldest_message_age_alarm_seconds} s: consumers are stopped or cannot keep up."

  namespace   = "AWS/SQS"
  metric_name = "ApproximateAgeOfOldestMessage"
  dimensions = {
    QueueName = aws_sqs_queue.this[each.key].name
  }

  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  datapoints_to_alarm = 5
  comparison_operator = "GreaterThanThreshold"
  threshold           = each.value.oldest_message_age_alarm_seconds
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions
}
