variable "name" {
  type = string
}

variable "region" {
  type = string
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "alarm_emails" {
  description = "Addresses subscribed to the alarm topic (confirmation required)."
  type        = list(string)
  default     = []
}
