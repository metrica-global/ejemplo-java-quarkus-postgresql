terraform {
  required_version = ">= 1.16"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
  }

  # Partial configuration: bucket, key and region come from a file that is not
  # committed (see backend.hcl.example):
  #
  #   terraform init -backend-config=backend.hcl
  #
  # The two settings below are the same everywhere, so they are fixed here.
  # use_lockfile takes the state lock with a conditional write to S3; no
  # DynamoDB table is needed.
  backend "s3" {
    encrypt      = true
    use_lockfile = true
  }
}
