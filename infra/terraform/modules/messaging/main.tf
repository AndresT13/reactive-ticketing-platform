# SQS Standard (at-least-once) with a DLQ per queue and SSE-KMS (ADR-005, ADR-016).
locals {
  queues = {
    reservation-requests = { visibility_timeout = 60 }
    payment-requests     = { visibility_timeout = 90 }
  }
}

resource "aws_sqs_queue" "dlq" {
  for_each                          = local.queues
  name                              = "${var.name}-${each.key}-dlq"
  message_retention_seconds         = 1209600
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = 300
}

resource "aws_sqs_queue" "this" {
  for_each                          = local.queues
  name                              = "${var.name}-${each.key}"
  visibility_timeout_seconds        = each.value.visibility_timeout
  receive_wait_time_seconds         = 20
  message_retention_seconds         = 345600
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = 300

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = var.max_receive_count
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  for_each  = local.queues
  queue_url = aws_sqs_queue.dlq[each.key].id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.this[each.key].arn]
  })
}

# Any message in a DLQ is a poison message that needs a human.
resource "aws_cloudwatch_metric_alarm" "dlq_not_empty" {
  for_each            = local.queues
  alarm_name          = "${var.name}-${each.key}-dlq-not-empty"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.dlq[each.key].name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "oldest_message" {
  for_each            = local.queues
  alarm_name          = "${var.name}-${each.key}-oldest-message"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = aws_sqs_queue.this[each.key].name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  threshold           = 120
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}
