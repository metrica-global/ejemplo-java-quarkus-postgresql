# tflint configuration for every root and module under infra/terraform.
#
#   docker run --rm -v "$PWD:/data:ro" --entrypoint /bin/sh ghcr.io/terraform-linters/tflint \
#     -c 'tflint --init --config /data/.tflint.hcl && tflint --recursive --config /data/.tflint.hcl'

config {
  # Follow calls to the modules of this repository, so each environment is
  # linted with the values it really passes. The community VPC module is not
  # inspected.
  call_module_type = "local"
}

plugin "terraform" {
  enabled = true
  preset  = "all"
}

plugin "aws" {
  enabled = true
  version = "0.50.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}

# The only rule turned off. Two directories are deliberately not conventional
# modules: tests/ is a root that only hosts *.tftest.hcl files, and
# modules/messaging/topology only has outputs. The rule would ask for empty
# main.tf / variables.tf / outputs.tf files in both.
rule "terraform_standard_module_structure" {
  enabled = false
}
