#!/usr/bin/env bash
# Build and push the shared API/worker ARM64 image to an EXISTING ECR repository.
# Does not create AWS infrastructure or modify Terraform state.
set -euo pipefail

REGION="${AWS_REGION:-us-east-1}"
REPOSITORY="${ECR_REPOSITORY:-ticketing-dev}"
ACCOUNT_EXPECTED="${AWS_ACCOUNT_ID:-190884856774}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

command -v aws >/dev/null || { echo "AWS CLI is required" >&2; exit 1; }
command -v docker >/dev/null || { echo "Docker is required" >&2; exit 1; }
docker buildx version >/dev/null
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
if [[ "$ACCOUNT" != "$ACCOUNT_EXPECTED" ]]; then
  echo "AWS account mismatch: expected $ACCOUNT_EXPECTED, got $ACCOUNT" >&2
  exit 1
fi

URI="$(aws ecr describe-repositories --region "$REGION" --repository-names "$REPOSITORY" --query 'repositories[0].repositoryUri' --output text)"
if [[ -z "$URI" || "$URI" == "None" ]]; then
  echo "ECR repository does not exist: $REPOSITORY" >&2
  exit 1
fi

TAG="$(git rev-parse --short HEAD)"
IMAGE="$URI:$TAG"
echo "Target image: $IMAGE"
echo "This will build and PUSH an image to AWS ECR (storage charges may apply)."
read -r -p "Type PUSH to continue: " ANSWER
[[ "$ANSWER" == "PUSH" ]] || { echo "Cancelled"; exit 0; }

aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "${URI%%/*}"
docker buildx build --platform linux/arm64 --file deployment/Dockerfile --tag "$IMAGE" --push .
echo "Published: $IMAGE"
echo "Deploy ECS separately only after a reviewed Terraform plan and explicit approval."
