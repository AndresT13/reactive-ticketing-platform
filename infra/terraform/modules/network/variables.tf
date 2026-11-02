variable "name" {
  description = "Prefix for resource names."
  type        = string
}

variable "region" {
  description = "AWS region, used to build VPC endpoint service names."
  type        = string
}

variable "cidr_block" {
  description = "VPC CIDR block."
  type        = string
  default     = "10.0.0.0/16"
}

variable "az_count" {
  description = "Number of availability zones (and private subnets)."
  type        = number
  default     = 3

  validation {
    condition     = var.az_count >= 2 && var.az_count <= 3
    error_message = "Use 2 or 3 availability zones."
  }
}

variable "log_retention_days" {
  description = "Retention of the VPC flow logs."
  type        = number
  default     = 365
}

variable "kms_key_arn" {
  description = "KMS key used to encrypt the flow logs."
  type        = string
}

# Pin these to the AZs already in use before deployment into an existing VPC.
# Null preserves the previous selection and avoids unplanned subnet replacement.
variable "availability_zone_names" {
  description = "Optional explicit AWS availability zone names in subnet index order."
  type        = list(string)
  default     = null

  validation {
    condition = var.availability_zone_names == null ? true : (
      length(var.availability_zone_names) == var.az_count &&
      length(distinct(var.availability_zone_names)) == var.az_count
    )
    error_message = "Provide exactly az_count distinct availability zone names."
  }
}
