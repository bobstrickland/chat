variable "name_prefix" {
  type = string
}

variable "account_id" {
  type = string
}

variable "region" {
  type = string
}

variable "github_org" {
  type = string
}

variable "github_repo" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "state_bucket_arn" {
  type = string
}

variable "state_lock_table_arn" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
