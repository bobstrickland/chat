locals {
  services = ["auth", "profile", "messaging-conversations", "presence-connection", "notification", "media", "search"]
}

# ---- Lambda assume-role trust policy (shared by all service roles) ----
data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

# ---- One execution role per service ----
resource "aws_iam_role" "service" {
  for_each           = toset(local.services)
  name               = "${var.name_prefix}-${each.key}-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
  tags               = merge(var.tags, { Service = each.key })
}

resource "aws_iam_role_policy_attachment" "basic_execution" {
  for_each   = toset(local.services)
  role       = aws_iam_role.service[each.key].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "vpc_access" {
  for_each   = toset(local.services)
  role       = aws_iam_role.service[each.key].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

# ---- Per-service scoped DynamoDB access ----
data "aws_iam_policy_document" "dynamodb_access" {
  for_each = var.dynamodb_table_arns
  statement {
    sid = "DynamoDBAccess"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:DeleteItem",
      "dynamodb:Query",
      "dynamodb:BatchGetItem",
      "dynamodb:BatchWriteItem",
    ]
    resources = concat(each.value, [for arn in each.value : "${arn}/index/*"])
  }
}

resource "aws_iam_role_policy" "dynamodb" {
  for_each = var.dynamodb_table_arns
  name     = "${var.name_prefix}-${each.key}-dynamodb"
  role     = aws_iam_role.service[each.key].id
  policy   = data.aws_iam_policy_document.dynamodb_access[each.key].json
}

# ---- KMS access (scoped) for all services with data at rest ----
data "aws_iam_policy_document" "kms_access" {
  statement {
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [var.kms_key_arn]
  }
}

resource "aws_iam_role_policy" "kms" {
  for_each = toset(local.services)
  name     = "${var.name_prefix}-${each.key}-kms"
  role     = aws_iam_role.service[each.key].id
  policy   = data.aws_iam_policy_document.kms_access.json
}

# ---- Secrets Manager, scoped to own path prefix ----
data "aws_iam_policy_document" "secrets_access" {
  for_each = toset(local.services)
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = ["arn:aws:secretsmanager:${var.region}:${var.account_id}:secret:${each.key}/*"]
  }
}

resource "aws_iam_role_policy" "secrets" {
  for_each = toset(local.services)
  name     = "${var.name_prefix}-${each.key}-secrets"
  role     = aws_iam_role.service[each.key].id
  policy   = data.aws_iam_policy_document.secrets_access[each.key].json
}

# ---- MSK produce/consume: messaging-conversations, presence-connection, notification, search ----
locals {
  msk_services = ["messaging-conversations", "presence-connection", "notification", "search"]
}

data "aws_iam_policy_document" "msk_access" {
  statement {
    actions = [
      "kafka-cluster:Connect",
      "kafka-cluster:DescribeCluster",
    ]
    resources = [var.msk_cluster_arn]
  }
  statement {
    actions = [
      "kafka-cluster:*Topic*",
      "kafka-cluster:WriteData",
      "kafka-cluster:ReadData",
    ]
    resources = [replace(var.msk_cluster_arn, ":cluster/", ":topic/")]
  }
  statement {
    actions   = ["kafka-cluster:AlterGroup", "kafka-cluster:DescribeGroup"]
    resources = [replace(var.msk_cluster_arn, ":cluster/", ":group/")]
  }
}

resource "aws_iam_role_policy" "msk" {
  for_each = toset(local.msk_services)
  name     = "${var.name_prefix}-${each.key}-msk"
  role     = aws_iam_role.service[each.key].id
  policy   = data.aws_iam_policy_document.msk_access.json
}

# ---- API Gateway WebSocket ManageConnections: messaging-conversations, presence-connection ----
data "aws_iam_policy_document" "ws_manage_connections" {
  statement {
    actions   = ["execute-api:ManageConnections"]
    resources = ["arn:aws:execute-api:${var.region}:${var.account_id}:*/*/POST/@connections/*"]
  }
}

resource "aws_iam_role_policy" "ws_manage" {
  for_each = toset(["messaging-conversations", "presence-connection"])
  name     = "${var.name_prefix}-${each.key}-ws-manage"
  role     = aws_iam_role.service[each.key].id
  policy   = data.aws_iam_policy_document.ws_manage_connections.json
}

# ---- Media service: scoped S3 access ----
data "aws_iam_policy_document" "media_s3" {
  statement {
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${var.media_bucket_arn}/*"]
  }
  statement {
    actions   = ["s3:ListBucket"]
    resources = [var.media_bucket_arn]
  }
}

resource "aws_iam_role_policy" "media_s3" {
  name   = "${var.name_prefix}-media-s3"
  role   = aws_iam_role.service["media"].id
  policy = data.aws_iam_policy_document.media_s3.json
}

# ---- Auth service: scoped Cognito admin access ----
data "aws_iam_policy_document" "cognito_admin" {
  statement {
    actions = [
      "cognito-idp:AdminCreateUser",
      "cognito-idp:AdminGetUser",
      "cognito-idp:AdminInitiateAuth",
      "cognito-idp:AdminRespondToAuthChallenge",
      "cognito-idp:AdminSetUserMFAPreference",
      "cognito-idp:AdminUpdateUserAttributes",
      "cognito-idp:AdminDeleteUser",
    ]
    resources = [var.cognito_user_pool_arn]
  }
}

resource "aws_iam_role_policy" "cognito_admin" {
  name   = "${var.name_prefix}-auth-cognito"
  role   = aws_iam_role.service["auth"].id
  policy = data.aws_iam_policy_document.cognito_admin.json
}

# ---- Search service: scoped OpenSearch access ----
data "aws_iam_policy_document" "opensearch_access" {
  count = var.opensearch_domain_arn != "" ? 1 : 0
  statement {
    actions   = ["es:ESHttpGet", "es:ESHttpPost", "es:ESHttpPut", "es:ESHttpDelete"]
    resources = ["${var.opensearch_domain_arn}/*"]
  }
}

resource "aws_iam_role_policy" "opensearch" {
  count  = var.opensearch_domain_arn != "" ? 1 : 0
  name   = "${var.name_prefix}-search-opensearch"
  role   = aws_iam_role.service["search"].id
  policy = data.aws_iam_policy_document.opensearch_access[0].json
}
