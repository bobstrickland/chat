variable "root_domain" {
  description = "Root Route53 hosted zone, e.g. rstrickland.dev"
  type        = string
}

variable "wildcard_domain" {
  description = "Wildcard cert domain, e.g. *.chat.rstrickland.dev"
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
