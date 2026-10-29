output "api_endpoint" {
  value = module.edge.api_endpoint
}

output "ecr_repository_url" {
  value = module.compute.ecr_repository_url
}

output "jwt_issuer_uri" {
  value = module.identity.issuer_uri
}
