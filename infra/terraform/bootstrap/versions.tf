terraform {
  required_version = ">= 1.16"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
  }

  # No backend block on purpose: this configuration creates the bucket the
  # other configurations store their state in, so its own state starts local.
  # It manages a handful of resources that almost never change. Keep the state
  # file somewhere safe, or migrate it into the bucket once it exists.
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "zonacards"
      Environment = "shared"
      ManagedBy   = "terraform"
    }
  }
}
