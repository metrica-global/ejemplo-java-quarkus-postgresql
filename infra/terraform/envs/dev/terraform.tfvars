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

# --- The application ---------------------------------------------------------------
# One image with every module. The nucleo serves them all.
nucleo_tasks = 2

# No module has a service of its own. To give giftcards one, with its own
# autoscaling, see promocion-giftcards.tfvars.
promoted_modules = {}

# --- Database roles: one per module --------------------------------------------
# The limit of a role has to hold every deployment that can serve the module at the
# same time. giftcards is sized for the day it is promoted, so that promoting it does
# not require touching the database.
lotes_db_connection_limit     = 40  # nucleo: 2 tasks x 2 (rolling deployment) x pool of 10
giftcards_db_connection_limit = 240 # the same 40 in the nucleo + its own service at its maximum: 10 tasks x 2 x 10 = 200

# --- Received, not provisioned -------------------------------------------------
image_tag   = "bootstrap"                                     # tag pushed before the first apply; later the pipeline records each release
oidc_issuer = "https://keycloak.example.com/realms/zonacards" # PLACEHOLDER: the client's Keycloak realm

# --- Account-level resources and CI/CD -----------------------------------------
# dev owns the ECR repository, the GitHub OIDC provider and the build role of
# the non-production account.
create_account_level_resources = true
github_repository              = "example-org/zonacards" # PLACEHOLDER: owner/name
