# Users — owned by Auth service
resource "aws_dynamodb_table" "users" {
  name         = "${var.name_prefix}-users"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "email"

  attribute {
    name = "email"
    type = "S"
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }

  point_in_time_recovery {
    enabled = true
  }

  tags = merge(var.tags, { Service = "auth" })
}

# Profiles — owned by Profile service
resource "aws_dynamodb_table" "profiles" {
  name         = "${var.name_prefix}-profiles"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "userId"

  attribute {
    name = "userId"
    type = "S"
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }

  point_in_time_recovery {
    enabled = true
  }

  tags = merge(var.tags, { Service = "profile" })
}

# Conversations — owned by Messaging & Conversations service
# Single-table design: SK prefix distinguishes meta / member#{userId} / timestamp#{messageId}
resource "aws_dynamodb_table" "conversations" {
  name         = "${var.name_prefix}-conversations"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "conversationId"
  range_key    = "sk"

  attribute {
    name = "conversationId"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  attribute {
    name = "userId"
    type = "S"
  }

  # GSI: "all conversations for a user" — queries member# items by userId
  global_secondary_index {
    name            = "gsi-user-conversations"
    hash_key        = "userId"
    range_key       = "conversationId"
    projection_type = "ALL"
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }

  point_in_time_recovery {
    enabled = true
  }

  stream_enabled   = true
  stream_view_type = "NEW_AND_OLD_IMAGES"

  tags = merge(var.tags, { Service = "messaging-conversations" })
}

# PresenceConnections — owned by Presence & Connection service
resource "aws_dynamodb_table" "presence_connections" {
  name         = "${var.name_prefix}-presence-connections"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "userId"
  range_key    = "connectionId"

  attribute {
    name = "userId"
    type = "S"
  }

  attribute {
    name = "connectionId"
    type = "S"
  }

  # $disconnect (and the ApiGatewayManagementApi) only knows the connectionId,
  # but the table is keyed by userId. This GSI resolves connectionId -> userId
  # so a disconnect can find and delete the right item. KEYS_ONLY is enough:
  # the projected table keys already include userId.
  global_secondary_index {
    name            = "gsi-connection"
    hash_key        = "connectionId"
    projection_type = "KEYS_ONLY"
  }

  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }

  tags = merge(var.tags, { Service = "presence-connection" })
}

# DeviceTokens — owned by Notification service
resource "aws_dynamodb_table" "device_tokens" {
  name         = "${var.name_prefix}-device-tokens"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "userId"
  range_key    = "deviceId"

  attribute {
    name = "userId"
    type = "S"
  }

  attribute {
    name = "deviceId"
    type = "S"
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }

  tags = merge(var.tags, { Service = "notification" })
}

# MediaMetadata — owned by Media service
resource "aws_dynamodb_table" "media_metadata" {
  name         = "${var.name_prefix}-media-metadata"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "mediaId"

  attribute {
    name = "mediaId"
    type = "S"
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }

  tags = merge(var.tags, { Service = "media" })
}
