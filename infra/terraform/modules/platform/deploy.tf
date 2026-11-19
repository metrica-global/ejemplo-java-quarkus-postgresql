# The contract with the deploy pipeline, published where the pipeline reads it.
#
# The pipeline deploys "the application" to an environment. Which services
# that means depends on the topology, and the topology changes when a module
# is promoted. So the list is not written in the workflow or copied into
# GitHub variables: Terraform, which knows it, publishes it in a parameter,
# and the pipeline reads the parameter at the start of every deployment.
# After a promotion there is nothing to update by hand.

locals {
  parameter_prefix = "/${local.project}/${var.environment}"

  deploy_descriptor = {
    cluster        = module.cluster.cluster_name
    repository     = local.repository_name
    repository_url = local.repository_url
    container      = module.nucleo.container_name
    subnets        = module.network.private_subnet_ids

    # One task migrates the schema of every module. It runs with the security
    # group of the nucleo, which the database already accepts.
    migrate = {
      task_definition_family = module.nucleo.migrate_task_definition_family
      security_group_id      = module.nucleo.security_group_id
      log_group              = module.nucleo.log_group_name
    }

    # In the order the pipeline deploys them: the services of promoted modules
    # first, the nucleo last, stopping at the first failure. The nucleo is the
    # one that can stop serving a module, and it must not do so before the
    # service that takes the module over is running the release.
    services = concat(
      [
        for name in sort(keys(var.promoted_modules)) : {
          name                   = name
          service                = module.promoted[name].service_name
          task_definition_family = module.promoted[name].task_definition_family
          modules                = name
        }
      ],
      [
        {
          name                   = "nucleo"
          service                = module.nucleo.service_name
          task_definition_family = module.nucleo.task_definition_family
          modules                = join(",", local.nucleo_modules)
        },
      ],
    )
  }
}

# Neither parameter holds a secret: names, ids and an image tag. They are plain
# String parameters on purpose, so their content is visible in every plan.

resource "aws_ssm_parameter" "deploy" {
  name           = "${local.parameter_prefix}/deploy"
  description    = "What the deploy pipeline deploys in ${var.environment}: cluster, migration task and services, in order. Managed by Terraform."
  type           = "String"
  insecure_value = jsonencode(local.deploy_descriptor)
}

# The image tag that is deployed. Terraform creates the parameter with the tag
# of the first image; from then on the deploy pipeline writes it after every
# successful deployment, and Terraform only reads it.
#
# It is what a service created LATER starts with. When a module is promoted,
# its new service must run the release the rest of the environment is running,
# not the image of the first day. Reading it here means nobody has to pass the
# tag by hand at that moment.
resource "aws_ssm_parameter" "release" {
  name           = "${local.parameter_prefix}/release"
  description    = "Image tag deployed in ${var.environment}. Written by the deploy pipeline after every successful deployment."
  type           = "String"
  insecure_value = var.image_tag

  # The value belongs to the pipeline from the first deployment on. So does
  # whatever its writes do to the description.
  lifecycle {
    ignore_changes = [insecure_value, description]
  }
}

locals {
  release_tag = aws_ssm_parameter.release.insecure_value
}
