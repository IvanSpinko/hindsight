output "bucket_name" {
  description = "Set as VISIONTRACK_CLOUD_BUCKET for the app's aws profile."
  value       = aws_s3_bucket.lake.bucket
}

output "uploader_role_arn" {
  description = "role_arn of the visiontrack-uploader profile in ~/.aws/config."
  value       = aws_iam_role.uploader.arn
}

output "uploader_profile_snippet" {
  description = "Paste into ~/.aws/config."
  value       = <<-EOT
    [profile visiontrack-uploader]
    role_arn = ${aws_iam_role.uploader.arn}
    source_profile = ${var.aws_profile}
    region = ${var.region}
  EOT
}

output "athena_workgroup" {
  value = aws_athena_workgroup.lake.name
}

output "glue_database" {
  value = aws_glue_catalog_database.lake.name
}

output "transform_function" {
  value = aws_lambda_function.transform.function_name
}
