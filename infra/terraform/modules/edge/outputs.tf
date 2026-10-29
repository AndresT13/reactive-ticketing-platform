output "api_endpoint" {
  value = aws_apigatewayv2_api.this.api_endpoint
}

output "alb_security_group_id" {
  value = aws_security_group.alb.id
}

output "target_group_arn" {
  value = aws_lb_target_group.api.arn
}
