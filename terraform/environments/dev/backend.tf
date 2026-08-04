terraform {
  required_version = ">= 1.7.0"

  backend "s3" {
    bucket  = "chat-app-terraform-state-721131331297"
    key     = "env/dev/terraform.tfstate"
    region  = "us-east-1"
    encrypt = true
    # S3-native state locking (Terraform 1.11+). Replaced
    # `dynamodb_table = "chat-app-terraform-locks"`, which is deprecated and
    # printed a warning on every command. Terraform now writes a .tflock object
    # beside the state, so the separate lock table is gone (see
    # terraform/bootstrap). Needs no permission the backend didn't already have.
    use_lockfile = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    kafka = {
      source  = "Mongey/kafka"
      version = "~> 0.7"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}
