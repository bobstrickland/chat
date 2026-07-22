output "gh_actions_role_arn" {
  value = aws_iam_role.gh_actions.arn
}

output "codebuild_project_name" {
  value = aws_codebuild_project.terraform.name
}
