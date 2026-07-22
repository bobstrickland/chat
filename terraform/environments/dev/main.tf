# ---------------------------------------------------------------------------
# NOTE ON BOOTSTRAP ORDERING:
# The S3 state bucket + DynamoDB lock table referenced in backend.tf must
# exist BEFORE `terraform init` can use them. Apply this file once with a
# local/no backend (comment out the backend block), or via `bootstrap/`
# (not included here) before switching to the S3 backend. Included below
# for completeness / re-creation reference, not first-run ordering.
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "terraform_state" {
  bucket = "chat-app-terraform-state-${var.account_id}"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
  }
}

resource "aws_dynamodb_table" "terraform_locks" {
  name         = "chat-app-terraform-locks"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }
}

# ---------------------------------------------------------------------------
# KMS — shared encryption key for DynamoDB / S3 / Secrets across services
# ---------------------------------------------------------------------------

resource "aws_kms_key" "app" {
  description             = "${var.name_prefix} application data encryption key"
  deletion_window_in_days = 30
  enable_key_rotation     = true
}

resource "aws_kms_alias" "app" {
  name          = "alias/${var.name_prefix}-app-key"
  target_key_id = aws_kms_key.app.key_id
}

# ---------------------------------------------------------------------------
# VPC
# ---------------------------------------------------------------------------

module "vpc" {
  source      = "../../modules/vpc"
  name_prefix = var.name_prefix
  tags        = local.tags
}

# ---------------------------------------------------------------------------
# DynamoDB
# ---------------------------------------------------------------------------

module "dynamodb" {
  source      = "../../modules/dynamodb"
  name_prefix = var.name_prefix
  kms_key_arn = aws_kms_key.app.arn
  tags        = local.tags
}

# ---------------------------------------------------------------------------
# MSK + topics (Mongey/kafka provider)
# ---------------------------------------------------------------------------

module "msk" {
  source              = "../../modules/msk"
  name_prefix         = var.name_prefix
  vpc_id              = module.vpc.vpc_id
  private_subnet_ids  = module.vpc.private_subnet_ids
  kms_key_arn         = aws_kms_key.app.arn
  # dev sizing — 2 brokers, small instance
  number_of_broker_nodes = 2
  broker_instance_type   = "kafka.t3.small"
  tags                    = local.tags
}

# ---------------------------------------------------------------------------
# DNS / ACM — wildcard cert for *.chat.rstrickland.dev, issued in us-east-1
# (required for CloudFront + Cognito Hosted UI regardless of app region)
# ---------------------------------------------------------------------------

module "dns_acm" {
  source          = "../../modules/dns_acm"
  root_domain     = var.root_domain
  wildcard_domain = var.wildcard_domain
  tags            = local.tags

  providers = {
    aws = aws.us_east_1
  }
}

# ---------------------------------------------------------------------------
# Cognito
# ---------------------------------------------------------------------------

module "cognito" {
  source                         = "../../modules/cognito"
  name_prefix                    = var.name_prefix
  auth_domain_prefix             = "dev-auth-chat-rstrickland-dev"
  custom_domain                  = "dev-auth.chat.rstrickland.dev"
  acm_certificate_arn_us_east_1  = module.dns_acm.certificate_arn
  mobile_callback_urls           = ["chatapp://callback"]
  web_callback_urls              = ["https://dev-app.chat.rstrickland.dev/callback"]
  web_logout_urls                = ["https://dev-app.chat.rstrickland.dev/logout"]

  google_client_id     = var.google_client_id
  google_client_secret = var.google_client_secret
  apple_client_id      = var.apple_client_id
  apple_team_id        = var.apple_team_id
  apple_key_id         = var.apple_key_id
  apple_private_key    = var.apple_private_key

  tags = local.tags
}

# ---------------------------------------------------------------------------
# IAM — 7 scoped service execution roles + shared access grants
# ---------------------------------------------------------------------------

module "iam" {
  source      = "../../modules/iam"
  name_prefix = var.name_prefix
  account_id  = var.account_id
  region      = var.region

  dynamodb_table_arns = {
    "auth"                     = [module.dynamodb.table_arns["users"]]
    "profile"                  = [module.dynamodb.table_arns["profiles"]]
    "messaging-conversations"  = [module.dynamodb.table_arns["conversations"]]
    "presence-connection"      = [module.dynamodb.table_arns["presence_connections"]]
    "notification"             = [module.dynamodb.table_arns["device_tokens"]]
    "media"                    = [module.dynamodb.table_arns["media_metadata"]]
    "search"                   = [] # reads via DynamoDB Streams, granted separately if needed
  }

  msk_cluster_arn        = module.msk.cluster_arn
  media_bucket_arn        = aws_s3_bucket.media.arn
  kms_key_arn             = aws_kms_key.app.arn
  cognito_user_pool_arn   = module.cognito.user_pool_arn

  tags = local.tags
}

# ---------------------------------------------------------------------------
# Media bucket (S3) — owned by Media service
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "media" {
  bucket = "${var.name_prefix}-media-${var.account_id}"
  tags   = local.tags
}

resource "aws_s3_bucket_server_side_encryption_configuration" "media" {
  bucket = aws_s3_bucket.media.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.app.arn
    }
  }
}

resource "aws_s3_bucket_public_access_block" "media" {
  bucket                  = aws_s3_bucket.media.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ---------------------------------------------------------------------------
# Web hosting bucket + CloudFront (basic scaffold — routing/behaviors TBD
# when the web client build output shape is known)
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "web" {
  bucket = "${var.name_prefix}-web-${var.account_id}"
  tags   = local.tags
}

resource "aws_s3_bucket_public_access_block" "web" {
  bucket                  = aws_s3_bucket.web.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_cloudfront_origin_access_control" "web" {
  name                              = "${var.name_prefix}-web-oac"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_distribution" "web" {
  enabled             = true
  default_root_object = "index.html"
  aliases             = ["dev-app.chat.rstrickland.dev"]

  origin {
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_id                = "web-s3-origin"
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
  }

  default_cache_behavior {
    target_origin_id       = "web-s3-origin"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods         = ["GET", "HEAD"]
    cached_methods           = ["GET", "HEAD"]

    forwarded_values {
      query_string = false
      cookies {
        forward = "none"
      }
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = module.dns_acm.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  tags = local.tags
}

resource "aws_route53_record" "app" {
  zone_id = module.dns_acm.zone_id
  name    = "dev-app.chat.rstrickland.dev"
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.web.domain_name
    zone_id                = aws_cloudfront_distribution.web.hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "auth" {
  zone_id = module.dns_acm.zone_id
  name    = "dev-auth.chat.rstrickland.dev"
  type    = "A"

  alias {
    name                   = module.cognito.hosted_ui_cloudfront_domain
    zone_id                = "Z2FDTNDATAQYW2" # CloudFront's fixed hosted zone ID (global, same for every distribution)
    evaluate_target_health = false
  }
}

# ---------------------------------------------------------------------------
# CI/CD — GitHub OIDC + VPC-attached CodeBuild for `terraform apply`
# (solves Mongey/kafka provider's need for broker reachability)
# ---------------------------------------------------------------------------

module "ci_cd" {
  source              = "../../modules/ci_cd"
  name_prefix         = var.name_prefix
  account_id          = var.account_id
  region              = var.region
  github_org          = var.github_org
  github_repo         = var.github_repo
  vpc_id              = module.vpc.vpc_id
  private_subnet_ids  = module.vpc.private_subnet_ids
  state_bucket_arn     = aws_s3_bucket.terraform_state.arn
  state_lock_table_arn = aws_dynamodb_table.terraform_locks.arn
  tags                  = local.tags
}

locals {
  tags = {
    Project     = "chat-app"
    Environment = "dev"
  }
}
