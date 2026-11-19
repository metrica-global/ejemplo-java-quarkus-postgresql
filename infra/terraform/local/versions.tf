terraform {
  required_version = ">= 1.16"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
  }

  # State stays on the developer's machine (git-ignored). The emulator is
  # disposable: delete terraform.tfstate whenever its data is wiped.
  backend "local" {}
}
