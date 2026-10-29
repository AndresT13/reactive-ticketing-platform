output "kms_key_arn" {
  value = aws_kms_key.this.arn
}

output "alarm_topic_arn" {
  value = aws_sns_topic.alarms.arn
}

output "log_group_names" {
  value = { for k, g in aws_cloudwatch_log_group.service : k => g.name }
}
