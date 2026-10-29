# ECR + ECS Fargate: one image, two services (api / worker) with their own scaling and least-privilege roles.
data "aws_caller_identity" "current" {}

locals {
  image = "${aws_ecr_repository.this.repository_url}:${var.image_tag}"

  common_environment = [
    { name = "AWS_REGION", value = var.region },
    { name = "SPRING_PROFILES_ACTIVE", value = "aws" },
    { name = "TICKETING_JWT_ISSUER_URI", value = var.jwt_issuer_uri },
    { name = "TICKETING_QUEUES_RESERVATION_REQUESTS_URL", value = var.queue_urls["reservation-requests"] },
    { name = "TICKETING_QUEUES_PAYMENT_REQUESTS_URL", value = var.queue_urls["payment-requests"] },
    { name = "TICKETING_TABLES_EVENTS", value = var.table_names["events"] },
    { name = "TICKETING_TABLES_INVENTORY", value = var.table_names["inventory"] },
    { name = "TICKETING_TABLES_ORDERS", value = var.table_names["orders"] },
    { name = "TICKETING_TABLES_TICKETS", value = var.table_names["tickets"] },
    { name = "TICKETING_TABLES_AUDIT", value = var.table_names["audit"] },
    { name = "TICKETING_TABLES_IDEMPOTENCY", value = var.table_names["idempotency"] },
    { name = "TICKETING_TABLES_GUARDS", value = var.table_names["guards"] },
  ]

  roles = {
    api = {
      cpu    = var.api_cpu
      memory = var.api_memory
      env    = [{ name = "TICKETING_ROLES_API", value = "true" }, { name = "TICKETING_ROLES_WORKER", value = "false" }]
    }
    worker = {
      cpu    = var.worker_cpu
      memory = var.worker_memory
      env    = [{ name = "TICKETING_ROLES_API", value = "false" }, { name = "TICKETING_ROLES_WORKER", value = "true" }]
    }
  }
}

resource "aws_ecr_repository" "this" {
  name                 = var.name
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = var.kms_key_arn
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  repository = aws_ecr_repository.this.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 30 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 30 }
      action       = { type = "expire" }
    }]
  })
}

resource "aws_ecs_cluster" "this" {
  name = var.name

  setting {
    name  = "containerInsights"
    value = "enhanced"
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
}

# ---------- IAM ----------
data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.name}-task-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_kms" {
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = ["kms:Decrypt"], Resource = var.kms_key_arn }]
  })
}

resource "aws_iam_role" "task" {
  for_each           = local.roles
  name               = "${var.name}-${each.key}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

# API: reads, admission/transition transactions and publishing. No receive/delete on queues.
data "aws_iam_policy_document" "api" {
  statement {
    sid       = "DynamoDb"
    actions   = ["dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:Query", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:ConditionCheckItem"]
    resources = [for k, arn in var.table_arns : arn if k != "audit"]
  }
  statement {
    sid       = "DynamoDbIndexes"
    actions   = ["dynamodb:Query"]
    resources = [for arn in values(var.table_arns) : "${arn}/index/*"]
  }
  statement {
    sid       = "AuditAppendOnly"
    actions   = ["dynamodb:PutItem", "dynamodb:Query"]
    resources = [var.table_arns["audit"]]
  }
  statement {
    sid       = "Publish"
    actions   = ["sqs:SendMessage", "sqs:GetQueueAttributes"]
    resources = values(var.queue_arns)
  }
  statement {
    sid       = "Kms"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [var.kms_key_arn]
  }
}

# Worker: consumes the queues, re-dispatches and runs the housekeeping sweeps.
data "aws_iam_policy_document" "worker" {
  statement {
    sid       = "DynamoDb"
    actions   = ["dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:Query", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:ConditionCheckItem"]
    resources = [for k, arn in var.table_arns : arn if k != "audit"]
  }
  statement {
    sid       = "DynamoDbIndexes"
    actions   = ["dynamodb:Query"]
    resources = [for arn in values(var.table_arns) : "${arn}/index/*"]
  }
  statement {
    sid       = "AuditAppendOnly"
    actions   = ["dynamodb:PutItem", "dynamodb:Query"]
    resources = [var.table_arns["audit"]]
  }
  statement {
    sid       = "Consume"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes", "sqs:SendMessage"]
    resources = values(var.queue_arns)
  }
  statement {
    sid       = "Kms"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [var.kms_key_arn]
  }
}

resource "aws_iam_role_policy" "api" {
  role   = aws_iam_role.task["api"].id
  policy = data.aws_iam_policy_document.api.json
}

resource "aws_iam_role_policy" "worker" {
  role   = aws_iam_role.task["worker"].id
  policy = data.aws_iam_policy_document.worker.json
}

# TransactWriteItems is authorized per item action, so the statements above already cover it.

# ---------- Security groups ----------
resource "aws_security_group" "api" {
  name        = "${var.name}-api-tasks"
  description = "API tasks: inbound only from the internal ALB"
  vpc_id      = var.vpc_id

  ingress {
    description     = "HTTP from the ALB"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [var.alb_security_group_id]
  }

  egress {
    description = "HTTPS to VPC endpoints and the DynamoDB/S3 gateway prefix lists"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "worker" {
  name        = "${var.name}-worker-tasks"
  description = "Worker tasks: no inbound traffic"
  vpc_id      = var.vpc_id

  egress {
    description = "HTTPS to VPC endpoints and the DynamoDB/S3 gateway prefix lists"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# ---------- Task definitions & services ----------
resource "aws_ecs_task_definition" "this" {
  for_each                 = local.roles
  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task[each.key].arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name                   = each.key
    image                  = local.image
    essential              = true
    readonlyRootFilesystem = true
    portMappings           = [{ containerPort = 8080, protocol = "tcp" }]
    environment            = concat(local.common_environment, each.value.env)
    mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp" }]
    healthCheck = {
      command     = ["CMD-SHELL", "curl -fs http://localhost:8080/actuator/health/liveness || exit 1"]
      interval    = 15
      timeout     = 3
      retries     = 3
      startPeriod = 40
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = var.log_group_names[each.key]
        awslogs-region        = var.region
        awslogs-stream-prefix = each.key
      }
    }
  }])

  volume {
    name = "tmp"
  }
}

resource "aws_ecs_service" "api" {
  name                   = "${var.name}-api"
  cluster                = aws_ecs_cluster.this.id
  task_definition        = aws_ecs_task_definition.this["api"].arn
  desired_count          = var.api_min_tasks
  launch_type            = "FARGATE"
  enable_execute_command = false

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.api.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = "api"
    container_port   = 8080
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    ignore_changes = [desired_count]
  }
}

resource "aws_ecs_service" "worker" {
  name                   = "${var.name}-worker"
  cluster                = aws_ecs_cluster.this.id
  task_definition        = aws_ecs_task_definition.this["worker"].arn
  desired_count          = var.worker_min_tasks
  enable_execute_command = false

  # The consumer is idempotent and SQS redelivers, so Spot interruptions are safe (ADR-011).
  capacity_provider_strategy {
    capacity_provider = "FARGATE"
    base              = 1
    weight            = 30
  }
  capacity_provider_strategy {
    capacity_provider = "FARGATE_SPOT"
    weight            = 70
  }

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.worker.id]
    assign_public_ip = false
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [aws_ecs_cluster_capacity_providers.this]
}

# ---------- Auto scaling ----------
resource "aws_appautoscaling_target" "api" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.api.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.api_min_tasks
  max_capacity       = var.api_max_tasks
}

resource "aws_appautoscaling_policy" "api_cpu" {
  name               = "${var.name}-api-cpu"
  service_namespace  = aws_appautoscaling_target.api.service_namespace
  resource_id        = aws_appautoscaling_target.api.resource_id
  scalable_dimension = aws_appautoscaling_target.api.scalable_dimension
  policy_type        = "TargetTrackingScaling"

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 120
    scale_out_cooldown = 30
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

resource "aws_appautoscaling_target" "worker" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.worker.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.worker_min_tasks
  max_capacity       = var.worker_max_tasks
}

# Scale the worker on the reservation backlog (visible messages); target tracking keeps ~backlog_per_task per task.
resource "aws_appautoscaling_policy" "worker_backlog" {
  name               = "${var.name}-worker-backlog"
  service_namespace  = aws_appautoscaling_target.worker.service_namespace
  resource_id        = aws_appautoscaling_target.worker.resource_id
  scalable_dimension = aws_appautoscaling_target.worker.scalable_dimension
  policy_type        = "TargetTrackingScaling"

  target_tracking_scaling_policy_configuration {
    target_value       = var.worker_backlog_per_task
    scale_in_cooldown  = 120
    scale_out_cooldown = 30

    customized_metric_specification {
      metrics {
        id          = "backlog"
        return_data = false
        metric_stat {
          stat = "Sum"
          metric {
            namespace   = "AWS/SQS"
            metric_name = "ApproximateNumberOfMessagesVisible"
            dimensions {
              name  = "QueueName"
              value = var.queue_names["reservation-requests"]
            }
          }
        }
      }
      metrics {
        id          = "tasks"
        return_data = false
        metric_stat {
          stat = "Average"
          metric {
            namespace   = "ECS/ContainerInsights"
            metric_name = "RunningTaskCount"
            dimensions {
              name  = "ClusterName"
              value = aws_ecs_cluster.this.name
            }
            dimensions {
              name  = "ServiceName"
              value = aws_ecs_service.worker.name
            }
          }
        }
      }
      metrics {
        id          = "backlogPerTask"
        expression  = "backlog / IF(tasks > 0, tasks, 1)"
        label       = "Backlog per task"
        return_data = true
      }
    }
  }
}
