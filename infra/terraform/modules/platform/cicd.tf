# Roles GitHub Actions assumes through OIDC. Skipped entirely when no
# repository is given.

module "github_oidc" {
  count  = var.github_repository == null ? 0 : 1
  source = "../github-oidc"

  name_prefix        = local.name_prefix
  github_repository  = var.github_repository
  github_environment = var.environment

  # Once per account, like the repositories the build role pushes to.
  create_oidc_provider = var.create_account_level_resources
  create_build_role    = var.create_account_level_resources
  ecr_repository_arns  = values(local.repository_arns)

  # The deploy role can update these two services, run their migration tasks
  # and hand these roles to ECS. Nothing else.
  deploy = {
    cluster_arn  = module.cluster.cluster_arn
    cluster_name = module.cluster.cluster_name
    service_arns = [module.lotes.service_arn, module.giftcards.service_arn]
    run_task_families = [
      module.lotes.migrate_task_definition_family,
      module.giftcards.migrate_task_definition_family,
    ]
    passable_role_arns = [
      module.lotes.task_role_arn,
      module.lotes.execution_role_arn,
      module.lotes.migrate_execution_role_arn,
      module.giftcards.task_role_arn,
      module.giftcards.execution_role_arn,
      module.giftcards.migrate_execution_role_arn,
    ]
    log_group_arns = [module.lotes.log_group_arn, module.giftcards.log_group_arn]
  }

  state_bucket_arn = var.state_bucket_arn

  # Secrets of this environment that Terraform manages and therefore refreshes
  # during a plan. The RDS-managed master secret is not among them.
  plan_secret_arns = [
    "arn:${local.partition}:secretsmanager:${local.region}:${local.account_id}:secret:${local.name_prefix}-*",
  ]
}
