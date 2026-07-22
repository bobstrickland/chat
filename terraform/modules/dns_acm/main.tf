data "aws_route53_zone" "root" {
  name = var.root_domain
}

# ACM for CloudFront MUST be in us-east-1 regardless of deployment region —
# this module is instantiated a second time with an explicit us-east-1 provider
# alias from the environment root for the CloudFront-facing cert.
resource "aws_acm_certificate" "wildcard" {
  domain_name       = var.wildcard_domain
  validation_method = "DNS"
  tags              = var.tags

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "validation" {
  for_each = {
    for dvo in aws_acm_certificate.wildcard.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  zone_id = data.aws_route53_zone.root.zone_id
  name    = each.value.name
  type    = each.value.type
  records = [each.value.record]
  ttl     = 60
}

resource "aws_acm_certificate_validation" "wildcard" {
  certificate_arn         = aws_acm_certificate.wildcard.arn
  validation_record_fqdns = [for r in aws_route53_record.validation : r.fqdn]
}
