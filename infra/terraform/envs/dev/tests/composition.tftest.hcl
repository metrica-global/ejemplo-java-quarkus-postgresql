# Evidence, per environment, of what the environment deploys.
#
# Runs against this root module with its real terraform.tfvars and a mocked
# AWS provider: no credentials, no API calls, nothing created.
#
#   terraform init -backend=false
#   terraform test                                         # the committed topology
#   terraform test -var-file=promocion-giftcards.tfvars    # with the example promotion
#
# The same assertions hold in both: they are written against the variables,
# so they describe whatever topology the variables ask for. With the committed
# tfvars that is one service for every module; with the example file, the
# nucleo plus an autoscaled service for giftcards.
#
# This file is identical in envs/dev, envs/qa and envs/prod.

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
  # The only value not taken from the tfvars. qa and prod refuse to plan
  # without an ACM certificate, and the committed tfvars carry no real ARN.
  # These tests are about the topology, so they accept the plain HTTP listener.
  alb_allow_plain_http = true
}

run "one_service_per_promoted_module_and_the_nucleo" {
  command = plan

  assert {
    condition     = sort(keys(output.services)) == sort(concat(["nucleo"], keys(var.promoted_modules)))
    error_message = "The environment must have the nucleo and exactly one service per promoted module."
  }

  assert {
    condition     = output.autoscaling.nucleo == null && output.services.nucleo.max_task_count == var.nucleo_tasks
    error_message = "The nucleo must be a fixed service of nucleo_tasks tasks: no scalable target, no scaling policy."
  }

  # Every service runs the one image of the application.
  assert {
    condition     = length(distinct([for svc in values(output.services) : svc.container_name])) == 1
    error_message = "Every service must run the same container of the same image."
  }

  assert {
    condition = alltrue([
      for name, cfg in var.promoted_modules : cfg.autoscaling == null ? output.autoscaling[name] == null : (
        output.autoscaling[name].min_capacity == cfg.autoscaling.min &&
        output.autoscaling[name].max_capacity == cfg.autoscaling.max &&
        length(output.autoscaling[name].policies) == (cfg.autoscaling.queue == null ? 1 : 2)
      )
    ])
    error_message = "A promoted module must scale as its entry in promoted_modules says: its range, and one policy for CPU plus one for its queue."
  }
}

run "every_module_is_served_and_routed_to_a_deployment_that_serves_it" {
  command = plan

  # Somebody serves each module...
  assert {
    condition = alltrue([
      for name, placement in output.topology.placement : placement.in_nucleo || placement.own_service
    ])
    error_message = "Every module of the application must be served by the nucleo or by a service of its own."
  }

  # ...and its API goes to a deployment that does: this is what makes every
  # stage of a promotion safe to stay in.
  assert {
    condition = alltrue([
      for name, placement in output.topology.placement :
      placement.routed_to == "nucleo" ? placement.in_nucleo : placement.own_service
    ])
    error_message = "A module's API must never be routed to a deployment that does not serve the module."
  }

  assert {
    condition = alltrue([
      for name, route in output.routes : sum(values(route.weights)) == 100 && route.weights[output.topology.placement[name].routed_to] == 100
    ])
    error_message = "Each module's rule must send all its traffic to one target group."
  }

  # The pipeline deploys every service, the nucleo last.
  assert {
    condition = (
      length(output.deploy_descriptor.services) == length(output.services) &&
      output.deploy_descriptor.services[length(output.deploy_descriptor.services) - 1].name == "nucleo"
    )
    error_message = "The deploy contract must list every service, with the nucleo last."
  }
}

run "connection_budget_holds" {
  command = plan

  assert {
    condition = alltrue([
      for module in values(output.db_connection_budget.modules) : module.peak_connections <= module.connection_limit
    ])
    error_message = "Every module must fit in the CONNECTION LIMIT of its role with every deployment that can serve it at its peak task count."
  }

  assert {
    condition     = output.db_connection_budget.committed <= output.db_connection_budget.max_connections
    error_message = "The role limits plus the reserve must fit in max_connections."
  }
}
