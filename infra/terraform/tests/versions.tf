# This directory is a root module with no resources. It exists so the module
# tests (*.tftest.hcl next to this file) have one place to run from:
#
#   terraform init -backend=false && terraform test
#
# Every run block loads the module it tests with `module { source = ... }`,
# and every provider is mocked: no credentials and no API calls.

terraform {
  required_version = ">= 1.16"
}
