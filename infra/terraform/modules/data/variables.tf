variable "name" {
  description = "Prefix for table names."
  type        = string
}

variable "kms_key_arn" {
  description = "Customer managed KMS key for server-side encryption."
  type        = string
}

variable "deletion_protection" {
  description = "Protect tables against accidental deletion (true outside dev)."
  type        = bool
  default     = true
}
