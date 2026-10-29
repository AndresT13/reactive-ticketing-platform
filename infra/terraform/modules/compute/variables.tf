variable "name" {
  type = string
}

variable "region" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "alb_security_group_id" {
  type = string
}

variable "target_group_arn" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "image_tag" {
  description = "Immutable image tag (the git SHA) deployed by CI."
  type        = string
}

variable "jwt_issuer_uri" {
  type = string
}

variable "table_names" {
  type = map(string)
}

variable "table_arns" {
  type = map(string)
}

variable "queue_urls" {
  type = map(string)
}

variable "queue_arns" {
  type = map(string)
}

variable "queue_names" {
  type = map(string)
}

variable "log_group_names" {
  type = map(string)
}

variable "api_cpu" {
  type    = number
  default = 512
}

variable "api_memory" {
  type    = number
  default = 1024
}

variable "worker_cpu" {
  type    = number
  default = 512
}

variable "worker_memory" {
  type    = number
  default = 1024
}

variable "api_min_tasks" {
  type    = number
  default = 2
}

variable "api_max_tasks" {
  type    = number
  default = 10
}

variable "worker_min_tasks" {
  type    = number
  default = 1
}

variable "worker_max_tasks" {
  type    = number
  default = 10
}

variable "worker_backlog_per_task" {
  description = "Acceptable visible messages per worker task before scaling out."
  type        = number
  default     = 100
}
