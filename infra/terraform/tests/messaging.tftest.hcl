# modules/messaging driven by the shared topology (modules/messaging/topology).
#
# The first run only evaluates the topology; the following runs feed its
# outputs to the messaging module, exactly as modules/platform and local/ do.
# So these assertions are about the topology that is really deployed, not
# about a copy kept in the test.

mock_provider "aws" {
  source = "./mocks/aws"
}

# A plan does not know ARNs: AWS assigns them on creation. The wiring this
# file checks (which queue redrives to which dead-letter queue, which inbox is
# subscribed to which topic) is expressed in ARNs, so each resource instance
# gets a distinct, recognisable ARN that is available while planning.
# 123456789012 is the documentation account id.

override_resource {
  target          = aws_sns_topic.this["lotes-events"]
  override_during = plan
  values          = { arn = "arn:aws:sns:us-east-1:123456789012:zonacards-test-lotes-events" }
}

override_resource {
  target          = aws_sns_topic.this["giftcards-events"]
  override_during = plan
  values          = { arn = "arn:aws:sns:us-east-1:123456789012:zonacards-test-giftcards-events" }
}

override_resource {
  target          = aws_sqs_queue.this["giftcards-inbox"]
  override_during = plan
  values          = { arn = "arn:aws:sqs:us-east-1:123456789012:zonacards-test-giftcards-inbox" }
}

override_resource {
  target          = aws_sqs_queue.this["lotes-inbox"]
  override_during = plan
  values          = { arn = "arn:aws:sqs:us-east-1:123456789012:zonacards-test-lotes-inbox" }
}

override_resource {
  target          = aws_sqs_queue.this["giftcards-generacion"]
  override_during = plan
  values          = { arn = "arn:aws:sqs:us-east-1:123456789012:zonacards-test-giftcards-generacion" }
}

override_resource {
  target          = aws_sqs_queue.dlq["giftcards-inbox"]
  override_during = plan
  values          = { arn = "arn:aws:sqs:us-east-1:123456789012:zonacards-test-giftcards-inbox-dlq" }
}

override_resource {
  target          = aws_sqs_queue.dlq["lotes-inbox"]
  override_during = plan
  values          = { arn = "arn:aws:sqs:us-east-1:123456789012:zonacards-test-lotes-inbox-dlq" }
}

override_resource {
  target          = aws_sqs_queue.dlq["giftcards-generacion"]
  override_during = plan
  values          = { arn = "arn:aws:sqs:us-east-1:123456789012:zonacards-test-giftcards-generacion-dlq" }
}

override_resource {
  target          = aws_kms_key.this[0]
  override_during = plan
  values          = { arn = "arn:aws:kms:us-east-1:123456789012:key/00000000-0000-0000-0000-000000000000" }
}

run "topology" {
  command = plan

  module {
    source = "../modules/messaging/topology"
  }

  assert {
    condition     = toset(keys(output.topics)) == toset(["lotes-events", "giftcards-events"])
    error_message = "The topology must declare one topic per publishing domain."
  }

  assert {
    condition     = toset(keys(output.queues)) == toset(["giftcards-inbox", "lotes-inbox", "giftcards-generacion"])
    error_message = "The topology must declare the two inboxes and the giftcards work queue."
  }
}

run "queues_dlqs_and_filters" {
  command = plan

  module {
    source = "../modules/messaging"
  }

  variables {
    name_prefix = "zonacards-test"
    topics      = run.topology.topics
    queues      = run.topology.queues
  }

  # --- topics -----------------------------------------------------------------

  assert {
    condition = { for k, t in aws_sns_topic.this : k => t.name } == {
      "lotes-events"     = "zonacards-test-lotes-events"
      "giftcards-events" = "zonacards-test-giftcards-events"
    }
    error_message = "Topics must be named <prefix>-<logical name>."
  }

  # --- one dead-letter queue per queue ----------------------------------------

  assert {
    condition     = length(aws_sqs_queue.this) == 3 && length(aws_sqs_queue.dlq) == 3
    error_message = "There must be three queues and three dead-letter queues."
  }

  assert {
    condition     = keys(aws_sqs_queue.dlq) == keys(aws_sqs_queue.this)
    error_message = "Every queue must have its own dead-letter queue."
  }

  assert {
    condition = alltrue([
      for k, q in aws_sqs_queue.dlq :
      q.name == "${aws_sqs_queue.this[k].name}-dlq" && q.message_retention_seconds == 1209600
    ])
    error_message = "A dead-letter queue must be named <queue>-dlq and keep messages for 14 days."
  }

  assert {
    condition = alltrue([
      for k, q in aws_sqs_queue.this :
      jsondecode(q.redrive_policy) == {
        deadLetterTargetArn = aws_sqs_queue.dlq[k].arn
        maxReceiveCount     = 5
      }
    ])
    error_message = "Every queue must redrive to its own dead-letter queue after 5 receives."
  }

  assert {
    condition     = alltrue([for q in aws_sqs_queue.this : q.receive_wait_time_seconds == 20])
    error_message = "Every queue must use 20 s long polling."
  }

  assert {
    condition = { for k, q in aws_sqs_queue.this : k => q.visibility_timeout_seconds } == {
      "giftcards-inbox"      = 60
      "lotes-inbox"          = 60
      "giftcards-generacion" = 300
    }
    error_message = "Visibility timeout must be 60 s for the inboxes and 300 s for the work queue."
  }

  # --- subscriptions and filter policies --------------------------------------

  assert {
    condition     = toset(keys(aws_sns_topic_subscription.this)) == toset(["giftcards-inbox", "lotes-inbox"])
    error_message = "Only the inboxes are subscribed; giftcards-generacion is written to directly."
  }

  assert {
    condition = alltrue([
      for s in aws_sns_topic_subscription.this :
      s.protocol == "sqs" && s.raw_message_delivery && s.filter_policy_scope == "MessageAttributes"
    ])
    error_message = "Subscriptions must deliver raw messages to SQS and filter on message attributes."
  }

  assert {
    condition = jsondecode(aws_sns_topic_subscription.this["giftcards-inbox"].filter_policy) == {
      type = [
        "zonacards.lotes.lote-solicitado.v1",
        "zonacards.lotes.lote-anulado.v1",
      ]
    }
    error_message = "giftcards-inbox must receive exactly lote-solicitado and lote-anulado."
  }

  assert {
    condition = jsondecode(aws_sns_topic_subscription.this["lotes-inbox"].filter_policy) == {
      type = [
        "zonacards.giftcards.generacion-iniciada.v1",
        "zonacards.giftcards.fragmento-generado.v1",
        "zonacards.giftcards.fragmento-fallido.v1",
        "zonacards.giftcards.lote-anulado-aplicado.v1",
      ]
    }
    error_message = "lotes-inbox must receive exactly the four giftcards events it handles."
  }

  assert {
    condition = (
      aws_sns_topic_subscription.this["giftcards-inbox"].topic_arn == aws_sns_topic.this["lotes-events"].arn &&
      aws_sns_topic_subscription.this["lotes-inbox"].topic_arn == aws_sns_topic.this["giftcards-events"].arn
    )
    error_message = "Each inbox must be subscribed to the OTHER domain's topic."
  }

  assert {
    condition     = toset(keys(aws_sqs_queue_policy.this)) == toset(["giftcards-inbox", "lotes-inbox"])
    error_message = "Each inbox must have a queue policy that admits its topic."
  }

  # --- encryption and alarms (on by default) ----------------------------------

  assert {
    condition     = length(aws_kms_key.this) == 1 && aws_kms_key.this[0].enable_key_rotation
    error_message = "Messaging must be encrypted with one customer managed key with rotation."
  }

  assert {
    condition = alltrue(concat(
      [for t in aws_sns_topic.this : t.kms_master_key_id == aws_kms_key.this[0].arn],
      [for q in aws_sqs_queue.this : q.kms_master_key_id == aws_kms_key.this[0].arn],
      [for q in aws_sqs_queue.dlq : q.kms_master_key_id == aws_kms_key.this[0].arn],
    ))
    error_message = "Every topic, queue and dead-letter queue must use the messaging key."
  }

  assert {
    condition     = length(aws_cloudwatch_metric_alarm.dlq_not_empty) == 3 && length(aws_cloudwatch_metric_alarm.oldest_message_age) == 3
    error_message = "Every queue must have a dead-letter alarm and an age alarm."
  }

  assert {
    condition = alltrue([
      for k, a in aws_cloudwatch_metric_alarm.dlq_not_empty :
      a.threshold == 0 && a.comparison_operator == "GreaterThanThreshold" && a.dimensions.QueueName == aws_sqs_queue.dlq[k].name
    ])
    error_message = "The dead-letter alarm must fire on any visible message in the dead-letter queue."
  }
}

run "emulator_mode_has_no_kms_and_no_alarms" {
  command = plan

  module {
    source = "../modules/messaging"
  }

  variables {
    name_prefix       = "zonacards-local"
    topics            = run.topology.topics
    queues            = run.topology.queues
    enable_encryption = false
    enable_alarms     = false
  }

  assert {
    condition     = length(aws_kms_key.this) == 0 && length(data.aws_caller_identity.current) == 0
    error_message = "With encryption disabled no KMS key may be created and the account must not be looked up."
  }

  assert {
    condition     = length(aws_cloudwatch_metric_alarm.dlq_not_empty) + length(aws_cloudwatch_metric_alarm.oldest_message_age) == 0
    error_message = "With alarms disabled no CloudWatch alarm may be created."
  }

  assert {
    condition     = length(aws_sqs_queue.this) == 3 && length(aws_sqs_queue.dlq) == 3 && length(aws_sns_topic_subscription.this) == 2
    error_message = "The emulator must get the same queues and subscriptions as AWS."
  }
}

run "rejects_a_subscription_to_an_unknown_topic" {
  command = plan

  module {
    source = "../modules/messaging"
  }

  variables {
    name_prefix = "zonacards-test"
    topics      = { "lotes-events" = {} }
    queues = {
      "orphan-inbox" = {
        subscribe_to = {
          topic       = "does-not-exist"
          event_types = ["zonacards.lotes.lote-solicitado.v1"]
        }
      }
    }
  }

  expect_failures = [var.queues]
}
