output "user_pool_id" {
  value = aws_cognito_user_pool.this.id
}

output "user_pool_arn" {
  value = aws_cognito_user_pool.this.arn
}

output "mobile_client_id" {
  value = aws_cognito_user_pool_client.mobile.id
}

output "web_client_id" {
  value = aws_cognito_user_pool_client.web.id
}

output "hosted_ui_domain" {
  value = aws_cognito_user_pool_domain.this.domain
}

output "hosted_ui_cloudfront_domain" {
  value = aws_cognito_user_pool_domain.this.cloudfront_distribution
}

output "jwks_url" {
  value = "https://cognito-idp.${data.aws_region.current.name}.amazonaws.com/${aws_cognito_user_pool.this.id}/.well-known/jwks.json"
}

data "aws_region" "current" {}
