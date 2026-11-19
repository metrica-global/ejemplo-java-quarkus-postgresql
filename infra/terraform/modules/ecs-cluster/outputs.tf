output "cluster_arn" {
  description = "ARN of the ECS cluster."
  value       = aws_ecs_cluster.this.arn
}

output "cluster_name" {
  description = "Name of the ECS cluster (the ClusterName dimension in CloudWatch)."
  value       = aws_ecs_cluster.this.name
}

output "alb_arn" {
  description = "ARN of the load balancer."
  value       = aws_lb.this.arn
}

output "alb_dns_name" {
  description = "DNS name of the load balancer."
  value       = aws_lb.this.dns_name
}

output "alb_zone_id" {
  description = "Route 53 zone ID of the load balancer, for an alias record."
  value       = aws_lb.this.zone_id
}

output "alb_security_group_id" {
  description = "Security group of the load balancer. Services allow their container port from it."
  value       = aws_security_group.alb.id
}

output "listener_arn" {
  description = "Listener the services attach their path rules to: HTTPS when a certificate was given, HTTP otherwise."
  value       = local.https_enabled ? aws_lb_listener.https[0].arn : aws_lb_listener.http.arn
}

output "base_url" {
  description = "Base URL of the API as seen from outside, using the load balancer's own DNS name."
  value       = "${local.https_enabled ? "https" : "http"}://${aws_lb.this.dns_name}"
}
