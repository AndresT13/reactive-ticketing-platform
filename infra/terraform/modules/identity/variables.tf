variable "name" {
  type = string
}

variable "region" {
  type = string
}

variable "domain_prefix" {
  description = "Globally unique Cognito hosted UI domain prefix."
  type        = string
}

variable "callback_urls" {
  description = "OAuth2 redirect URIs of the web client."
  type        = list(string)
}

variable "deletion_protection" {
  type    = bool
  default = true
}
