#!/bin/sh
# Creates the DynamoDB tables (DynamoDB Local) and the SQS queues with their DLQs (LocalStack).
# Idempotent: it can run again on an existing environment.
set -eu

DDB="aws dynamodb --endpoint-url ${DYNAMODB_ENDPOINT:-http://dynamodb-local:8000} --region ${AWS_REGION:-us-east-1}"
SQS="aws sqs --endpoint-url ${SQS_ENDPOINT:-http://localstack:4566} --region ${AWS_REGION:-us-east-1}"

echo "Waiting for DynamoDB Local and LocalStack..."
until $DDB list-tables >/dev/null 2>&1; do sleep 1; done
until $SQS list-queues >/dev/null 2>&1; do sleep 1; done

create_table() {
  name="$1"; shift
  if $DDB describe-table --table-name "$name" >/dev/null 2>&1; then
    echo "Table $name already exists"
  else
    $DDB create-table --table-name "$name" --billing-mode PAY_PER_REQUEST "$@" >/dev/null
    echo "Created table $name"
  fi
}

create_table ticketing-events \
  --attribute-definitions AttributeName=eventId,AttributeType=S \
  --key-schema AttributeName=eventId,KeyType=HASH

create_table ticketing-inventory \
  --attribute-definitions AttributeName=eventId,AttributeType=S \
  --key-schema AttributeName=eventId,KeyType=HASH

# Sparse GSI: only non-final orders carry deadlineKey/deadlineAt.
create_table ticketing-orders \
  --attribute-definitions AttributeName=orderId,AttributeType=S AttributeName=deadlineKey,AttributeType=S \
                          AttributeName=deadlineAt,AttributeType=N AttributeName=customerId,AttributeType=S \
                          AttributeName=createdAt,AttributeType=N \
  --key-schema AttributeName=orderId,KeyType=HASH \
  --global-secondary-indexes \
    '[{"IndexName":"deadline-index","KeySchema":[{"AttributeName":"deadlineKey","KeyType":"HASH"},{"AttributeName":"deadlineAt","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}},
      {"IndexName":"customer-index","KeySchema":[{"AttributeName":"customerId","KeyType":"HASH"},{"AttributeName":"createdAt","KeyType":"RANGE"}],"Projection":{"ProjectionType":"KEYS_ONLY"}}]'

create_table ticketing-tickets \
  --attribute-definitions AttributeName=ticketId,AttributeType=S AttributeName=orderId,AttributeType=S \
  --key-schema AttributeName=ticketId,KeyType=HASH \
  --global-secondary-indexes \
    '[{"IndexName":"order-index","KeySchema":[{"AttributeName":"orderId","KeyType":"HASH"}],"Projection":{"ProjectionType":"ALL"}}]'

create_table ticketing-audit \
  --attribute-definitions AttributeName=aggregateId,AttributeType=S AttributeName=sequence,AttributeType=S \
  --key-schema AttributeName=aggregateId,KeyType=HASH AttributeName=sequence,KeyType=RANGE

create_table ticketing-idempotency-keys \
  --attribute-definitions AttributeName=scopedKey,AttributeType=S \
  --key-schema AttributeName=scopedKey,KeyType=HASH
$DDB update-time-to-live --table-name ticketing-idempotency-keys \
  --time-to-live-specification Enabled=true,AttributeName=ttl >/dev/null 2>&1 || true

create_table ticketing-order-guards \
  --attribute-definitions AttributeName=guardKey,AttributeType=S \
  --key-schema AttributeName=guardKey,KeyType=HASH

create_queue() {
  name="$1"; visibility="$2"
  dlq_url=$($SQS create-queue --queue-name "$name-dlq" --attributes MessageRetentionPeriod=1209600 --query QueueUrl --output text)
  dlq_arn=$($SQS get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn --query Attributes.QueueArn --output text)
  redrive="{\"deadLetterTargetArn\":\"$dlq_arn\",\"maxReceiveCount\":\"5\"}"
  attributes=$(printf '{"VisibilityTimeout":"%s","ReceiveMessageWaitTimeSeconds":"20","MessageRetentionPeriod":"345600","RedrivePolicy":"%s"}' \
    "$visibility" "$(echo "$redrive" | sed 's/"/\\"/g')")
  $SQS create-queue --queue-name "$name" --attributes "$attributes" >/dev/null
  echo "Created queue $name (+ DLQ)"
}

create_queue ticketing-reservation-requests 60
create_queue ticketing-payment-requests 90

echo "Local AWS resources are ready."
