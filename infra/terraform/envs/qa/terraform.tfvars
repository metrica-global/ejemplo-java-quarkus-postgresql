# qa: same shape as dev, kept long enough to test a release candidate.
# Values marked PLACEHOLDER must be replaced before a real apply.

environment        = "qa"
region             = "us-east-1"
log_retention_days = 30

# --- Network -----------------------------------------------------------------
vpc_cidr           = "10.21.0.0/16"
az_count           = 2
single_nat_gateway = true
enable_flow_logs   = false

# PLACEHOLDER, REQUIRED: this environment does not plan without a certificate.
# Uncomment with the real ARN, or pass it with -var / TF_VAR_certificate_arn.
# certificate_arn       = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
alb_deletion_protection = false

# --- Database ----------------------------------------------------------------
db_instance_class        = "db.t4g.medium"
db_max_connections       = 400 # a 4 GiB class defaults to slightly more; pinned so the budget is exact
db_multi_az              = false
db_backup_retention_days = 7
db_deletion_protection   = false
db_skip_final_snapshot   = false

secret_recovery_window_days = 7

# --- lotes: fixed --------------------------------------------------------------
lotes_tasks               = 2
lotes_db_connection_limit = 40 # 2 tasks x 2 (rolling deployment) x pool of 10

# --- giftcards: autoscaled 2 -> 12 ---------------------------------------------
giftcards_min_tasks               = 2
giftcards_max_tasks               = 12
giftcards_backlog_per_task_target = 10  # messages waiting per task; a message is one fragment of a lote
giftcards_db_connection_limit     = 240 # 12 tasks x 2 (rolling deployment) x pool of 10

# --- Received, not provisioned -------------------------------------------------
image_tag   = "bootstrap"                                     # tag pushed before the first full apply
oidc_issuer = "https://keycloak.example.com/realms/zonacards" # PLACEHOLDER: the client's Keycloak realm

# --- Account-level resources and CI/CD -----------------------------------------
# qa shares the non-production AWS account with dev, which already owns the ECR
# repositories, the GitHub OIDC provider and the build role.
create_account_level_resources = false
github_repository              = "example-org/zonacards" # PLACEHOLDER: owner/name
