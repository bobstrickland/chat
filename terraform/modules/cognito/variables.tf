variable "name_prefix" {
  type = string
}

variable "auth_domain_prefix" {
  description = "Prefix for Cognito Hosted UI custom domain (e.g. dev-auth-chat-rstrickland-dev)"
  type        = string
}

variable "custom_domain" {
  description = "Full custom domain for Hosted UI, e.g. dev-auth.chat.rstrickland.dev"
  type        = string
  default     = ""
}

variable "acm_certificate_arn_us_east_1" {
  description = "ACM cert ARN in us-east-1 — required for Cognito custom domain regardless of deployment region"
  type        = string
  default     = ""
}

variable "mobile_callback_urls" {
  type    = list(string)
  default = ["myapp://callback"]
}

variable "web_callback_urls" {
  type = list(string)
}

variable "web_logout_urls" {
  type = list(string)
}

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

variable "tags" {
  type    = map(string)
  default = {}
}
