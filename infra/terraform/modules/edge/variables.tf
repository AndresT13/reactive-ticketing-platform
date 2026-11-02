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
  default = 30
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
