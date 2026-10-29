# dev: same modules as staging/prod, only the sizes and protections change.
locals {
  name = "ticketing-${var.environment}"
}

module "observability" {
  source             = "../../modules/observability"
  name               = local.name
  region             = var.region
  log_retention_days = 30
  alarm_emails       = var.alarm_emails
}

module "network" {
  source             = "../../modules/network"
  name               = local.name
  region             = var.region
  az_count           = 2
  kms_key_arn        = module.observability.kms_key_arn
  log_retention_days = 30
}

module "data" {
  source              = "../../modules/data"
  name                = local.name
  kms_key_arn         = module.observability.kms_key_arn
  deletion_protection = false
}

module "messaging" {
  source          = "../../modules/messaging"
  name            = local.name
  kms_key_arn     = module.observability.kms_key_arn
  alarm_topic_arn = module.observability.alarm_topic_arn
}

module "identity" {
  source              = "../../modules/identity"
  name                = local.name
  region              = var.region
  domain_prefix       = var.cognito_domain_prefix
  callback_urls       = var.callback_urls
  deletion_protection = false
}

module "edge" {
  source              = "../../modules/edge"
  name                = local.name
  vpc_id              = module.network.vpc_id
  vpc_cidr_block      = module.network.vpc_cidr_block
  private_subnet_ids  = module.network.private_subnet_ids
  jwt_issuer_uri      = module.identity.issuer_uri
  jwt_audiences       = module.identity.client_ids
  kms_key_arn         = module.observability.kms_key_arn
  deletion_protection = false
}

module "compute" {
  source                = "../../modules/compute"
  name                  = local.name
  region                = var.region
  vpc_id                = module.network.vpc_id
  private_subnet_ids    = module.network.private_subnet_ids
  alb_security_group_id = module.edge.alb_security_group_id
  target_group_arn      = module.edge.target_group_arn
  kms_key_arn           = module.observability.kms_key_arn
  image_tag             = var.image_tag
  jwt_issuer_uri        = module.identity.issuer_uri
  table_names           = module.data.table_names
  table_arns            = module.data.table_arns
  queue_urls            = module.messaging.queue_urls
  queue_arns            = module.messaging.queue_arns
  queue_names           = module.messaging.queue_names
  log_group_names       = module.observability.log_group_names

  api_min_tasks    = 1
  api_max_tasks    = 4
  worker_min_tasks = 1
  worker_max_tasks = 4
}
