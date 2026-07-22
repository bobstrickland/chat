#!/usr/bin/env bash
# Creates the 6 application tables against DynamoDB Local.
# Schemas mirror terraform/modules/dynamodb/main.tf exactly — keep both in sync.
set -euo pipefail

ENDPOINT="${DYNAMODB_ENDPOINT:-http://localhost:8000}"
REGION="${AWS_REGION:-us-east-1}"

export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-local}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-local}"

DDB="aws dynamodb --endpoint-url ${ENDPOINT} --region ${REGION}"

table_exists() {
  $DDB describe-table --table-name "$1" >/dev/null 2>&1
}

create_users_table() {
  local name="users-local"
  if table_exists "$name"; then
    echo "skip: $name already exists"
    return
  fi
  echo "creating: $name"
  $DDB create-table \
    --table-name "$name" \
    --attribute-definitions AttributeName=email,AttributeType=S \
    --key-schema AttributeName=email,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST
}

create_profiles_table() {
  local name="profiles-local"
  if table_exists "$name"; then
    echo "skip: $name already exists"
    return
  fi
  echo "creating: $name"
  $DDB create-table \
    --table-name "$name" \
    --attribute-definitions AttributeName=userId,AttributeType=S \
    --key-schema AttributeName=userId,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST
}

create_conversations_table() {
  local name="conversations-local"
  if table_exists "$name"; then
    echo "skip: $name already exists"
    return
  fi
  echo "creating: $name"
  # Single-table design: PK conversationId, SK sk (meta / member#{userId} / timestamp#{messageId})
  # GSI gsi-user-conversations: PK userId, SK conversationId — "all conversations for a user"
  $DDB create-table \
    --table-name "$name" \
    --attribute-definitions \
        AttributeName=conversationId,AttributeType=S \
        AttributeName=sk,AttributeType=S \
        AttributeName=userId,AttributeType=S \
    --key-schema \
        AttributeName=conversationId,KeyType=HASH \
        AttributeName=sk,KeyType=RANGE \
    --global-secondary-indexes \
        '[{
          "IndexName": "gsi-user-conversations",
          "KeySchema": [
            {"AttributeName": "userId", "KeyType": "HASH"},
            {"AttributeName": "conversationId", "KeyType": "RANGE"}
          ],
          "Projection": {"ProjectionType": "ALL"}
        }]' \
    --billing-mode PAY_PER_REQUEST \
    --stream-specification StreamEnabled=true,StreamViewType=NEW_AND_OLD_IMAGES
}

create_presence_connections_table() {
  local name="presence-connections-local"
  if table_exists "$name"; then
    echo "skip: $name already exists"
    return
  fi
  echo "creating: $name"
  $DDB create-table \
    --table-name "$name" \
    --attribute-definitions \
        AttributeName=userId,AttributeType=S \
        AttributeName=connectionId,AttributeType=S \
    --key-schema \
        AttributeName=userId,KeyType=HASH \
        AttributeName=connectionId,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST

  echo "enabling TTL on: $name (attribute: expiresAt)"
  $DDB update-time-to-live \
    --table-name "$name" \
    --time-to-live-specification "Enabled=true,AttributeName=expiresAt"
}

create_device_tokens_table() {
  local name="device-tokens-local"
  if table_exists "$name"; then
    echo "skip: $name already exists"
    return
  fi
  echo "creating: $name"
  $DDB create-table \
    --table-name "$name" \
    --attribute-definitions \
        AttributeName=userId,AttributeType=S \
        AttributeName=deviceId,AttributeType=S \
    --key-schema \
        AttributeName=userId,KeyType=HASH \
        AttributeName=deviceId,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST
}

create_media_metadata_table() {
  local name="media-metadata-local"
  if table_exists "$name"; then
    echo "skip: $name already exists"
    return
  fi
  echo "creating: $name"
  $DDB create-table \
    --table-name "$name" \
    --attribute-definitions AttributeName=mediaId,AttributeType=S \
    --key-schema AttributeName=mediaId,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST
}

echo "Initializing DynamoDB Local at ${ENDPOINT} (region ${REGION})"
echo

create_users_table
create_profiles_table
create_conversations_table
create_presence_connections_table
create_device_tokens_table
create_media_metadata_table

echo
echo "Done. Tables:"
$DDB list-tables --output text