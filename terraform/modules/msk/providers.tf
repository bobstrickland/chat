# Provider requirements for this module.
#
# Without this, `terraform init` on any config that uses this module FAILS
# outright: the module declares `kafka_topic` resources, and with no source
# address for the local name "kafka" Terraform assumes `hashicorp/kafka`, which
# does not exist ("registry.terraform.io does not have a provider named
# hashicorp/kafka"). A `required_providers` block in the ROOT is not inherited —
# every module that uses a non-HashiCorp provider must name it too.
#
# The version constraint is deliberately left to the root config
# (`environments/dev/backend.tf` pins `~> 0.7`) so there's one place to bump it.
terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
    kafka = {
      source = "Mongey/kafka"
    }
  }
}
