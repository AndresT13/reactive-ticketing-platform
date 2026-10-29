output "table_names" {
  description = "Table names keyed by logical name (events, inventory, orders, tickets, audit, idempotency, guards)."
  value = merge(
    { for k, t in aws_dynamodb_table.simple : k => t.name },
    { orders = aws_dynamodb_table.orders.name, tickets = aws_dynamodb_table.tickets.name, audit = aws_dynamodb_table.audit.name },
  )
}

output "table_arns" {
  description = "Table ARNs keyed by logical name."
  value = merge(
    { for k, t in aws_dynamodb_table.simple : k => t.arn },
    { orders = aws_dynamodb_table.orders.arn, tickets = aws_dynamodb_table.tickets.arn, audit = aws_dynamodb_table.audit.arn },
  )
}
