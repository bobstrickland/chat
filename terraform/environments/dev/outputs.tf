output "vpc_id" {
  value = module.vpc.vpc_id
}

output "dynamodb_table_names" {
  value = module.dynamodb.table_names
}

output "msk_bootstrap_brokers_sasl_iam" {
  value = module.msk.bootstrap_brokers_sasl_iam
}

output "cognito_user_pool_id" {
  value = module.cognito.user_pool_id
}

output "cognito_mobile_client_id" {
  value = module.cognito.mobile_client_id
}

output "cognito_web_client_id" {
  value = module.cognito.web_client_id
}

output "cognito_hosted_ui_domain" {
  value = module.cognito.hosted_ui_domain
}

output "cognito_jwks_url" {
  value = module.cognito.jwks_url
}

output "service_role_arns" {
  value = module.iam.service_role_arns
}

output "media_bucket_name" {
  value = aws_s3_bucket.media.bucket
}

output "web_cloudfront_domain" {
  value = aws_cloudfront_distribution.web.domain_name
}

output "gh_actions_role_arn" {
  value = module.ci_cd.gh_actions_role_arn
}

output "codebuild_project_name" {
  value = module.ci_cd.codebuild_project_name
}

output "acm_certificate_arn" {
  value = module.dns_acm.certificate_arn
}
