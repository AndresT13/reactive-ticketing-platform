output "issuer_uri" {
  description = "JWT issuer, used by API Gateway and by Spring's resource server."
  value       = "https://cognito-idp.${var.region}.amazonaws.com/${aws_cognito_user_pool.this.id}"
}

output "user_pool_id" {
  value = aws_cognito_user_pool.this.id
}

output "client_ids" {
  value = [aws_cognito_user_pool_client.web.id, aws_cognito_user_pool_client.admin.id]
}
