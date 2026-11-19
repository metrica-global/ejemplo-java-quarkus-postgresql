# prod: three availability zones, no single point of failure, nothing that can
# be deleted by accident.
# Values marked PLACEHOLDER must be replaced before a real apply.

environment        = "prod"
region             = "us-east-1"
log_retention_days = 365

# --- Network -----------------------------------------------------------------
vpc_cidr           = "10.22.0.0/16"
az_count           = 3
single_nat_gateway = false # one NAT gateway per availability zone
enable_flow_logs   = true

# PLACEHOLDER, REQUIRED: this environment does not plan without a certificate.
# Uncomment with the real ARN, or pass it with -var / TF_VAR_certificate_arn.
# certificate_arn       = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
alb_deletion_protection = true

# --- Database ----------------------------------------------------------------
db_instance_class        = "db.m7g.large"
db_max_connections       = 800 # an 8 GiB class defaults to slightly more; pinned so the budget is exact
db_reserved_connections  = 40
db_multi_az              = true
db_backup_retention_days = 30
db_deletion_protection   = true
db_skip_final_snapshot   = false

secret_recovery_window_days = 30

# --- lotes: fixed --------------------------------------------------------------
lotes_tasks               = 3  # one per availability zone
lotes_db_connection_limit = 60 # 3 tasks x 2 (rolling deployment) x pool of 10

# --- giftcards: autoscaled 3 -> 30 ---------------------------------------------
giftcards_min_tasks               = 3
giftcards_max_tasks               = 30
giftcards_backlog_per_task_target = 10  # messages waiting per task; a message is one fragment of a lote
giftcards_db_connection_limit     = 600 # 30 tasks x 2 (rolling deployment) x pool of 10

# --- Received, not provisioned -------------------------------------------------
image_tag   = "bootstrap"                                     # tag pushed before the first full apply
oidc_issuer = "https://keycloak.example.com/realms/zonacards" # PLACEHOLDER: the client's Keycloak realm

# --- Account-level resources and CI/CD -----------------------------------------
# prod runs in its own AWS account, so it owns that account's ECR repositories,
# GitHub OIDC provider and build role. Set to false if prod shares an account
# with an environment that already owns them.
create_account_level_resources = true
github_repository              = "example-org/zonacards" # PLACEHOLDER: owner/name
