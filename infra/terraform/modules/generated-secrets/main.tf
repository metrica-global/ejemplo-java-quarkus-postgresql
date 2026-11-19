# Secrets whose value Terraform generates but never keeps.
#
# This is the only place in the repository where a secret value is created.
# Each value is EPHEMERAL: generated in memory, written to Secrets Manager
# through a write-only argument (secret_string_wo) and forgotten. It is in
# neither the plan nor the state; only the secret's ARN is.
#
# An ephemeral value is regenerated on every run, but it is only SENT to
# Secrets Manager when secret_string_wo_version changes. The stored value is
# therefore stable until var.value_version is increased on purpose.

locals {
  passwords = { for key, s in var.secrets : key => s if s.format == "password" }
  keys      = { for key, s in var.secrets : key => s if s.format == "base64-256-bit-key" }
}

# -----------------------------------------------------------------------------
# format = "password": 40 letters and digits (about 238 bits).
# No punctuation: these values travel through shell and psql variables in the
# database bootstrap task and should need no quoting anywhere.
# -----------------------------------------------------------------------------

ephemeral "random_password" "password" {
  for_each = local.passwords

  length  = 40
  special = false
}

# -----------------------------------------------------------------------------
# format = "base64-256-bit-key": 32 random bytes, base64 encoded.
#
# The random provider can only generate ephemeral STRINGS, not bytes, so the
# base64 text is generated directly. The canonical base64 form of 32 bytes
# (256 bits) is always:
#
#   42 characters of the full 64-character alphabet   42 x 6 = 252 bits
#    1 character whose two low bits are zero          4 bits (16 choices)
#    1 padding character "="
#
# Drawing those characters uniformly gives exactly the same distribution as
# encoding 32 uniformly random bytes.
# -----------------------------------------------------------------------------

ephemeral "random_password" "key_head" {
  for_each = local.keys

  length           = 42
  upper            = true
  lower            = true
  numeric          = true
  special          = true
  override_special = "+/"
}

ephemeral "random_password" "key_tail" {
  for_each = local.keys

  length           = 1
  upper            = false
  lower            = false
  numeric          = false
  special          = true
  override_special = "AEIMQUYcgkosw048" # base64 digits 0, 4, 8, ... 60
}

# -----------------------------------------------------------------------------
# Secrets Manager
# -----------------------------------------------------------------------------

resource "aws_secretsmanager_secret" "this" {
  for_each = var.secrets

  name                    = each.value.name
  description             = each.value.description
  recovery_window_in_days = var.recovery_window_days
}

resource "aws_secretsmanager_secret_version" "this" {
  for_each = var.secrets

  secret_id = aws_secretsmanager_secret.this[each.key].id

  secret_string_wo = (
    each.value.format == "password"
    ? ephemeral.random_password.password[each.key].result
    : "${ephemeral.random_password.key_head[each.key].result}${ephemeral.random_password.key_tail[each.key].result}="
  )
  secret_string_wo_version = var.value_version
}
