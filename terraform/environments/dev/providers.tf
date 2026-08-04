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
  tls_enabled       = true

  # Flat attributes, not a `sasl {}` block — that block form isn't in the
  # provider's schema and made `terraform validate` fail ("Blocks of type sasl
  # are not expected here"). MSK IAM auth maps to the "aws-iam" mechanism plus
  # the region to sign for; credentials come from the ambient AWS chain (the
  # CodeBuild role), same as the aws provider.
  sasl_mechanism  = "aws-iam"
  sasl_aws_region = var.region
}
