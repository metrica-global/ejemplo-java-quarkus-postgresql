# Every output is a map keyed by the LOGICAL name (e.g. "giftcards-inbox"), so
# callers never have to rebuild a physical name.

output "topic_arns" {
  description = "Topic ARNs keyed by logical topic name."
  value       = { for name, topic in aws_sns_topic.this : name => topic.arn }
}

output "queue_urls" {
  description = "Queue URLs keyed by logical queue name."
  value       = { for name, queue in aws_sqs_queue.this : name => queue.url }
}

output "queue_arns" {
  description = "Queue ARNs keyed by logical queue name."
  value       = { for name, queue in aws_sqs_queue.this : name => queue.arn }
}

output "queue_names" {
  description = "Physical queue names keyed by logical queue name (the QueueName dimension in CloudWatch)."
  value       = { for name, queue in aws_sqs_queue.this : name => queue.name }
}

output "dlq_urls" {
  description = "Dead-letter queue URLs keyed by the logical name of the queue they belong to."
  value       = { for name, queue in aws_sqs_queue.dlq : name => queue.url }
}

output "dlq_arns" {
  description = "Dead-letter queue ARNs keyed by the logical name of the queue they belong to."
  value       = { for name, queue in aws_sqs_queue.dlq : name => queue.arn }
}

output "kms_key_arns" {
  description = "ARN of the messaging KMS key as a list: one element, or empty when encryption is disabled. A list keeps its length known at plan time."
  value       = aws_kms_key.this[*].arn
}
