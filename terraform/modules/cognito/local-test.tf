# ---------------------------------------------------------------------------
# Standalone-root wiring for THIS directory.
#
# `modules/cognito` is used two ways: as a child module (from
# `environments/dev`), and — because Cognito has no usable local emulator — as a
# root config applied directly from here, which is how the live dev User Pool
# was created (CLAUDE.md "Auth"). The blocks below serve only the second use.
# When the directory is consumed as a child module, Terraform IGNORES both the
# provider and the backend declared here (it warns about the backend); the
# environment root's own provider and backend win.
#
#   cd terraform/modules/cognito && terraform apply    # vars: dev-local.auto.tfvars
#
# The state lives in S3, NOT next to this file. It holds `google_client_secret`
# in plaintext — as every `sensitive` value does, since `sensitive` only redacts
# CLI output — so a local `terraform.tfstate` meant an unencrypted, unversioned,
# un-backed-up copy of a live OAuth secret on a single laptop, and that file is
# simultaneously the only handle on the live pool. In S3 it is encrypted at
# rest, versioned, and lockable. The bucket comes from `terraform/bootstrap`.
#
# The state KEY is deliberately distinct from `env/dev/terraform.tfstate`: this
# config and `environments/dev` both describe Cognito, and sharing a key would
# have them fight over the same resources. Reconciling the two — importing this
# pool into the environment root — is deferred until `environments/dev` is
# actually applied.
# ---------------------------------------------------------------------------

terraform {
  required_version = ">= 1.7.0"

  backend "s3" {
    bucket       = "chat-app-terraform-state-721131331297"
    key          = "standalone/cognito/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true # S3-native locking; see environments/dev/backend.tf
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "us-east-1"
}
