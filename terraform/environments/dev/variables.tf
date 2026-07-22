variable "region" {
  type    = string
  default = "us-east-1"
}

variable "account_id" {
  type    = string
  default = "721131331297"
}

variable "name_prefix" {
  type    = string
  default = "chat-dev"
}

variable "root_domain" {
  type    = string
  default = "rstrickland.dev"
}

variable "wildcard_domain" {
  type    = string
  default = "*.chat.rstrickland.dev"
}

variable "github_org" {
  type = string
}

variable "github_repo" {
  type = string
}

# OAuth2 federation — leave blank to skip Google/Apple until credentials exist
variable "google_client_id" {
  type    = string
  default = ""
}

variable "google_client_secret" {
  type      = string
  default   = ""
  sensitive = true
}

variable "apple_client_id" {
  type    = string
  default = ""
}

variable "apple_team_id" {
  type    = string
  default = ""
}

variable "apple_key_id" {
  type    = string
  default = ""
}

variable "apple_private_key" {
  type      = string
  default   = ""
  sensitive = true
}
