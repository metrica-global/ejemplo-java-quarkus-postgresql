# Maps keyed by logical name, e.g. queue_urls["giftcards-generacion"].

output "topic_arns" {
  description = "Topic ARNs keyed by logical topic name."
  value       = module.messaging.topic_arns
}

output "queue_urls" {
  description = "Queue URLs keyed by logical queue name."
  value       = module.messaging.queue_urls
}

output "queue_arns" {
  description = "Queue ARNs keyed by logical queue name."
  value       = module.messaging.queue_arns
}

output "dlq_urls" {
  description = "Dead-letter queue URLs keyed by the logical name of their queue."
  value       = module.messaging.dlq_urls
}
