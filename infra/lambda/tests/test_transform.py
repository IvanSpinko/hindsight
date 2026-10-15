import io
import json
from collections import Counter
from datetime import datetime

import pyarrow.parquet as pq
import pytest

import transform
from conftest import SAMPLES

KEY = "raw/detections/dt=2026-10-15/run_id=0f8fad5b-d9cb-469f-a165-70867728950e/part-0000.jsonl.gz"


def sample_lines():
    return (SAMPLES / "detections_sample.jsonl").read_text(encoding="utf-8").splitlines()


def test_sample_file_is_split_into_clean_rejected_and_duplicate_rows():
    result = transform.clean_detection_lines(sample_lines(), KEY)

    assert len(result.rows) == 4
    assert result.duplicates == 1
    assert Counter(r["reject_reason"] for r in result.rejected) == Counter({
        "confidence_out_of_range": 1,
        "bbox_x_out_of_range": 1,
        "bbox_out_of_range": 1,
        "missing_class_label": 1,
        "unsupported_schema_version": 1,
        "invalid_detected_at": 1,
        "invalid_json": 1,
    })
    assert all(r["source_key"] == KEY for r in result.rejected)


def test_values_are_typed_and_labels_normalised():
    rows = transform.clean_detection_lines(sample_lines(), KEY).rows

    assert [r["class_label"] for r in rows] == ["person", "cup", "laptop", "person"]
    assert rows[3]["frame_index"] == 7  # "7" cast to int
    assert rows[2]["bbox_x"] == pytest.approx(3.24e-4)
    assert rows[0]["detected_at"] == datetime(2026, 10, 15, 13, 21, 0, 265000)
    assert rows[0]["schema_version"] == 1


@pytest.mark.parametrize("field, value, reason", [
    ("confidence", -0.01, "confidence_out_of_range"),
    ("bbox_height", 1.2, "bbox_height_out_of_range"),
    ("frame_index", -1, "frame_index_out_of_range"),
    ("frame_index", 1.5, "invalid_frame_index"),
    ("frame_width", 0, "frame_width_out_of_range"),
    ("run_id", "not-a-uuid", "invalid_run_id"),
    ("confidence", "high", "invalid_confidence"),
    ("confidence", True, "invalid_confidence"),
    ("detected_at", "2026-10-15T13:21:00", "invalid_detected_at"),  # no offset
    ("model_name", None, "missing_model_name"),
])
def test_rules_reject_with_a_reason(field, value, reason):
    record = json.loads(sample_lines()[0])
    record[field] = value
    with pytest.raises(transform.Rejected, match=f"^{reason}$"):
        transform.clean_detection(record)


def test_offsets_are_converted_to_utc():
    record = json.loads(sample_lines()[0])
    record["detected_at"] = "2026-10-15T15:21:00.265+02:00"
    assert transform.clean_detection(record)["detected_at"] == datetime(2026, 10, 15, 13, 21, 0, 265000)


def test_run_summary_is_cleaned():
    result = transform.clean_run_document((SAMPLES / "run_sample.json").read_text(encoding="utf-8"), "k")

    assert result.rejected == []
    run = result.rows[0]
    assert run["source"] == "upload"
    assert run["status"] == "completed"
    assert run["requested_classes"] == ["person", "cup", "laptop"]
    assert run["duration_ms"] == 5000
    assert run["finished_at"] > run["started_at"]


@pytest.mark.parametrize("field, value, reason", [
    ("source", "webcam", "invalid_source"),
    ("status", "done", "invalid_status"),
    ("finished_at", "2026-10-15T13:00:00Z", "finished_before_started"),
    ("schema_version", 9, "unsupported_schema_version"),
    ("requested_classes", "person", "invalid_requested_classes"),
])
def test_invalid_run_summaries_are_rejected(field, value, reason):
    record = json.loads((SAMPLES / "run_sample.json").read_text(encoding="utf-8"))
    record[field] = value
    result = transform.clean_run_document(json.dumps(record), "k")
    assert result.rows == []
    assert result.rejected[0]["reject_reason"] == reason


def test_nullable_run_fields_stay_null():
    record = json.loads((SAMPLES / "run_sample.json").read_text(encoding="utf-8"))
    record["duration_ms"] = None
    record["fps"] = None
    run = transform.clean_run(record)
    assert run["duration_ms"] is None and run["fps"] is None


def test_parquet_has_the_declared_types():
    rows = transform.clean_detection_lines(sample_lines(), KEY).rows
    table = pq.read_table(io.BytesIO(transform.to_parquet(rows, transform.DETECTIONS_SCHEMA)))

    assert table.schema.equals(transform.DETECTIONS_SCHEMA)
    assert table.num_rows == 4
    assert str(table.schema.field("detected_at").type) == "timestamp[ms]"


def test_empty_parquet_is_still_valid():
    table = pq.read_table(io.BytesIO(transform.to_parquet([], transform.RUNS_SCHEMA)))
    assert table.num_rows == 0
    assert table.schema.equals(transform.RUNS_SCHEMA)
