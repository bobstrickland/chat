# This module is instantiated with an explicit `providers = { aws = aws.us_east_1 }`
# (ACM certs for CloudFront and the Cognito Hosted UI must be issued in
# us-east-1 regardless of where everything else deploys). Passing a provider to
# a module that doesn't declare it is only a warning, but declaring it makes the
# contract explicit and keeps `terraform validate` clean.
terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}
