# Values for the RESOURCES of the mocked AWS provider, for the runs that apply.
#
# A mocked apply invents a random string for every computed attribute. The
# provider still validates its arguments, so an attribute that is passed on to
# an argument that must look like an ARN needs a value with that shape. Every
# instance of a type listed here gets the SAME value, so a test cannot tell two
# of them apart by these attributes; the tests that follow a resource through
# several applies do it with types that are not listed here, whose ARN is
# random and therefore changes if the resource is created again.

mock_resource "aws_iam_role" {
  defaults = {
    arn = "arn:aws:iam::123456789012:role/mocked-by-terraform-test"
  }
}

mock_resource "aws_lb" {
  defaults = {
    arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:loadbalancer/app/mocked/0000000000000000"
  }
}

mock_resource "aws_lb_listener" {
  defaults = {
    arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:listener/app/mocked/0000000000000000/0000000000000000"
  }
}

mock_resource "aws_lb_target_group" {
  defaults = {
    arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/mocked/0000000000000000"
  }
}

mock_resource "aws_sns_topic" {
  defaults = {
    arn = "arn:aws:sns:us-east-1:123456789012:mocked"
  }
}

mock_resource "aws_sqs_queue" {
  defaults = {
    arn = "arn:aws:sqs:us-east-1:123456789012:mocked"
  }
}

mock_resource "aws_kms_key" {
  defaults = {
    arn = "arn:aws:kms:us-east-1:123456789012:key/00000000-0000-0000-0000-000000000000"
  }
}

mock_resource "aws_secretsmanager_secret" {
  defaults = {
    arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:mocked-AAAAAA"
  }
}

mock_resource "aws_ecs_cluster" {
  defaults = {
    arn = "arn:aws:ecs:us-east-1:123456789012:cluster/mocked"
  }
}

mock_resource "aws_ecs_task_definition" {
  defaults = {
    arn = "arn:aws:ecs:us-east-1:123456789012:task-definition/mocked:1"
  }
}

# The master password is managed by RDS in Secrets Manager; the bootstrap task
# reads the ARN of that secret from the instance.
mock_resource "aws_db_instance" {
  defaults = {
    arn = "arn:aws:rds:us-east-1:123456789012:db:mocked"
    master_user_secret = [
      {
        kms_key_id    = "arn:aws:kms:us-east-1:123456789012:key/00000000-0000-0000-0000-000000000000"
        secret_arn    = "arn:aws:secretsmanager:us-east-1:123456789012:secret:rds!db-mocked-AAAAAA"
        secret_status = "active"
      },
    ]
  }
}
