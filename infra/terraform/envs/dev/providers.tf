provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "zonacards"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
