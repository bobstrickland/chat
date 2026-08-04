# ---------------------------------------------------------------------------
# Terraform state backend — the one config that CANNOT use the S3 backend,
# because it is what creates it.
#
# This exists to break the chicken-and-egg that `environments/dev` used to have:
# that config declared the state bucket + lock table it was simultaneously
# trying to store its state in, so a first apply meant commenting out the
# backend block, applying, uncommenting, and re-initing. Now: apply this once,
# by hand, with local state, and every other config just points at it.
#
#   cd terraform/bootstrap && terraform init && terraform apply
#
# Its own state stays LOCAL and is gitignored (*.tfstate). That's deliberate and
# safe: everything here is trivially reproducible (an empty bucket and an empty
# lock table) and `prevent_destroy` guards the bucket. If this state file is ever
# lost, `terraform import` two resources — or just leave it, since nothing else
# depends on this config's outputs at plan time.
#
# Consumers reference these by NAME, not by remote-state lookup (see
# environments/dev/backend.tf) — backend blocks can't interpolate, so the names
# are fixed strings in both places. Change one, change the other.
# ---------------------------------------------------------------------------

terraform {
  required_version = ">= 1.7.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.region
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "account_id" {
  type        = string
  description = "AWS account id — part of the state bucket name (globally unique)."
  default     = "721131331297"
}

locals {
  bucket_name = "chat-app-terraform-state-${var.account_id}"

  tags = {
    Project   = "chat-app"
    ManagedBy = "terraform"
    Component = "tf-backend"
  }
}

resource "aws_s3_bucket" "terraform_state" {
  bucket = local.bucket_name
  tags   = local.tags

  # State is the only record of what exists in the account. Losing this bucket
  # orphans every managed resource, so make it un-deletable by accident.
  lifecycle {
    prevent_destroy = true
  }
}

# Versioning is what makes a corrupted or truncated state recoverable — a
# botched apply can be rolled back to the previous object version.
resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  versioning_configuration {
    status = "Enabled"
  }
}

# State plainly contains secrets (any `sensitive` value lands here in the clear),
# so encrypt at rest. SSE-S3 rather than the app KMS key on purpose: that key is
# created by `environments/dev`, which would put a dependency from the backend
# onto a config that stores its state in the backend — the same cycle this
# directory exists to remove.
resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket                  = aws_s3_bucket.terraform_state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# State locking needs no resource here: both backends use S3-native locking
# (`use_lockfile = true`), so Terraform writes a `.tflock` object next to the
# state object itself. The old `aws_dynamodb_table.terraform_locks` was removed
# on 2026-08-03 — the `dynamodb_table` backend argument is deprecated as of
# Terraform 1.11 and warned on every command. Don't add a lock table back
# without also changing both backend blocks; having one without the other means
# no locking at all.

output "state_bucket" {
  value = aws_s3_bucket.terraform_state.id
}
