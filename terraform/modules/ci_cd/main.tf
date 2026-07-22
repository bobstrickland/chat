# ---- GitHub OIDC provider (one per account, safe to reuse if it already exists) ----
data "tls_certificate" "github" {
  url = "https://token.actions.githubusercontent.com/.well-known/openid-configuration"
}

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.github.certificates[0].sha1_fingerprint]
  tags            = var.tags
}

# ---- Role assumed by GitHub Actions via OIDC, scoped to this repo only ----
data "aws_iam_policy_document" "gh_actions_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_org}/${var.github_repo}:*"]
    }
  }
}

resource "aws_iam_role" "gh_actions" {
  name               = "${var.name_prefix}-gh-actions-role"
  assume_role_policy = data.aws_iam_policy_document.gh_actions_assume.json
  tags               = var.tags
}

# GH Actions role can only trigger CodeBuild — it does not apply Terraform directly
data "aws_iam_policy_document" "gh_actions_permissions" {
  statement {
    actions   = ["codebuild:StartBuild", "codebuild:BatchGetBuilds"]
    resources = [aws_codebuild_project.terraform.arn]
  }
}

resource "aws_iam_role_policy" "gh_actions" {
  name   = "${var.name_prefix}-gh-actions-policy"
  role   = aws_iam_role.gh_actions.id
  policy = data.aws_iam_policy_document.gh_actions_permissions.json
}

# ---- CodeBuild service role — this is what actually runs `terraform apply`, VPC-attached ----
data "aws_iam_policy_document" "codebuild_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["codebuild.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "codebuild" {
  name               = "${var.name_prefix}-codebuild-terraform-role"
  assume_role_policy = data.aws_iam_policy_document.codebuild_assume.json
  tags               = var.tags
}

# Broad-ish provisioning permissions for this project's resource types + state access.
# Scope down further once the resource set stabilizes.
data "aws_iam_policy_document" "codebuild_permissions" {
  statement {
    sid = "TerraformStateAccess"
    actions = [
      "s3:GetObject", "s3:PutObject", "s3:ListBucket",
      "dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:DeleteItem",
    ]
    resources = [
      var.state_bucket_arn, "${var.state_bucket_arn}/*",
      var.state_lock_table_arn,
    ]
  }
  statement {
    sid = "ProjectProvisioning"
    actions = [
      "ec2:*", "vpc:*",
      "iam:GetRole", "iam:CreateRole", "iam:DeleteRole", "iam:PutRolePolicy",
      "iam:DeleteRolePolicy", "iam:AttachRolePolicy", "iam:DetachRolePolicy",
      "iam:TagRole", "iam:PassRole", "iam:GetPolicy", "iam:ListRolePolicies",
      "iam:GetRolePolicy", "iam:ListAttachedRolePolicies", "iam:CreateOpenIDConnectProvider",
      "iam:GetOpenIDConnectProvider", "iam:TagOpenIDConnectProvider",
      "dynamodb:CreateTable", "dynamodb:DeleteTable", "dynamodb:DescribeTable",
      "dynamodb:UpdateTable", "dynamodb:TagResource", "dynamodb:ListTagsOfResource",
      "kafka:*",
      "cognito-idp:*",
      "acm:*",
      "route53:*",
      "cloudfront:*",
      "apigateway:*",
      "kms:*",
      "logs:*",
      "codebuild:*",
      "s3:CreateBucket", "s3:PutBucketPolicy", "s3:PutBucketTagging",
      "s3:PutEncryptionConfiguration", "s3:PutBucketVersioning",
    ]
    resources = ["*"]
  }
  statement {
    sid       = "MSKKafkaClusterConnect"
    actions   = ["kafka-cluster:*"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "codebuild" {
  name   = "${var.name_prefix}-codebuild-terraform-policy"
  role   = aws_iam_role.codebuild.id
  policy = data.aws_iam_policy_document.codebuild_permissions.json
}

resource "aws_security_group" "codebuild" {
  name_prefix = "${var.name_prefix}-codebuild-"
  vpc_id      = var.vpc_id
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = merge(var.tags, { Name = "${var.name_prefix}-codebuild-sg" })
}

resource "aws_codebuild_project" "terraform" {
  name         = "${var.name_prefix}-terraform-apply"
  service_role = aws_iam_role.codebuild.arn

  artifacts {
    type = "NO_ARTIFACTS"
  }

  environment {
    compute_type    = "BUILD_GENERAL1_SMALL"
    image           = "aws/codebuild/amazonlinux2-x86_64-standard:5.0"
    type            = "LINUX_CONTAINER"
    privileged_mode = false
  }

  source {
    type      = "GITHUB"
    location  = "https://github.com/${var.github_org}/${var.github_repo}.git"
    buildspec = "buildspec.yml"
  }

  vpc_config {
    vpc_id             = var.vpc_id
    subnets            = var.private_subnet_ids
    security_group_ids = [aws_security_group.codebuild.id]
  }

  tags = var.tags
}
