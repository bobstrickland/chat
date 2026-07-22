variable "name_prefix" {
  type = string
}

variable "account_id" {
  type = string
}

variable "region" {
  type = string
}

variable "dynamodb_table_arns" {
  description = "Map of service name -> list of table ARNs that service may access"
  type        = map(list(string))
}

variable "msk_cluster_arn" {
  type = string
}

variable "media_bucket_arn" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "cognito_user_pool_arn" {
  type = string
}

variable "opensearch_domain_arn" {
  type    = string
  default = ""
}

variable "tags" {
  type    = map(string)
  default = {}
}
