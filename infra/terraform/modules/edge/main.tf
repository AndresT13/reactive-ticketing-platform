# Public edge: API Gateway HTTP API (JWT authorizer + throttling) -> VPC Link -> internal ALB (+ WAF) -> API tasks.
# HTTP APIs cannot be associated with AWS WAF directly, so the web ACL protects the ALB behind the VPC Link.

resource "aws_security_group" "vpc_link" {
  name        = "${var.name}-vpc-link"
  description = "API Gateway VPC Link ENIs"
  vpc_id      = var.vpc_id

  egress {
    description = "HTTP to the internal ALB"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr_block]
  }
}

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "Internal ALB: inbound only from the VPC Link"
  vpc_id      = var.vpc_id

  ingress {
    description     = "HTTP from the VPC Link"
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    security_groups = [aws_security_group.vpc_link.id]
  }

  egress {
    description = "To the API tasks"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr_block]
  }
}

resource "aws_lb" "this" {
  name                       = "${var.name}-alb"
  internal                   = true
  load_balancer_type         = "application"
  subnets                    = var.private_subnet_ids
  security_groups            = [aws_security_group.alb.id]
  drop_invalid_header_fields = true
  idle_timeout               = 60
  enable_deletion_protection = var.deletion_protection
}

resource "aws_lb_target_group" "api" {
  name                 = "${var.name}-api"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  health_check {
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# Traffic inside the VPC travels over the private network; TLS terminates at API Gateway.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

# ---------- WAF ----------
resource "aws_wafv2_web_acl" "this" {
  name  = "${var.name}-web-acl"
  scope = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "aws-common"
    priority = 10
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "aws-common"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-known-bad-inputs"
    priority = 20
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "aws-known-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  # Coarse per-IP ceiling; the application applies a finer per-customer token bucket (ADR-008).
  rule {
    name     = "rate-limit-per-ip"
    priority = 30
    action {
      block {}
    }
    statement {
      rate_based_statement {
        limit              = var.waf_rate_limit_per_5min
        aggregate_key_type = "FORWARDED_IP"
        forwarded_ip_config {
          header_name       = "X-Forwarded-For"
          fallback_behavior = "MATCH"
        }
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "rate-limit-per-ip"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.name}-web-acl"
    sampled_requests_enabled   = true
  }
}

resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = aws_lb.this.arn
  web_acl_arn  = aws_wafv2_web_acl.this.arn
}

# ---------- API Gateway ----------
resource "aws_apigatewayv2_vpc_link" "this" {
  name               = "${var.name}-vpc-link"
  subnet_ids         = var.private_subnet_ids
  security_group_ids = [aws_security_group.vpc_link.id]
}

resource "aws_apigatewayv2_api" "this" {
  name          = "${var.name}-api"
  protocol_type = "HTTP"
}

resource "aws_apigatewayv2_authorizer" "jwt" {
  api_id           = aws_apigatewayv2_api.this.id
  name             = "cognito"
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]

  jwt_configuration {
    issuer   = var.jwt_issuer_uri
    audience = var.jwt_audiences
  }
}

resource "aws_apigatewayv2_integration" "alb" {
  api_id               = aws_apigatewayv2_api.this.id
  integration_type     = "HTTP_PROXY"
  integration_method   = "ANY"
  integration_uri      = aws_lb_listener.http.arn
  connection_type      = "VPC_LINK"
  connection_id        = aws_apigatewayv2_vpc_link.this.id
  timeout_milliseconds = 29000
}

# Authenticated by default; the service still enforces scopes (defense in depth).
resource "aws_apigatewayv2_route" "protected" {
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = "ANY /api/v1/{proxy+}"
  target             = "integrations/${aws_apigatewayv2_integration.alb.id}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.jwt.id
}

resource "aws_apigatewayv2_route" "public_availability" {
  api_id    = aws_apigatewayv2_api.this.id
  route_key = "GET /api/v1/events/{eventId}/availability"
  target    = "integrations/${aws_apigatewayv2_integration.alb.id}"
}

# SSE is reachable but capped by the 29 s integration timeout; EventSource reconnects automatically (README, limitations).
resource "aws_apigatewayv2_route" "public_availability_stream" {
  api_id    = aws_apigatewayv2_api.this.id
  route_key = "GET /api/v1/events/{eventId}/availability/stream"
  target    = "integrations/${aws_apigatewayv2_integration.alb.id}"
}

resource "aws_cloudwatch_log_group" "access" {
  name              = "/apigateway/${var.name}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = "$default"
  auto_deploy = true

  default_route_settings {
    throttling_burst_limit = var.throttling_burst_limit
    throttling_rate_limit  = var.throttling_rate_limit
  }

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.access.arn
    format = jsonencode({
      requestId = "$context.requestId", ip = "$context.identity.sourceIp", method = "$context.httpMethod",
      path      = "$context.path", status = "$context.status", latency = "$context.integrationLatency",
      sub       = "$context.authorizer.claims.sub"
    })
  }
}
