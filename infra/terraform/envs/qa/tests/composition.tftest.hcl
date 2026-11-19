# Evidence, per environment, that only one of the two services scales.
#
# Runs against this root module with its real terraform.tfvars and a mocked
# AWS provider: no credentials, no API calls, nothing created.
#
#   terraform init -backend=false && terraform test
#
# This file is identical in envs/dev, envs/qa and envs/prod: the expected
# values are read from the variables of each environment.

mock_provider "aws" {
  source = "../../tests/mocks/aws"
}

# The random provider is only used by modules/generated-secrets, through
# EPHEMERAL resources, which the mocking framework of Terraform 1.16 cannot
# mock. So that no real provider runs here, the provider is mocked and the two
# calls to that module are replaced by their outputs (see
# ../../tests/platform.tftest.hcl). 123456789012 is the documentation account id.
mock_provider "random" {}

override_module {
  target          = module.zonacards.module.database.module.role_passwords
  override_during = plan
  outputs = {
    secret_arns = {
      "lotes-owner"     = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-lotes-db-owner-password-AAAAAA"
      "lotes-app"       = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-lotes-db-app-password-AAAAAA"
      "giftcards-owner" = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-giftcards-db-owner-password-AAAAAA"
      "giftcards-app"   = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-giftcards-db-app-password-AAAAAA"
    }
  }
}

override_module {
  target          = module.zonacards.module.giftcards_keys
  override_during = plan
  outputs = {
    secret_arns = {
      "codigo-key"      = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-giftcards-codigo-key-AAAAAA"
      "codigo-hmac-key" = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-giftcards-codigo-hmac-key-AAAAAA"
    }
  }
}

variables {
  # The only value not taken from terraform.tfvars. qa and prod refuse to plan
  # without an ACM certificate, and the committed tfvars carry no real ARN.
  # These tests are about scaling, so they accept the plain HTTP listener.
  alb_allow_plain_http = true
}

run "only_giftcards_scales" {
  command = plan

  assert {
    condition     = output.autoscaling.lotes == null
    error_message = "lotes must be a fixed service: no scalable target, no scaling policy."
  }

  assert {
    condition     = output.services.lotes.max_task_count == var.lotes_tasks
    error_message = "lotes must run exactly lotes_tasks tasks."
  }

  assert {
    condition     = output.autoscaling.giftcards != null
    error_message = "giftcards must be autoscaled."
  }

  assert {
    condition = (
      output.autoscaling.giftcards.min_capacity == var.giftcards_min_tasks &&
      output.autoscaling.giftcards.max_capacity == var.giftcards_max_tasks
    )
    error_message = "giftcards must scale between giftcards_min_tasks and giftcards_max_tasks."
  }

  assert {
    condition     = length(output.autoscaling.giftcards.policies) == 2
    error_message = "giftcards must have two scaling policies: CPU and backlog per task."
  }
}

run "connection_budget_holds" {
  command = plan

  assert {
    condition = alltrue([
      for svc in values(output.db_connection_budget.services) : svc.peak_connections <= svc.connection_limit
    ])
    error_message = "Every service must fit in the CONNECTION LIMIT of its role at peak task count."
  }

  assert {
    condition     = output.db_connection_budget.committed <= output.db_connection_budget.max_connections
    error_message = "The role limits plus the reserve must fit in max_connections."
  }
}
