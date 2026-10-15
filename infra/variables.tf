variable "region" {
  description = "AWS region for every resource."
  type        = string
  default     = "eu-west-1"
}

variable "aws_profile" {
  description = "Named profile from ~/.aws used by Terraform (an admin-capable identity)."
  type        = string
  default     = "main"
}

variable "project" {
  description = "Name prefix for all resources."
  type        = string
  default     = "visiontrack"
}

variable "uploader_principal_arns" {
  description = "IAM principals allowed to assume the upload-only role. Empty = the identity running Terraform."
  type        = list(string)
  default     = []
}

variable "pandas_layer_arn" {
  description = "Managed AWS SDK for pandas layer (pyarrow) matching the Lambda runtime."
  type        = string
  default     = "arn:aws:lambda:eu-west-1:336392948345:layer:AWSSDKPandas-Python313:16"
}

variable "athena_bytes_scanned_cutoff" {
  description = "Per-query scan limit of the Athena workgroup, in bytes (minimum 10 MB)."
  type        = number
  default     = 1073741824 # 1 GB
}

variable "budget_email" {
  description = "Address for AWS Budgets alerts (kept in terraform.tfvars, which is gitignored)."
  type        = string
}

variable "budget_limit_usd" {
  description = "Monthly cost budget for the whole account."
  type        = number
  default     = 10
}

variable "budget_alerts_usd" {
  description = "Actual-spend thresholds that send an e-mail."
  type        = list(number)
  default     = [5, 10]
}

variable "force_destroy_bucket" {
  description = "Allow terraform destroy to delete the bucket with all data and versions in it."
  type        = bool
  default     = false
}
