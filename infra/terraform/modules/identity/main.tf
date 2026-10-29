# Cognito issues the JWTs; scopes map to the API authorities (ADR-010).
resource "aws_cognito_user_pool" "this" {
  name                     = "${var.name}-users"
  deletion_protection      = var.deletion_protection ? "ACTIVE" : "INACTIVE"
  mfa_configuration        = "OPTIONAL"
  auto_verified_attributes = ["email"]
  username_attributes      = ["email"]

  password_policy {
    minimum_length    = 12
    require_lowercase = true
    require_uppercase = true
    require_numbers   = true
    require_symbols   = true
  }

  software_token_mfa_configuration {
    enabled = true
  }

  user_pool_add_ons {
    advanced_security_mode = "ENFORCED"
  }
}

resource "aws_cognito_resource_server" "ticketing" {
  user_pool_id = aws_cognito_user_pool.this.id
  identifier   = "ticketing"
  name         = "Ticketing API"

  scope {
    scope_name        = "purchase"
    scope_description = "Browse events and buy tickets"
  }

  scope {
    scope_name        = "admin"
    scope_description = "Manage events and issue complimentary tickets"
  }
}

resource "aws_cognito_user_pool_domain" "this" {
  domain       = var.domain_prefix
  user_pool_id = aws_cognito_user_pool.this.id
}

# Public client for buyers (authorization code + PKCE, no secret).
resource "aws_cognito_user_pool_client" "web" {
  name                                 = "${var.name}-web"
  user_pool_id                         = aws_cognito_user_pool.this.id
  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "ticketing/purchase"]
  callback_urls                        = var.callback_urls
  supported_identity_providers         = ["COGNITO"]
  prevent_user_existence_errors        = "ENABLED"
  access_token_validity                = 60
  id_token_validity                    = 60
  refresh_token_validity               = 1
  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }
  depends_on = [aws_cognito_resource_server.ticketing]
}

# Confidential machine client for back-office tooling (client credentials).
resource "aws_cognito_user_pool_client" "admin" {
  name                                 = "${var.name}-admin"
  user_pool_id                         = aws_cognito_user_pool.this.id
  generate_secret                      = true
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["client_credentials"]
  allowed_oauth_scopes                 = ["ticketing/admin", "ticketing/purchase"]
  supported_identity_providers         = ["COGNITO"]
  access_token_validity                = 15
  token_validity_units {
    access_token = "minutes"
  }
  depends_on = [aws_cognito_resource_server.ticketing]
}
