# ECS cluster shared by every service of the environment.

locals {
  cluster_name = "${var.name_prefix}-cluster"
}

# Container Insights writes its performance events to this group. AWS would
# create it on first use with its own defaults; creating it here puts its
# retention under our control.
resource "aws_cloudwatch_log_group" "container_insights" {
  name              = "/aws/ecs/containerinsights/${local.cluster_name}/performance"
  retention_in_days = var.log_retention_days
}

resource "aws_ecs_cluster" "this" {
  name = local.cluster_name

  # Container Insights is not optional in this design: the backlog-per-task
  # scaling policy of modules/ecs-service divides the queue depth by the
  # RunningTaskCount metric that only Container Insights publishes.
  setting {
    name  = "containerInsights"
    value = var.container_insights
  }

  depends_on = [aws_cloudwatch_log_group.container_insights]
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}
