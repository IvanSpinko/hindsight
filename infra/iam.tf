# ---------- Lambda: read raw/, write curated/ and rejected/, write its own logs ----------

data "aws_iam_policy_document" "lambda_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "transform" {
  name               = "${var.project}-transform"
  assume_role_policy = data.aws_iam_policy_document.lambda_trust.json
}

data "aws_iam_policy_document" "transform" {
  statement {
    sid       = "ReadRaw"
    actions   = ["s3:GetObject"]
    resources = ["${local.bucket_arn}/raw/*"]
  }
  statement {
    sid       = "WriteCuratedAndRejected"
    actions   = ["s3:PutObject"]
    resources = ["${local.bucket_arn}/curated/*", "${local.bucket_arn}/rejected/*"]
  }
  statement {
    sid       = "Logs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.transform.arn}:*"]
  }
}

resource "aws_iam_role_policy" "transform" {
  name   = "lake-access"
  role   = aws_iam_role.transform.id
  policy = data.aws_iam_policy_document.transform.json
}

# ---------- Local app: may only put objects under raw/ ----------
# Used through a profile in ~/.aws/config:
#   [profile visiontrack-uploader]
#   role_arn = <output uploader_role_arn>
#   source_profile = main
#   region = eu-west-1

data "aws_iam_policy_document" "uploader_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type = "AWS"
      identifiers = length(var.uploader_principal_arns) > 0 ? var.uploader_principal_arns : [
        data.aws_caller_identity.current.arn
      ]
    }
  }
}

resource "aws_iam_role" "uploader" {
  name                 = "${var.project}-uploader"
  description          = "VisionTrack desktop app: upload detection exports to raw/ only"
  assume_role_policy   = data.aws_iam_policy_document.uploader_trust.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "uploader" {
  statement {
    sid       = "PutRawExports"
    actions   = ["s3:PutObject"]
    resources = ["${local.bucket_arn}/raw/*"]
  }
}

resource "aws_iam_role_policy" "uploader" {
  name   = "put-raw-only"
  role   = aws_iam_role.uploader.id
  policy = data.aws_iam_policy_document.uploader.json
}
