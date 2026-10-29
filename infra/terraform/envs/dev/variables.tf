variable "region" {
  type    = string
  default = "us-east-1"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "owner" {
  type = string
}

variable "cost_center" {
  type = string
}

variable "image_tag" {
  description = "Image tag to deploy (git SHA). CI passes it with -var."
  type        = string
}

variable "cognito_domain_prefix" {
  type = string
}

variable "callback_urls" {
  type = list(string)
}

variable "alarm_emails" {
  type    = list(string)
  default = []
}
