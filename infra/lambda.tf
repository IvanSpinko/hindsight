data "archive_file" "transform" {
  type        = "zip"
  source_dir  = "${path.module}/lambda/src"
  output_path = "${path.module}/build/transform.zip"
  excludes    = ["__pycache__"]
}

resource "aws_cloudwatch_log_group" "transform" {
  name              = "/aws/lambda/${var.project}-transform"
  retention_in_days = 14
}

resource "aws_lambda_function" "transform" {
  function_name    = "${var.project}-transform"
  description      = "Cleans raw VisionTrack exports into Parquet (curated/ and rejected/)"
  role             = aws_iam_role.transform.arn
  runtime          = "python3.13"
  architectures    = ["x86_64"]
  handler          = "handler.handler"
  filename         = data.archive_file.transform.output_path
  source_code_hash = data.archive_file.transform.output_base64sha256
  layers           = [var.pandas_layer_arn]
  memory_size      = 512
  timeout          = 60

  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.transform.name
  }

  depends_on = [aws_iam_role_policy.transform, aws_cloudwatch_log_group.transform]
}

resource "aws_lambda_function_event_invoke_config" "transform" {
  function_name                = aws_lambda_function.transform.function_name
  maximum_retry_attempts       = 2
  maximum_event_age_in_seconds = 3600
}

resource "aws_lambda_permission" "s3_invoke" {
  statement_id   = "AllowS3RawExports"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.transform.function_name
  principal      = "s3.amazonaws.com"
  source_arn     = aws_s3_bucket.lake.arn
  source_account = local.account_id
}
