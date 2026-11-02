variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "vpc_cidr_block" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "jwt_issuer_uri" {
  type = string
}

variable "jwt_audiences" {
  description = "Cognito app client ids accepted by the JWT authorizer."
  type        = list(string)
}

variable "kms_key_arn" {
  type = string
}

variable "log_retention_days" {
  type    = number
  default = 365
}

variable "throttling_rate_limit" {
  type    = number
  default = 500
}

variable "throttling_burst_limit" {
  type    = number
  default = 1000
}

variable "waf_rate_limit_per_5min" {
  type    = number
  default = 2000
}

variable "deletion_protection" {
  type    = bool
  default = true
}

# Supply both fields together only after provisioning a valid ACM certificate.
# Null preserves the existing HTTP private integration until TLS can be deployed.
variable "alb_tls" {
  description = "Optional verified HTTPS configuration for the private ALB."
  type = object({
    certificate_arn = string
    server_name     = string
  })
  default = null

  validation {
    condition = var.alb_tls == null ? true : (
      can(regex("^arn:aws[a-z-]*:acm:[^:]+:[0-9]{12}:certificate/.+", var.alb_tls.certificate_arn)) &&
      length(trimspace(var.alb_tls.server_name)) > 0
    )
    error_message = "Provide a valid ACM certificate ARN and non-empty TLS server name."
  }
}
