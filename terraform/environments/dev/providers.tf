provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "chat-app"
      Environment = "dev"
      ManagedBy   = "terraform"
    }
  }
}

# CloudFront + Cognito custom domain ACM certs must be issued in us-east-1
# regardless of the region resources actually deploy to.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = "chat-app"
      Environment = "dev"
      ManagedBy   = "terraform"
    }
  }
}

# Mongey/kafka provider — talks to MSK bootstrap brokers directly.
# Requires apply to run from inside the VPC (CodeBuild project, see modules/ci_cd)
# or via a bastion/VPN if applying locally.
provider "kafka" {
  bootstrap_servers = split(",", module.msk.bootstrap_brokers_sasl_iam)
  tls_enabled        = true

  sasl {
    mechanism  = "aws-iam"
    aws_region = var.region
  }
}
