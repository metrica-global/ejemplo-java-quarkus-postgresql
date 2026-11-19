# dev: the cheapest shape that still shows the whole design.
# Values marked PLACEHOLDER must be replaced before a real apply.

environment        = "dev"
region             = "us-east-1"
log_retention_days = 14

# --- Network -----------------------------------------------------------------
vpc_cidr           = "10.20.0.0/16"
az_count           = 2
single_nat_gateway = true
enable_flow_logs   = false

# No certificate: the load balancer listens on plain HTTP. Development only,
# and only because it is stated here explicitly.
# certificate_arn       = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
alb_allow_plain_http    = true
alb_deletion_protection = false

# --- Database ----------------------------------------------------------------
db_instance_class        = "db.t4g.medium"
db_max_connections       = 400 # a 4 GiB class defaults to slightly more; pinned so the budget is exact
db_multi_az              = false
db_backup_retention_days = 7
db_deletion_protection   = false
db_skip_final_snapshot   = true

secret_recovery_window_days = 0 # lets the environment be destroyed and recreated

# --- lotes: fixed --------------------------------------------------------------
lotes_tasks               = 2
lotes_db_connection_limit = 40 # 2 tasks x 2 (rolling deployment) x pool of 10

# --- giftcards: autoscaled 2 -> 10 ---------------------------------------------
giftcards_min_tasks               = 2
giftcards_max_tasks               = 10
giftcards_backlog_per_task_target = 10  # messages waiting per task; a message is one fragment of a lote
giftcards_db_connection_limit     = 200 # 10 tasks x 2 (rolling deployment) x pool of 10

# --- Received, not provisioned -------------------------------------------------
image_tag   = "bootstrap"                                     # tag pushed before the first full apply
oidc_issuer = "https://keycloak.example.com/realms/zonacards" # PLACEHOLDER: the client's Keycloak realm

# --- Account-level resources and CI/CD -----------------------------------------
# dev owns the ECR repositories, the GitHub OIDC provider and the build role of
# the non-production account.
create_account_level_resources = true
github_repository              = "example-org/zonacards" # PLACEHOLDER: owner/name
