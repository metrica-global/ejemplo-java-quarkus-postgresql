# Values for the data sources of the mocked AWS provider.
#
# A mocked provider invents a random string for every computed attribute.
# That is fine for IDs, but some values are parsed or validated further down:
# a policy must be JSON, the list of zones must have enough entries, and an
# ARN is built from the partition and account. Those get fixed values here.
# 123456789012 is the documentation account id, not a real one.

mock_data "aws_caller_identity" {
  defaults = {
    account_id = "123456789012"
    arn        = "arn:aws:iam::123456789012:user/terraform-test"
    user_id    = "AIDAEXAMPLEEXAMPLE000"
  }
}

mock_data "aws_partition" {
  defaults = {
    partition  = "aws"
    dns_suffix = "amazonaws.com"
  }
}

mock_data "aws_region" {
  defaults = {
    region = "us-east-1"
  }
}

mock_data "aws_availability_zones" {
  defaults = {
    names = ["us-east-1a", "us-east-1b", "us-east-1c"]
  }
}

# The real data source renders its statements to JSON. The mock cannot, so
# every policy document resolves to this valid placeholder. Tests therefore
# assert on resources and arguments, not on rendered policy text.
mock_data "aws_iam_policy_document" {
  defaults = {
    json = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"MockedByTerraformTest\",\"Effect\":\"Allow\",\"Action\":\"sts:GetCallerIdentity\",\"Resource\":\"*\"}]}"
  }
}
