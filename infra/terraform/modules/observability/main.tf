# Shared KMS key, alarm topic and log groups. Created first because every other module encrypts with this key.
data "aws_caller_identity" "current" {}

resource "aws_kms_key" "this" {
  description             = "${var.name} data encryption (DynamoDB, SQS, logs, SNS)"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AccountAdministration"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "CloudWatchLogs"
        Effect    = "Allow"
        Principal = { Service = "logs.${var.region}.amazonaws.com" }
        Action    = ["kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey"]
        Resource  = "*"
      },
      {
        Sid       = "AlarmsToEncryptedTopic"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
        Action    = ["kms:Decrypt", "kms:GenerateDataKey*"]
        Resource  = "*"
      }
    ]
  })
}

resource "aws_kms_alias" "this" {
  name          = "alias/${var.name}"
  target_key_id = aws_kms_key.this.key_id
}

resource "aws_sns_topic" "alarms" {
  name              = "${var.name}-alarms"
  kms_master_key_id = aws_kms_key.this.arn
}

resource "aws_sns_topic_subscription" "email" {
  for_each  = toset(var.alarm_emails)
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = each.value
}

resource "aws_cloudwatch_log_group" "service" {
  for_each          = toset(["api", "worker"])
  name              = "/ecs/${var.name}/${each.value}"
  retention_in_days = var.log_retention_days
  kms_key_id        = aws_kms_key.this.arn
}
