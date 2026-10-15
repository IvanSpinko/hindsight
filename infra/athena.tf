resource "aws_athena_workgroup" "lake" {
  name          = var.project
  description   = "VisionTrack detection analytics"
  force_destroy = true

  configuration {
    enforce_workgroup_configuration    = true
    publish_cloudwatch_metrics_enabled = true
    bytes_scanned_cutoff_per_query     = var.athena_bytes_scanned_cutoff

    engine_version {
      selected_engine_version = "AUTO"
    }

    result_configuration {
      output_location = "s3://${local.bucket_name}/athena-results/"
      encryption_configuration {
        encryption_option = "SSE_S3"
      }
    }
  }
}

# The example queries from analytics/queries, saved in the workgroup ("Saved queries" in the console)
resource "aws_athena_named_query" "examples" {
  for_each  = fileset("${path.module}/../analytics/queries", "*.sql")
  name      = trimsuffix(each.value, ".sql")
  workgroup = aws_athena_workgroup.lake.name
  database  = aws_glue_catalog_database.lake.name
  query     = file("${path.module}/../analytics/queries/${each.value}")
}
