# modules/platform: a promotion and its way back, APPLIED stage by stage.
#
#   everything in the nucleo
#     -> giftcards with transition = "traffic-in-nucleo"    the service exists, nothing else moves
#     -> giftcards promoted                                 the API and the ownership move
#     -> giftcards with transition = "traffic-in-service"   the nucleo is told to serve it again
#     -> giftcards with transition = "traffic-in-nucleo"    the API goes back
#     -> everything in the nucleo                           the service is removed
#
# The runs share one state, so each one starts from what the previous one left,
# like successive `terraform apply` on an environment. The provider is mocked:
# nothing is created anywhere.
#
# What these runs can show, and what they cannot:
#
#   - They show that the RESOURCES that carry traffic keep their address from
#     the first stage to the last: the listener rule of the module and the
#     service of the nucleo. A resource whose address depended on the topology
#     would be destroyed and created again here, and its mocked ARN would
#     change. This is the part that depends on how this module is written.
#   - They show which ARGUMENTS change at each stage and which do not. In the
#     listener rule, only the forward action; in the nucleo, only ZC_MODULES.
#   - They cannot show what the AWS provider does with a changed argument, nor
#     whether the order Terraform applies the changes in is one AWS accepts: a
#     mocked provider plans every change as an update and accepts every call.
#     That is what scripts/tf-ensayo-promocion.sh is for: the same stages, with
#     the real provider, against an emulator. It is the rehearsal that found
#     that a target group must be taken out of its rule before it is deleted
#     (see the lifecycle block in modules/platform/routing.tf).

mock_provider "aws" {
  source = "./mocks/aws"
}

mock_provider "random" {}

override_module {
  target          = module.database.module.role_passwords
  override_during = plan
  outputs = {
    secret_arns = {
      "lotes-owner"     = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-test-lotes-db-owner-password-AAAAAA"
      "lotes-app"       = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-test-lotes-db-app-password-AAAAAA"
      "giftcards-owner" = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-test-giftcards-db-owner-password-AAAAAA"
      "giftcards-app"   = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-test-giftcards-db-app-password-AAAAAA"
    }
  }
}

override_module {
  target          = module.giftcards_keys
  override_during = plan
  outputs = {
    secret_arns = {
      "codigo-key"      = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-test-giftcards-codigo-key-AAAAAA"
      "codigo-hmac-key" = "arn:aws:secretsmanager:us-east-1:123456789012:secret:zonacards-test-giftcards-codigo-hmac-key-AAAAAA"
    }
  }
}

variables {
  environment = "test"

  network = {
    vpc_cidr = "10.99.0.0/16"
  }

  load_balancer = {
    allow_plain_http = true
  }

  database = {
    instance_class  = "db.t4g.medium"
    max_connections = 400
  }

  nucleo = {
    desired_count = 2
  }

  modules = {
    lotes     = { db_pool_max = 10, db_connection_limit = 40 }
    giftcards = { db_pool_max = 10, db_connection_limit = 240 }
  }

  image_tag                      = "1.0.0"
  oidc_issuer                    = "https://keycloak.example.com/realms/zonacards"
  create_account_level_resources = true
  github_repository              = "example-org/zonacards"
}

run "everything_in_the_nucleo" {
  command = apply

  module {
    source = "../modules/platform"
  }

  assert {
    condition     = join(",", keys(output.services)) == "nucleo" && output.routes.giftcards.weights == { nucleo = 100 }
    error_message = "The environment must start with one service that receives the traffic of every module."
  }

  # A check on the method of this file: the ARN of a rule is random for every
  # rule that is created, so a rule that were created again in a later run
  # would show a different one.
  assert {
    condition     = output.routes.giftcards.listener_rule_arn != output.routes.lotes.listener_rule_arn
    error_message = "The mocked ARN of a listener rule must be unique per created rule, or the runs below prove nothing."
  }
}

# --- promote: expand -------------------------------------------------------------

run "the_service_is_added_and_nothing_else_moves" {
  command = apply

  module {
    source = "../modules/platform"
  }

  variables {
    promoted_modules = {
      giftcards = {
        autoscaling = { min = 2, max = 10, queue = { name = "giftcards-generacion", backlog_per_task_target = 10 } }
        transition  = "traffic-in-nucleo"
      }
    }
  }

  # The service is new and runs the release that is deployed.
  assert {
    condition = (
      join(",", keys(output.services)) == "giftcards,nucleo" &&
      module.promoted["giftcards"].first_image == "123456789012.dkr.ecr.us-east-1.amazonaws.com/zonacards/app:1.0.0"
    )
    error_message = "Promoting must create the module's service from the image that is deployed."
  }

  # The nucleo is exactly what it was: same service, same target group, same
  # modules. Nothing has to be redeployed at this stage.
  assert {
    condition     = output.services.nucleo == run.everything_in_the_nucleo.services.nucleo
    error_message = "The first stage of a promotion must not change the nucleo in any way."
  }

  # The module's rule is the SAME rule, with a second target group that
  # receives nothing yet.
  assert {
    condition = (
      output.routes.giftcards.listener_rule_arn == run.everything_in_the_nucleo.routes.giftcards.listener_rule_arn &&
      output.routes.giftcards.priority == run.everything_in_the_nucleo.routes.giftcards.priority &&
      output.routes.giftcards.path_patterns == run.everything_in_the_nucleo.routes.giftcards.path_patterns &&
      output.routes.giftcards.weights == { nucleo = 100, giftcards = 0 }
    )
    error_message = "The rule of the module must stay the same resource, with the same priority and paths, and keep sending everything to the nucleo."
  }

  assert {
    condition     = output.routes.lotes == run.everything_in_the_nucleo.routes.lotes
    error_message = "Promoting a module must not touch the rule of another module."
  }
}

# --- promote: contract -----------------------------------------------------------

run "promoted_the_api_moves_and_the_nucleo_releases_the_module" {
  command = apply

  module {
    source = "../modules/platform"
  }

  variables {
    promoted_modules = {
      giftcards = {
        autoscaling = { min = 2, max = 10, queue = { name = "giftcards-generacion", backlog_per_task_target = 10 } }
      }
    }
  }

  assert {
    condition = (
      output.routes.giftcards.listener_rule_arn == run.everything_in_the_nucleo.routes.giftcards.listener_rule_arn &&
      output.routes.giftcards.weights == { nucleo = 0, giftcards = 100 }
    )
    error_message = "The same rule must now send the module's traffic to its service."
  }

  # The service created in the previous stage is the one that takes over.
  assert {
    condition     = output.services.giftcards == run.the_service_is_added_and_nothing_else_moves.services.giftcards
    error_message = "The second stage must not change the service the first stage created."
  }

  # The nucleo is the same service; what changes is the modules its NEXT
  # deployment will serve.
  assert {
    condition = (
      output.services.nucleo.service_arn == run.everything_in_the_nucleo.services.nucleo.service_arn &&
      output.services.nucleo.target_group_arn == run.everything_in_the_nucleo.services.nucleo.target_group_arn &&
      module.nucleo.environment["ZC_MODULES"] == "lotes"
    )
    error_message = "The nucleo must stay the same service and be configured to stop serving the module."
  }

  assert {
    condition     = output.routes.lotes == run.everything_in_the_nucleo.routes.lotes
    error_message = "Promoting a module must not touch the rule of another module."
  }
}

# --- demote: the nucleo serves the module again -----------------------------------

run "the_nucleo_is_told_to_serve_the_module_again" {
  command = apply

  module {
    source = "../modules/platform"
  }

  variables {
    promoted_modules = {
      giftcards = {
        autoscaling = { min = 2, max = 10, queue = { name = "giftcards-generacion", backlog_per_task_target = 10 } }
        transition  = "traffic-in-service"
      }
    }
  }

  assert {
    condition = (
      module.nucleo.environment["ZC_MODULES"] == "giftcards,lotes" &&
      output.routes.giftcards.weights == { nucleo = 0, giftcards = 100 } &&
      output.services.giftcards == run.the_service_is_added_and_nothing_else_moves.services.giftcards
    )
    error_message = "The first step back must configure the nucleo to serve the module again while the API and the service stay as they are."
  }
}

# --- demote: the API goes back ----------------------------------------------------------

run "the_api_goes_back_while_the_service_still_exists" {
  command = apply

  module {
    source = "../modules/platform"
  }

  variables {
    promoted_modules = {
      giftcards = {
        autoscaling = { min = 2, max = 10, queue = { name = "giftcards-generacion", backlog_per_task_target = 10 } }
        transition  = "traffic-in-nucleo"
      }
    }
  }

  # Exactly the state the promotion went through: the route changes in an
  # apply that removes nothing, so the service is still there if the nucleo
  # turns out not to be ready.
  assert {
    condition = (
      output.routes == run.the_service_is_added_and_nothing_else_moves.routes &&
      output.services == run.the_service_is_added_and_nothing_else_moves.services &&
      output.topology == run.the_service_is_added_and_nothing_else_moves.topology
    )
    error_message = "Moving the API back must leave the environment exactly as it was after the first step of the promotion."
  }
}

# --- demote: the service is removed -------------------------------------------------

run "back_in_the_nucleo_nothing_is_left_behind" {
  command = apply

  module {
    source = "../modules/platform"
  }

  # promoted_modules is not set: the default, {}.

  assert {
    condition     = output.services == run.everything_in_the_nucleo.services
    error_message = "After the way back the environment must have the same single service it started with."
  }

  assert {
    condition     = output.routes == run.everything_in_the_nucleo.routes
    error_message = "After the way back every rule must be the same resource, forwarding as at the start."
  }

  assert {
    condition = (
      output.topology == run.everything_in_the_nucleo.topology &&
      output.deploy_descriptor == run.everything_in_the_nucleo.deploy_descriptor &&
      output.db_connection_budget == run.everything_in_the_nucleo.db_connection_budget
    )
    error_message = "After the way back the topology, the deploy contract and the connection budget must be the initial ones."
  }

  assert {
    condition     = length(module.promoted) == 0 && join(",", keys(aws_lb_target_group.service)) == "nucleo"
    error_message = "The service of the module and its target group must be gone."
  }
}
