# modules/platform: the whole environment composition, planned against mocks.
#
# This is the composition envs/dev, envs/qa and envs/prod call. The tests check
# what the composition is responsible for:
#   - only the service that declares `autoscaling` scales;
#   - each container receives exactly the variables and secrets of the contract;
#   - a plain HTTP load balancer has to be asked for explicitly;
#   - a change that breaks the database connection budget is rejected at plan.

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

  lotes = {
    desired_count       = 2
    db_pool_max         = 10
    db_connection_limit = 40
  }

  giftcards = {
    db_pool_max         = 10
    db_connection_limit = 200
    autoscaling = {
      min = 2
      max = 10
      queue = {
        name                    = "giftcards-generacion"
        backlog_per_task_target = 10
      }
    }
  }

  image_tag                      = "test"
  oidc_issuer                    = "https://keycloak.example.com/realms/zonacards"
  create_account_level_resources = true
  github_repository              = "example-org/zonacards"
}

run "only_the_service_that_declares_autoscaling_scales" {
  command = plan

  module {
    source = "../modules/platform"
  }

  assert {
    condition     = module.lotes.autoscaling == null
    error_message = "lotes declares no autoscaling: it must have no scalable target and no policy."
  }

  assert {
    condition     = module.lotes.max_task_count == 2 && module.lotes.peak_task_count == 4
    error_message = "lotes must run 2 tasks, 4 at most during a rolling deployment."
  }

  assert {
    condition = module.giftcards.autoscaling == {
      min_capacity = 2
      max_capacity = 10
      policies     = ["zonacards-test-giftcards-cpu", "zonacards-test-giftcards-backlog-per-task"]
    }
    error_message = "giftcards must scale from 2 to 10 tasks with the CPU and backlog-per-task policies."
  }

  assert {
    condition     = module.giftcards.max_task_count == 10 && module.giftcards.peak_task_count == 20
    error_message = "giftcards may run 10 tasks, 20 at most during a rolling deployment."
  }
}

run "containers_receive_exactly_the_contract" {
  command = plan

  module {
    source = "../modules/platform"
  }

  assert {
    condition = module.lotes.environment_names == sort([
      "QUARKUS_OTEL_SDK_DISABLED",
      "ZC_CONSUMER_CONCURRENCY",
      "ZC_DB_APP_USER",
      "ZC_DB_POOL_MAX",
      "ZC_DB_URL",
      "ZC_ENV",
      "ZC_OIDC_AUDIENCE",
      "ZC_OIDC_ISSUER",
      "ZC_QUEUE_LOTES_INBOX_URL",
      "ZC_TOPIC_LOTES_EVENTS_ARN",
    ])
    error_message = "lotes must receive exactly its contract variables."
  }

  assert {
    condition     = module.lotes.secret_names == tolist(["ZC_DB_APP_PASSWORD"])
    error_message = "lotes must receive only the password of its application role."
  }

  assert {
    condition = module.giftcards.environment_names == sort([
      "QUARKUS_OTEL_SDK_DISABLED",
      "ZC_CONSUMER_CONCURRENCY",
      "ZC_DB_APP_USER",
      "ZC_DB_POOL_MAX",
      "ZC_DB_URL",
      "ZC_ENV",
      "ZC_OIDC_AUDIENCE",
      "ZC_OIDC_ISSUER",
      "ZC_QUEUE_GIFTCARDS_GENERACION_URL",
      "ZC_QUEUE_GIFTCARDS_INBOX_URL",
      "ZC_TOPIC_GIFTCARDS_EVENTS_ARN",
    ])
    error_message = "giftcards must receive exactly its contract variables."
  }

  assert {
    condition = module.giftcards.secret_names == sort([
      "ZC_DB_APP_PASSWORD",
      "ZC_GIFTCARDS_CODIGO_HMAC_KEY",
      "ZC_GIFTCARDS_CODIGO_KEY",
    ])
    error_message = "giftcards must receive its application password and its two code keys."
  }

  assert {
    condition = (
      module.lotes.migrate_task_definition_family == "zonacards-test-lotes-migrate" &&
      module.giftcards.migrate_task_definition_family == "zonacards-test-giftcards-migrate"
    )
    error_message = "Each service must have a <service>-migrate task definition."
  }

  # The migration task needs the database and nothing else: no OIDC, no
  # messaging. And the owner's password reaches no service container.
  assert {
    condition = alltrue([
      for svc in [module.lotes, module.giftcards] :
      svc.migrate_environment_names == tolist(["ZC_DB_OWNER_USER", "ZC_DB_URL", "ZC_MODE"]) &&
      svc.migrate_secret_names == tolist(["ZC_DB_OWNER_PASSWORD"]) &&
      !contains(svc.secret_names, "ZC_DB_OWNER_PASSWORD")
    ])
    error_message = "A migration task must receive exactly ZC_MODE, ZC_DB_URL, ZC_DB_OWNER_USER and the secret ZC_DB_OWNER_PASSWORD."
  }

  assert {
    condition     = output.db_bootstrap.task_definition_family == "zonacards-test-db-bootstrap"
    error_message = "The environment must register the database bootstrap task definition."
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
      contains(module.lotes.environment_names, "OTEL_EXPORTER_OTLP_ENDPOINT") &&
      !contains(module.lotes.environment_names, "QUARKUS_OTEL_SDK_DISABLED")
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

# --- connection budget ---------------------------------------------------------

run "budget_accepts_the_reference_sizing" {
  command = plan

  module {
    source = "../modules/platform"
  }

  assert {
    condition = terraform_data.db_connection_budget.input == {
      services = {
        lotes     = { peak_tasks = 4, pool_max = 10, peak_connections = 40, connection_limit = 40 }
        giftcards = { peak_tasks = 20, pool_max = 10, peak_connections = 200, connection_limit = 200 }
      }
      reserved_connections = 30
      committed            = 270
      max_connections      = 400
    }
    error_message = "The budget must be computed from peak tasks x pool, the role limits and the reserve."
  }
}

run "budget_rejects_more_tasks_than_the_role_limit_allows" {
  command = plan

  module {
    source = "../modules/platform"
  }

  variables {
    # 15 tasks x 10 connections = 150 in steady state: already above the limit.
    giftcards = {
      db_pool_max         = 10
      db_connection_limit = 120
      autoscaling = {
        min = 2
        max = 15
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
    # A fixed service is checked too: 2 tasks x 30 connections = 60 > 40.
    lotes = {
      desired_count       = 2
      db_pool_max         = 30
      db_connection_limit = 40
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
    # Each service fits in its own limit, but 40 + 200 + 30 > 250.
    database = {
      instance_class  = "db.t4g.medium"
      max_connections = 250
    }
  }

  expect_failures = [terraform_data.db_connection_budget]
}
