# Roles GitHub Actions assumes through OIDC. Skipped entirely when no
# repository is given.

module "github_oidc" {
  count  = var.github_repository == null ? 0 : 1
  source = "../github-oidc"

  name_prefix        = local.name_prefix
  github_repository  = var.github_repository
  github_environment = var.environment

  # Once per account, like the repository the build role pushes to.
  create_oidc_provider = var.create_account_level_resources
  create_build_role    = var.create_account_level_resources
  ecr_repository_arns  = [local.repository_arn]

  # The deploy role can update the services of this environment, run the
  # migration task, hand their roles to ECS, read what it has to deploy and
  # record what it deployed. Nothing else. The lists follow the topology: a
  # promoted module's service is added to them in the apply that creates it.
  deploy = {
    cluster_arn       = module.cluster.cluster_arn
    cluster_name      = module.cluster.cluster_name
    service_arns      = concat([module.nucleo.service_arn], [for name in sort(keys(module.promoted)) : module.promoted[name].service_arn])
    run_task_families = [module.nucleo.migrate_task_definition_family]
    passable_role_arns = concat(
      [module.nucleo.task_role_arn, module.nucleo.execution_role_arn, module.nucleo.migrate_execution_role_arn],
      flatten([for name in sort(keys(module.promoted)) : [module.promoted[name].task_role_arn, module.promoted[name].execution_role_arn]]),
    )
    log_group_arns       = concat([module.nucleo.log_group_arn], [for name in sort(keys(module.promoted)) : module.promoted[name].log_group_arn])
    read_parameter_arns  = [aws_ssm_parameter.deploy.arn, aws_ssm_parameter.release.arn]
    write_parameter_arns = [aws_ssm_parameter.release.arn]
  }

  state_bucket_arn = var.state_bucket_arn

  # Secrets of this environment that Terraform manages and therefore refreshes
  # during a plan. The RDS-managed master secret is not among them.
  plan_secret_arns = [
    "arn:${local.partition}:secretsmanager:${local.region}:${local.account_id}:secret:${local.name_prefix}-*",
  ]
}
