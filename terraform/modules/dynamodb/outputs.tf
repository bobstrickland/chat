output "table_arns" {
  value = {
    users                 = aws_dynamodb_table.users.arn
    profiles              = aws_dynamodb_table.profiles.arn
    conversations         = aws_dynamodb_table.conversations.arn
    presence_connections  = aws_dynamodb_table.presence_connections.arn
    device_tokens         = aws_dynamodb_table.device_tokens.arn
    media_metadata        = aws_dynamodb_table.media_metadata.arn
  }
}

output "table_names" {
  value = {
    users                 = aws_dynamodb_table.users.name
    profiles              = aws_dynamodb_table.profiles.name
    conversations         = aws_dynamodb_table.conversations.name
    presence_connections  = aws_dynamodb_table.presence_connections.name
    device_tokens         = aws_dynamodb_table.device_tokens.name
    media_metadata        = aws_dynamodb_table.media_metadata.name
  }
}

output "conversations_stream_arn" {
  value = aws_dynamodb_table.conversations.stream_arn
}
