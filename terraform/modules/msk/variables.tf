variable "name_prefix" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "kafka_version" {
  type    = string
  default = "3.6.0"
}

variable "broker_instance_type" {
  type    = string
  default = "kafka.t3.small"
}

variable "number_of_broker_nodes" {
  type    = number
  default = 2
}

variable "kms_key_arn" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
