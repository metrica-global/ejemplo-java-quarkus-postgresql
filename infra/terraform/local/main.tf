# Messaging topology on a LOCAL EMULATOR. Never on AWS.
#
# This root applies only modules/messaging, fed by the same topology module
# the AWS environments use. A developer therefore gets the topics, queues,
# dead-letter queues and filter policies that are deployed, without a second
# definition to keep in sync.
#
# The provider cannot reach AWS from here: every service this configuration
# uses has its endpoint overridden, the credentials are dummy values the
# emulator ignores, and the lookups that would call real AWS are skipped.

provider "aws" {
  region = var.region

  # Dummy credentials for the emulator. Not secrets.
  access_key = "test"
  secret_key = "test"

  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  endpoints {
    sns = var.endpoint
    sqs = var.endpoint
    sts = var.endpoint
  }
}

module "topology" {
  source = "../modules/messaging/topology"
}

module "messaging" {
  source = "../modules/messaging"

  name_prefix = "zonacards-local"
  topics      = module.topology.topics
  queues      = module.topology.queues

  # The emulator implements SNS and SQS, not KMS or CloudWatch alarms.
  enable_encryption = false
  enable_alarms     = false
}
