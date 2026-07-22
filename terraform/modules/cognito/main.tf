locals {
  federation_enabled = var.google_client_id != "" || var.apple_client_id != ""
  base_identity_providers = ["COGNITO"]
  identity_providers = concat(
    local.base_identity_providers,
    var.google_client_id != "" ? ["Google"] : [],
    var.apple_client_id != "" ? ["SignInWithApple"] : [],
  )
}

resource "aws_cognito_user_pool" "this" {
  name = "${var.name_prefix}-user-pool"

  # Email is the only identity — no phone/username signup
  username_attributes     = ["email"]
  auto_verified_attributes = ["email"]

  password_policy {
    minimum_length    = 12
    require_lowercase = true
    require_uppercase = true
    require_numbers   = true
    require_symbols   = true
  }

  # Optional MFA — user can choose to enroll TOTP
  mfa_configuration = "OPTIONAL"
  software_token_mfa_configuration {
    enabled = true
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  email_configuration {
    email_sending_account = "COGNITO_DEFAULT"
  }

  schema {
    name                     = "email"
    attribute_data_type      = "String"
    required                 = true
    mutable                  = true
    string_attribute_constraints {
      min_length = 1
      max_length = 256
    }
  }

  tags = var.tags
}

resource "aws_cognito_user_pool_domain" "this" {
  domain          = var.auth_domain_prefix
  user_pool_id    = aws_cognito_user_pool.this.id
  certificate_arn = var.custom_domain != "" ? var.acm_certificate_arn_us_east_1 : null
}

# ---- Google federation (optional — only created if client_id supplied) ----
resource "aws_cognito_identity_provider" "google" {
  count         = var.google_client_id != "" ? 1 : 0
  user_pool_id  = aws_cognito_user_pool.this.id
  provider_name = "Google"
  provider_type = "Google"

  provider_details = {
    client_id        = var.google_client_id
    client_secret     = var.google_client_secret
    authorize_scopes = "email openid profile"
  }

  attribute_mapping = {
    email    = "email"
    username = "sub"
  }
}

# ---- Apple federation (optional — only created if client_id supplied) ----
resource "aws_cognito_identity_provider" "apple" {
  count         = var.apple_client_id != "" ? 1 : 0
  user_pool_id  = aws_cognito_user_pool.this.id
  provider_name = "SignInWithApple"
  provider_type = "SignInWithApple"

  provider_details = {
    client_id        = var.apple_client_id
    team_id          = var.apple_team_id
    key_id           = var.apple_key_id
    private_key      = var.apple_private_key
    authorize_scopes = "email name"
  }

  attribute_mapping = {
    email    = "email"
    username = "sub"
  }
}

# ---- Mobile app client ----
resource "aws_cognito_user_pool_client" "mobile" {
  name         = "${var.name_prefix}-mobile-client"
  user_pool_id = aws_cognito_user_pool.this.id

  generate_secret = false # public client (mobile)

  explicit_auth_flows = [
    "ALLOW_USER_PASSWORD_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH",
    "ALLOW_USER_SRP_AUTH",
  ]

  allowed_oauth_flows_user_pool_client = local.federation_enabled
  allowed_oauth_flows                  = local.federation_enabled ? ["code"] : null
  allowed_oauth_scopes                 = local.federation_enabled ? ["email", "openid", "profile"] : null
  callback_urls                        = local.federation_enabled ? var.mobile_callback_urls : null
  supported_identity_providers         = local.identity_providers

  access_token_validity  = 1
  id_token_validity      = 1
  refresh_token_validity = 30

  token_validity_units {
    access_token  = "hours"
    id_token      = "hours"
    refresh_token = "days"
  }
}

# ---- Web app client (distinct from mobile: different token lifetimes, redirect URIs) ----
resource "aws_cognito_user_pool_client" "web" {
  name         = "${var.name_prefix}-web-client"
  user_pool_id = aws_cognito_user_pool.this.id

  generate_secret = false # public client (SPA)

  explicit_auth_flows = [
    "ALLOW_USER_PASSWORD_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH",
    "ALLOW_USER_SRP_AUTH",
  ]

  allowed_oauth_flows_user_pool_client = local.federation_enabled
  allowed_oauth_flows                  = local.federation_enabled ? ["code"] : null
  allowed_oauth_scopes                 = local.federation_enabled ? ["email", "openid", "profile"] : null
  callback_urls                        = local.federation_enabled ? var.web_callback_urls : null
  logout_urls                          = local.federation_enabled ? var.web_logout_urls : null
  supported_identity_providers         = local.identity_providers

  access_token_validity  = 1
  id_token_validity      = 1
  refresh_token_validity = 7 # shorter than mobile — browser session norms

  token_validity_units {
    access_token  = "hours"
    id_token      = "hours"
    refresh_token = "days"
  }

  prevent_user_existence_errors = "ENABLED"
}
