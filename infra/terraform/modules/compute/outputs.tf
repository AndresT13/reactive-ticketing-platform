output "ecr_repository_url" {
  value = aws_ecr_repository.this.repository_url
}

output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "service_names" {
  value = { api = aws_ecs_service.api.name, worker = aws_ecs_service.worker.name }
}
