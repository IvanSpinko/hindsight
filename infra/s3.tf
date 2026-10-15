# Data lake bucket:
#   raw/       exports from the app (JSON Lines / JSON), written only by the uploader role
#   curated/   cleaned Parquet written by the Lambda, read by Athena
#   rejected/  invalid records with a reject_reason column
#   athena-results/  query results of the workgroup

resource "aws_s3_bucket" "lake" {
  bucket        = local.bucket_name
  force_destroy = var.force_destroy_bucket
}

resource "aws_s3_bucket_public_access_block" "lake" {
  bucket                  = aws_s3_bucket.lake.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "lake" {
  bucket = aws_s3_bucket.lake.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# SSE-S3: free; SSE-KMS would add about 1 USD per month for the key
resource "aws_s3_bucket_server_side_encryption_configuration" "lake" {
  bucket = aws_s3_bucket.lake.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "lake" {
  bucket = aws_s3_bucket.lake.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "lake" {
  bucket     = aws_s3_bucket.lake.id
  depends_on = [aws_s3_bucket_versioning.lake]

  rule {
    id     = "all-noncurrent-versions-and-uploads"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
    expiration {
      expired_object_delete_marker = true
    }
  }

  rule {
    id     = "athena-results"
    status = "Enabled"
    filter {
      prefix = "athena-results/"
    }
    expiration {
      days = 7
    }
  }

  rule {
    id     = "rejected"
    status = "Enabled"
    filter {
      prefix = "rejected/"
    }
    expiration {
      days = 90
    }
  }

  rule {
    id     = "raw-to-infrequent-access"
    status = "Enabled"
    filter {
      prefix = "raw/"
    }
    transition {
      days          = 90
      storage_class = "STANDARD_IA"
    }
  }
}

data "aws_iam_policy_document" "lake_bucket" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    resources = [local.bucket_arn, "${local.bucket_arn}/*"]
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "lake" {
  bucket     = aws_s3_bucket.lake.id
  policy     = data.aws_iam_policy_document.lake_bucket.json
  depends_on = [aws_s3_bucket_public_access_block.lake]
}

resource "aws_s3_bucket_notification" "raw_exports" {
  bucket = aws_s3_bucket.lake.id

  lambda_function {
    id                  = "raw-detections"
    lambda_function_arn = aws_lambda_function.transform.arn
    events              = ["s3:ObjectCreated:*"]
    filter_prefix       = "raw/detections/"
    filter_suffix       = ".jsonl.gz"
  }

  lambda_function {
    id                  = "raw-runs"
    lambda_function_arn = aws_lambda_function.transform.arn
    events              = ["s3:ObjectCreated:*"]
    filter_prefix       = "raw/runs/"
    filter_suffix       = ".json"
  }

  depends_on = [aws_lambda_permission.s3_invoke]
}
