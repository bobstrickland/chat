terraform {
  required_version = ">= 1.7.0"

  backend "s3" {
    bucket         = "chat-app-terraform-state-721131331297"
    key            = "env/dev/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "chat-app-terraform-locks"
    encrypt        = true
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
