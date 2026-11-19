# modules/platform: the whole environment composition, planned against mocks.
#
# This is the composition envs/dev, envs/qa and envs/prod call. The tests check
# what the composition is responsible for:
#   - by default ONE service, the nucleo, serves every module of the application;
#   - a module listed in promoted_modules gets a service of its own, from the
#     same image, that receives only what that module needs;
#   - in every state of a promotion, and of the way back, the module's API
#     goes to a deployment that serves the module;
#   - a topology that makes no sense, and a change that breaks the database
#     connection budget, are rejected at plan.
#
# promotion.tftest.hcl applies the same states one after the other, to check
# what a plan alone cannot show: that moving between them replaces nothing.

mock_provider "aws" {
  source = "./mocks/aws"
}

# The random provider is only used by modules/generated-secrets, through
# EPHEMERAL resources, which the mocking framework of Terraform 1.16 cannot
# mock ("does not yet support ephemeral resource types"). So that no real
# provider runs here, the provider is mocked and the two calls to that module
# are replaced by their outputs: the ARNs of the secrets they would create.
# 123456789012 is the documentation account id.
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

  # Sized for the promotion of giftcards: 40 in the nucleo + 200 in its own service.
  modules = {
    lotes     = { db_pool_max = 10, db_connection_limit = 40 }
    giftcards = { db_pool_max = 10, db_connection_limit = 240 }
  }

  image_tag                      = "1.0.0"
  oidc_issuer                    = "https://keycloak.example.com/realms/zonacards"
  create_account_level_resources = true
  github_repository              = "example-org/zonacards"
}

# --- the default topology --------------------------------------------------------

run "by_default_one_service_serves_every_module" {
  command = plan

  module {
    source = "../modules/platform"
  }

  assert {
    condition     = length(module.promoted) == 0 && join(",", keys(output.services)) == "nucleo"
    error_message = "Without promoted_modules the environment must have exactly one service: the nucleo."
  }

  assert {
    condition     = module.nucleo.environment["ZC_MODULES"] == "giftcards,lotes" && join(",", output.services.nucleo.modules) == "giftcards,lotes"
    error_message = "The nucleo must serve every module of the application."
  }

  assert {
    condition     = module.nucleo.autoscaling == null && module.nucleo.max_task_count == 2 && module.nucleo.peak_task_count == 4
    error_message = "The nucleo of this test is a fixed service of 2 tasks, 4 at most during a rolling deployment."
  }

  assert {
    condition     = join(",", keys(aws_lb_target_group.service)) == "nucleo"
    error_message = "One service, one target group."
  }

  # One rule per module, each sending everything to the only service there is.
  assert {
    condition = alltrue([
      for name in ["giftcards", "lotes"] :
      [for tg in aws_lb_listener_rule.module[name].action[0].forward[0].target_group : tg.weight] == [100]
    ])
    error_message = "Every module's rule must forward all its traffic to the nucleo."
  }

  assert {
    condition = (
      one(aws_lb_listener_rule.module["lotes"].condition).path_pattern[0].values == toset(["/api/v1/lotes*"]) &&
      one(aws_lb_listener_rule.module["giftcards"].condition).path_pattern[0].values == toset(["/api/v1/giftcards*"]) &&
      aws_lb_listener_rule.module["lotes"].priority != aws_lb_listener_rule.module["giftcards"].priority
    )
    error_message = "Each module's rule must match that module's API paths, with its own priority."
  }

  assert {
    condition     = output.repository_url == "123456789012.dkr.ecr.us-east-1.amazonaws.com/zonacards/app"
    error_message = "The application has one image repository."
  }
}

run "the_nucleo_receives_what_every_module_needs" {
  command = plan

  module {
    source = "../modules/platform"
  }

  assert {
    condition = module.nucleo.environment_names == sort([
      "OTEL_SERVICE_NAME",
      "QUARKUS_OTEL_SDK_DISABLED",
      "ZC_CONSUMER_CONCURRENCY",
      "ZC_DB_GIFTCARDS_APP_USER",
      "ZC_DB_GIFTCARDS_POOL_MAX",
      "ZC_DB_LOTES_APP_USER",
      "ZC_DB_LOTES_POOL_MAX",
      "ZC_DB_URL",
      "ZC_ENV",
      "ZC_MODULES",
      "ZC_OIDC_AUDIENCE",
      "ZC_OIDC_ISSUER",
      "ZC_QUEUE_GIFTCARDS_GENERACION_URL",
      "ZC_QUEUE_GIFTCARDS_INBOX_URL",
      "ZC_QUEUE_LOTES_INBOX_URL",
      "ZC_TOPIC_GIFTCARDS_EVENTS_ARN",
      "ZC_TOPIC_LOTES_EVENTS_ARN",
    ])
    error_message = "The nucleo must receive exactly the contract variables of every module."
  }

  assert {
    condition = module.nucleo.secret_names == sort([
      "ZC_DB_GIFTCARDS_APP_PASSWORD",
      "ZC_DB_LOTES_APP_PASSWORD",
      "ZC_GIFTCARDS_CODIGO_HMAC_KEY",
      "ZC_GIFTCARDS_CODIGO_KEY",
    ])
    error_message = "The nucleo must receive the application password of every module and the keys of giftcards."
  }

  assert {
    condition = (
      module.nucleo.environment["ZC_DB_LOTES_APP_USER"] == "lotes_app" &&
      module.nucleo.environment["ZC_DB_GIFTCARDS_APP_USER"] == "giftcards_app" &&
      module.nucleo.environment["OTEL_SERVICE_NAME"] == "nucleo"
    )
    error_message = "Each module must connect with its own database role, also inside the nucleo."
  }

  # ONE migration task for the application, with the owner role of every
  # module; no owner password reaches a long-running container.
  assert {
    condition = (
      module.nucleo.migrate_task_definition_family == "zonacards-test-nucleo-migrate" &&
      output.migrate.task_definition_family == "zonacards-test-nucleo-migrate"
    )
    error_message = "The application must have one migration task definition."
  }

  assert {
    condition = (
      module.nucleo.migrate_environment_names == tolist(["ZC_DB_GIFTCARDS_OWNER_USER", "ZC_DB_LOTES_OWNER_USER", "ZC_DB_URL", "ZC_MODE"]) &&
      module.nucleo.migrate_secret_names == tolist(["ZC_DB_GIFTCARDS_OWNER_PASSWORD", "ZC_DB_LOTES_OWNER_PASSWORD"]) &&
      length([for name in module.nucleo.secret_names : name if strcontains(name, "OWNER")]) == 0
    )
    error_message = "The migration task must receive the database and the owner credentials of every module, and nothing else; the nucleo must receive no owner password."
  }

  assert {
    condition     = output.db_bootstrap.task_definition_family == "zonacards-test-db-bootstrap"
    error_message = "The environment must register the database bootstrap task definition."
  }
}

run "the_pipeline_is_told_what_to_deploy" {
  command = plan

  module {
    source = "../modules/platform"
  }

  assert {
    condition     = output.deploy_parameters == { deploy = "/zonacards/test/deploy", release = "/zonacards/test/release" }
    error_message = "The deploy contract must be published under /zonacards/<environment>/."
  }

  assert {
    condition = jsonencode(output.deploy_descriptor.services) == jsonencode([
      {
        modules                = "giftcards,lotes"
        name                   = "nucleo"
        service                = "zonacards-test-nucleo"
        task_definition_family = "zonacards-test-nucleo"
      },
    ])
    error_message = "With every module in the nucleo there is one service to deploy."
  }

  assert {
    condition     = output.deploy_descriptor.container == "app" && output.deploy_descriptor.repository == "zonacards/app"
    error_message = "The pipeline must be told the one repository and the one container name of the application."
  }

  # The parameter starts with the tag of the first image; the pipeline owns it afterwards.
  assert {
    condition     = aws_ssm_parameter.release.insecure_value == "1.0.0" && module.nucleo.first_image == "123456789012.dkr.ecr.us-east-1.amazonaws.com/zonacards/app:1.0.0"
    error_message = "A service must be created with the release recorded in the release parameter."
  }
}

run "an_otlp_endpoint_enables_the_exporter" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    otel_exporter_otlp_endpoint = "http://otel-collector.internal:4317"
  }

  assert {
    condition = (
      contains(module.nucleo.environment_names, "OTEL_EXPORTER_OTLP_ENDPOINT") &&
      !contains(module.nucleo.environment_names, "QUARKUS_OTEL_SDK_DISABLED")
    )
    error_message = "With an OTLP endpoint the SDK must stay enabled and receive the endpoint."
  }
}

run "plain_http_must_be_asked_for_explicitly" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    load_balancer = {} # no certificate and no allow_plain_http
  }

  expect_failures = [var.load_balancer]
}

# --- a promoted module -------------------------------------------------------------

run "a_promoted_module_gets_its_own_service_from_the_same_image" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    promoted_modules = {
      giftcards = {
        autoscaling = {
          min = 2
          max = 10
          queue = {
            name                    = "giftcards-generacion"
            backlog_per_task_target = 10
          }
        }
      }
    }
  }

  assert {
    condition     = join(",", keys(output.services)) == "giftcards,nucleo" && join(",", output.services.giftcards.modules) == "giftcards"
    error_message = "A promoted module must have a service of its own that serves that module."
  }

  assert {
    condition     = module.promoted["giftcards"].first_image == module.nucleo.first_image
    error_message = "The promoted module's service must run the same image as the nucleo."
  }

  # Only this service scales.
  assert {
    condition = (
      module.promoted["giftcards"].autoscaling == {
        min_capacity = 2
        max_capacity = 10
        policies     = ["zonacards-test-giftcards-cpu", "zonacards-test-giftcards-backlog-per-task"]
      } &&
      module.nucleo.autoscaling == null
    )
    error_message = "giftcards must scale from 2 to 10 tasks with the CPU and backlog-per-task policies, and the nucleo must stay fixed."
  }

  # It receives what giftcards needs and nothing of lotes.
  assert {
    condition = module.promoted["giftcards"].environment_names == sort([
      "OTEL_SERVICE_NAME",
      "QUARKUS_OTEL_SDK_DISABLED",
      "ZC_CONSUMER_CONCURRENCY",
      "ZC_DB_GIFTCARDS_APP_USER",
      "ZC_DB_GIFTCARDS_POOL_MAX",
      "ZC_DB_URL",
      "ZC_ENV",
      "ZC_MODULES",
      "ZC_OIDC_AUDIENCE",
      "ZC_OIDC_ISSUER",
      "ZC_QUEUE_GIFTCARDS_GENERACION_URL",
      "ZC_QUEUE_GIFTCARDS_INBOX_URL",
      "ZC_TOPIC_GIFTCARDS_EVENTS_ARN",
    ])
    error_message = "The service of giftcards must receive exactly the contract variables of giftcards."
  }

  assert {
    condition = module.promoted["giftcards"].secret_names == sort([
      "ZC_DB_GIFTCARDS_APP_PASSWORD",
      "ZC_GIFTCARDS_CODIGO_HMAC_KEY",
      "ZC_GIFTCARDS_CODIGO_KEY",
    ])
    error_message = "The service of giftcards must receive the secrets of giftcards and no other."
  }

  assert {
    condition = (
      module.promoted["giftcards"].environment["ZC_MODULES"] == "giftcards" &&
      module.promoted["giftcards"].environment["OTEL_SERVICE_NAME"] == "giftcards" &&
      module.promoted["giftcards"].migrate_task_definition_family == null
    )
    error_message = "The service must serve only its module, and must not declare a second migration task."
  }

  # Promoted, with no transition: the nucleo lets the module go and the
  # module's API goes to its service.
  assert {
    condition     = module.nucleo.environment["ZC_MODULES"] == "lotes"
    error_message = "The nucleo must stop serving a promoted module."
  }

  assert {
    condition     = output.routes.giftcards.weights == { nucleo = 0, giftcards = 100 } && output.routes.lotes.weights == { nucleo = 100 }
    error_message = "The traffic of giftcards must go to its service, and the traffic of lotes to the nucleo."
  }

  # The nucleo still HOLDS what giftcards needs: nothing is taken away from
  # tasks that are still running the previous configuration, and the module
  # can come back with a change of ZC_MODULES alone.
  assert {
    condition = (
      contains(module.nucleo.environment_names, "ZC_QUEUE_GIFTCARDS_GENERACION_URL") &&
      contains(module.nucleo.secret_names, "ZC_GIFTCARDS_CODIGO_KEY")
    )
    error_message = "A promotion must not remove anything from the nucleo except the module from ZC_MODULES."
  }

  # Promoted services first, the nucleo last.
  assert {
    condition = jsonencode(output.deploy_descriptor.services) == jsonencode([
      {
        modules                = "giftcards"
        name                   = "giftcards"
        service                = "zonacards-test-giftcards"
        task_definition_family = "zonacards-test-giftcards"
      },
      {
        modules                = "lotes"
        name                   = "nucleo"
        service                = "zonacards-test-nucleo"
        task_definition_family = "zonacards-test-nucleo"
      },
    ])
    error_message = "The pipeline must deploy the service of the promoted module first and the nucleo last."
  }
}

run "with_traffic_in_the_nucleo_the_new_service_is_attached_and_receives_nothing" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    promoted_modules = {
      giftcards = { transition = "traffic-in-nucleo" }
    }
  }

  assert {
    condition = (
      module.promoted["giftcards"].environment["ZC_MODULES"] == "giftcards" &&
      module.nucleo.environment["ZC_MODULES"] == "giftcards,lotes"
    )
    error_message = "In this state both the service and the nucleo must serve the module."
  }

  assert {
    condition     = output.routes.giftcards.weights == { nucleo = 100, giftcards = 0 }
    error_message = "The module's API must go to the nucleo; the service is attached with weight 0."
  }

  assert {
    condition     = sort([for tg in aws_lb_listener_rule.module["giftcards"].action[0].forward[0].target_group : tostring(tg.weight)]) == tolist(["0", "100"])
    error_message = "The module's rule must carry both target groups, one of them with weight 0."
  }

  assert {
    condition     = output.topology.placement.giftcards == { own_service = true, in_nucleo = true, routed_to = "nucleo" }
    error_message = "The topology output must describe the state in which the module is served twice."
  }
}

run "with_traffic_in_the_service_the_nucleo_serves_the_module_again" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    promoted_modules = {
      giftcards = { transition = "traffic-in-service" }
    }
  }

  assert {
    condition = (
      module.promoted["giftcards"].environment["ZC_MODULES"] == "giftcards" &&
      module.nucleo.environment["ZC_MODULES"] == "giftcards,lotes"
    )
    error_message = "In this state the nucleo must serve the module again and the service must keep serving it."
  }

  assert {
    condition     = output.routes.giftcards.weights == { nucleo = 0, giftcards = 100 }
    error_message = "The module's API must stay in its service: the nucleo only serves the module again after its next deployment."
  }
}

# --- topologies that are rejected ----------------------------------------------------

run "rejects_a_module_the_application_does_not_have" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    promoted_modules = {
      redencion = {}
    }
  }

  expect_failures = [var.promoted_modules]
}

run "rejects_an_unknown_transition" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    promoted_modules = {
      giftcards = { transition = "half-way" }
    }
  }

  expect_failures = [var.promoted_modules]
}

run "rejects_a_nucleo_left_without_modules" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    modules = {
      lotes     = { db_connection_limit = 80 }
      giftcards = { db_connection_limit = 80 }
    }
    promoted_modules = {
      lotes     = {}
      giftcards = {}
    }
  }

  expect_failures = [terraform_data.topology]
}

run "rejects_sizing_for_a_module_that_does_not_exist" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    modules = {
      lotes = { db_connection_limit = 40 }
    }
  }

  expect_failures = [var.modules]
}

# --- connection budget ---------------------------------------------------------------

run "budget_counts_the_nucleo_for_every_module" {
  command = plan

  module {
    source = "../modules/platform"
  }

  assert {
    condition = terraform_data.db_connection_budget.input == {
      modules = {
        giftcards = { pool_max = 10, nucleo_peak_tasks = 4, own_service_peak_tasks = 0, peak_connections = 40, connection_limit = 240 }
        lotes     = { pool_max = 10, nucleo_peak_tasks = 4, own_service_peak_tasks = 0, peak_connections = 40, connection_limit = 40 }
      }
      reserved_connections = 30
      committed            = 310
      max_connections      = 400
    }
    error_message = "With every module in the nucleo, each role must hold the nucleo's peak tasks x pool."
  }
}

run "budget_adds_the_own_service_of_a_promoted_module" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    promoted_modules = {
      giftcards = {
        autoscaling = { min = 2, max = 10 }
        transition  = "traffic-in-nucleo"
      }
    }
  }

  # 4 nucleo tasks + 20 of its own service at the peak of a deployment.
  assert {
    condition = terraform_data.db_connection_budget.input.modules.giftcards == {
      pool_max = 10, nucleo_peak_tasks = 4, own_service_peak_tasks = 20, peak_connections = 240, connection_limit = 240
    }
    error_message = "A promoted module's role must hold the nucleo AND its own service: both can serve it at the same time."
  }

  assert {
    condition     = terraform_data.db_connection_budget.input.modules.lotes.peak_connections == 40
    error_message = "The budget of a module that is not promoted must not change."
  }
}

run "budget_rejects_a_promotion_the_role_limit_was_not_sized_for" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    # The limit only covers the nucleo: 4 tasks x 10.
    modules = {
      lotes     = { db_pool_max = 10, db_connection_limit = 40 }
      giftcards = { db_pool_max = 10, db_connection_limit = 40 }
    }
    promoted_modules = {
      giftcards = {
        autoscaling = { min = 2, max = 10 }
        transition  = "traffic-in-nucleo"
      }
    }
  }

  expect_failures = [terraform_data.db_connection_budget]
}

run "budget_rejects_more_tasks_than_the_role_limit_allows" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    # 40 in the nucleo + 15 tasks x 2 x 10 = 340 > 240.
    promoted_modules = {
      giftcards = {
        autoscaling = { min = 2, max = 15 }
      }
    }
  }

  expect_failures = [terraform_data.db_connection_budget]
}

run "budget_rejects_a_pool_larger_than_the_role_limit_allows" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    # A fixed service is checked too: 2 tasks x 2 x 30 connections = 120 > 40.
    modules = {
      lotes     = { db_pool_max = 30, db_connection_limit = 40 }
      giftcards = { db_pool_max = 10, db_connection_limit = 240 }
    }
  }

  expect_failures = [terraform_data.db_connection_budget]
}

run "budget_rejects_role_limits_above_max_connections" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    # Each module fits in its own limit, but 40 + 240 + 30 > 300.
    database = {
      instance_class  = "db.t4g.medium"
      max_connections = 300
    }
  }

  expect_failures = [terraform_data.db_connection_budget]
}
