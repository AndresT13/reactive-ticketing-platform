# One table per aggregate, on-demand, PITR, SSE-KMS and deletion protection (ADR-003).
locals {
  simple_tables = {
    events      = "eventId"
    inventory   = "eventId"
    guards      = "guardKey"
    idempotency = "scopedKey"
  }
}

resource "aws_dynamodb_table" "simple" {
  for_each                    = local.simple_tables
  name                        = "${var.name}-${each.key}"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = each.value
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = each.value
    type = "S"
  }

  dynamic "ttl" {
    for_each = each.key == "idempotency" ? [1] : []
    content {
      attribute_name = "ttl"
      enabled        = true
    }
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }
}

resource "aws_dynamodb_table" "orders" {
  name                        = "${var.name}-orders"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "orderId"
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = "orderId"
    type = "S"
  }
  attribute {
    name = "deadlineKey"
    type = "S"
  }
  attribute {
    name = "deadlineAt"
    type = "N"
  }
  attribute {
    name = "customerId"
    type = "S"
  }
  attribute {
    name = "createdAt"
    type = "N"
  }

  # Sparse index: only non-final orders carry deadlineKey (STATUS#bucket) and deadlineAt (ADR-007).
  global_secondary_index {
    name            = "deadline-index"
    hash_key        = "deadlineKey"
    range_key       = "deadlineAt"
    projection_type = "ALL"
  }

  global_secondary_index {
    name            = "customer-index"
    hash_key        = "customerId"
    range_key       = "createdAt"
    projection_type = "KEYS_ONLY"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }
}

resource "aws_dynamodb_table" "tickets" {
  name                        = "${var.name}-tickets"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "ticketId"
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = "ticketId"
    type = "S"
  }
  attribute {
    name = "orderId"
    type = "S"
  }

  global_secondary_index {
    name            = "order-index"
    hash_key        = "orderId"
    projection_type = "ALL"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }
}

resource "aws_dynamodb_table" "audit" {
  name                        = "${var.name}-audit"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "aggregateId"
  range_key                   = "sequence"
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = "aggregateId"
    type = "S"
  }
  attribute {
    name = "sequence"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }
}
