# Tables are declared here (no crawlers). Partition projection on dt: Athena computes the partitions from the
# date range, so no MSCK REPAIR / ALTER TABLE ADD PARTITION is ever needed.

resource "aws_glue_catalog_database" "lake" {
  name        = var.project
  description = "VisionTrack detection data lake"
}

locals {
  parquet_storage = {
    input_format  = "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat"
    output_format = "org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat"
    serde         = "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe"
  }

  dt_projection = {
    "projection.enabled"          = "true"
    "projection.dt.type"          = "date"
    "projection.dt.format"        = "yyyy-MM-dd"
    "projection.dt.range"         = "2026-01-01,NOW"
    "projection.dt.interval"      = "1"
    "projection.dt.interval.unit" = "DAYS"
    "classification"              = "parquet"
    "parquet.compression"         = "SNAPPY"
  }

  detection_columns = [
    { name = "schema_version", type = "int" },
    { name = "run_id", type = "string" },
    { name = "video_id", type = "string" },
    { name = "frame_index", type = "bigint" },
    { name = "timestamp_ms", type = "bigint" },
    { name = "class_label", type = "string" },
    { name = "confidence", type = "double" },
    { name = "bbox_x", type = "double" },
    { name = "bbox_y", type = "double" },
    { name = "bbox_width", type = "double" },
    { name = "bbox_height", type = "double" },
    { name = "frame_width", type = "int" },
    { name = "frame_height", type = "int" },
    { name = "model_name", type = "string" },
    { name = "model_version", type = "string" },
    { name = "detected_at", type = "timestamp" },
  ]

  run_columns = [
    { name = "schema_version", type = "int" },
    { name = "run_id", type = "string" },
    { name = "video_id", type = "string" },
    { name = "source", type = "string" },
    { name = "duration_ms", type = "bigint" },
    { name = "fps", type = "double" },
    { name = "total_frames", type = "bigint" },
    { name = "total_detections", type = "bigint" },
    { name = "requested_classes", type = "array<string>" },
    { name = "processing_time_ms", type = "bigint" },
    { name = "started_at", type = "timestamp" },
    { name = "finished_at", type = "timestamp" },
    { name = "app_version", type = "string" },
    { name = "status", type = "string" },
  ]

  rejected_columns = [
    { name = "reject_reason", type = "string" },
    { name = "raw_record", type = "string" },
    { name = "run_id", type = "string" },
    { name = "source_key", type = "string" },
    { name = "rejected_at", type = "timestamp" },
  ]

  curated_tables = {
    detections = local.detection_columns
    runs       = local.run_columns
  }
}

resource "aws_glue_catalog_table" "curated" {
  for_each      = local.curated_tables
  name          = each.key
  database_name = aws_glue_catalog_database.lake.name
  table_type    = "EXTERNAL_TABLE"

  parameters = merge(local.dt_projection, {
    "storage.location.template" = "s3://${local.bucket_name}/curated/${each.key}/dt=$${dt}/"
  })

  partition_keys {
    name = "dt"
    type = "string"
  }

  storage_descriptor {
    location      = "s3://${local.bucket_name}/curated/${each.key}/"
    input_format  = local.parquet_storage.input_format
    output_format = local.parquet_storage.output_format

    ser_de_info {
      serialization_library = local.parquet_storage.serde
    }

    dynamic "columns" {
      for_each = each.value
      content {
        name = columns.value.name
        type = columns.value.type
      }
    }
  }
}

resource "aws_glue_catalog_table" "rejected" {
  name          = "rejected"
  database_name = aws_glue_catalog_database.lake.name
  table_type    = "EXTERNAL_TABLE"

  parameters = merge(local.dt_projection, {
    "projection.record_type.type"   = "enum"
    "projection.record_type.values" = "detections,runs"
    "storage.location.template"     = "s3://${local.bucket_name}/rejected/$${record_type}/dt=$${dt}/"
  })

  partition_keys {
    name = "record_type"
    type = "string"
  }
  partition_keys {
    name = "dt"
    type = "string"
  }

  storage_descriptor {
    location      = "s3://${local.bucket_name}/rejected/"
    input_format  = local.parquet_storage.input_format
    output_format = local.parquet_storage.output_format

    ser_de_info {
      serialization_library = local.parquet_storage.serde
    }

    dynamic "columns" {
      for_each = local.rejected_columns
      content {
        name = columns.value.name
        type = columns.value.type
      }
    }
  }
}
