# Remote state per environment: its own S3 bucket/key, encrypted, versioned, with native S3 locking (Terraform >= 1.10).
# The bucket is created once per account (bootstrap) and passed at init time:
#   terraform init -backend-config="bucket=<state-bucket>"
terraform {
  backend "s3" {
    key          = "reactive-ticketing/dev/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
