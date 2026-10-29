variable "name" {
  description = "Prefix for queue names."
  type        = string
}

variable "kms_key_arn" {
  description = "Customer managed KMS key for SSE."
  type        = string
}

variable "max_receive_count" {
  description = "Deliveries before a message moves to the DLQ."
  type        = number
  default     = 5
}

variable "alarm_topic_arn" {
  description = "SNS topic for alarms."
  type        = string
}
