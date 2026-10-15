"""Cleaning rules for VisionTrack export files: raw JSON -> typed rows (curated) or rows with a reason (rejected).

Pure functions without AWS calls, so they are tested locally with pytest.
"""

from __future__ import annotations

import gzip
import io
import json
import math
import re
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone

import pyarrow as pa
import pyarrow.parquet as pq

SUPPORTED_SCHEMA_VERSIONS = {1}
SOURCES = {"upload", "live_camera"}
STATUSES = {"completed", "failed", "cancelled"}
BBOX_TOLERANCE = 1e-4  # x + width may exceed 1 by float rounding only

DETECTIONS_SCHEMA = pa.schema([
    ("schema_version", pa.int32()),
    ("run_id", pa.string()),
    ("video_id", pa.string()),
    ("frame_index", pa.int64()),
    ("timestamp_ms", pa.int64()),
    ("class_label", pa.string()),
    ("confidence", pa.float64()),
    ("bbox_x", pa.float64()),
    ("bbox_y", pa.float64()),
    ("bbox_width", pa.float64()),
    ("bbox_height", pa.float64()),
    ("frame_width", pa.int32()),
    ("frame_height", pa.int32()),
    ("model_name", pa.string()),
    ("model_version", pa.string()),
    ("detected_at", pa.timestamp("ms")),
])

RUNS_SCHEMA = pa.schema([
    ("schema_version", pa.int32()),
    ("run_id", pa.string()),
    ("video_id", pa.string()),
    ("source", pa.string()),
    ("duration_ms", pa.int64()),
    ("fps", pa.float64()),
    ("total_frames", pa.int64()),
    ("total_detections", pa.int64()),
    ("requested_classes", pa.list_(pa.string())),
    ("processing_time_ms", pa.int64()),
    ("started_at", pa.timestamp("ms")),
    ("finished_at", pa.timestamp("ms")),
    ("app_version", pa.string()),
    ("status", pa.string()),
])

REJECTED_SCHEMA = pa.schema([
    ("reject_reason", pa.string()),
    ("raw_record", pa.string()),
    ("run_id", pa.string()),
    ("source_key", pa.string()),
    ("rejected_at", pa.timestamp("ms")),
])


class Rejected(ValueError):
    """A record that breaks a cleaning rule; the message is the reject reason."""


@dataclass
class Result:
    rows: list[dict] = field(default_factory=list)
    rejected: list[dict] = field(default_factory=list)
    duplicates: int = 0


# ---------- field rules ----------

def _require(record: dict, name: str):
    value = record.get(name)
    if value is None or (isinstance(value, str) and not value.strip()):
        raise Rejected(f"missing_{name}")
    return value


def _schema_version(record: dict) -> int:
    version = _integer(record, "schema_version")
    if version not in SUPPORTED_SCHEMA_VERSIONS:
        raise Rejected("unsupported_schema_version")
    return version


def _integer(record: dict, name: str, minimum: int | None = None) -> int:
    value = _require(record, name)
    if isinstance(value, bool):
        raise Rejected(f"invalid_{name}")
    try:
        number = float(value)
    except (TypeError, ValueError):
        raise Rejected(f"invalid_{name}") from None
    if not math.isfinite(number) or number != int(number):
        raise Rejected(f"invalid_{name}")
    if minimum is not None and number < minimum:
        raise Rejected(f"{name}_out_of_range")
    return int(number)


def _optional_integer(record: dict, name: str, minimum: int = 0) -> int | None:
    return None if record.get(name) is None else _integer(record, name, minimum)


def _number(record: dict, name: str) -> float:
    value = _require(record, name)
    if isinstance(value, bool):
        raise Rejected(f"invalid_{name}")
    try:
        number = float(value)
    except (TypeError, ValueError):
        raise Rejected(f"invalid_{name}") from None
    if not math.isfinite(number):
        raise Rejected(f"invalid_{name}")
    return number


def _unit_interval(record: dict, name: str) -> float:
    number = _number(record, name)
    if not 0.0 <= number <= 1.0:
        raise Rejected(f"{name}_out_of_range")
    return number


def _text(record: dict, name: str) -> str:
    value = _require(record, name)
    if not isinstance(value, str):
        raise Rejected(f"invalid_{name}")
    return value.strip()


def _uuid(record: dict, name: str) -> str:
    try:
        return str(uuid.UUID(_text(record, name)))
    except ValueError:
        raise Rejected(f"invalid_{name}") from None


def _timestamp(record: dict, name: str) -> datetime:
    """ISO-8601 with an offset (the app writes UTC with 'Z'); stored as naive UTC with millisecond precision."""
    text = _text(record, name)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        raise Rejected(f"invalid_{name}") from None
    if parsed.tzinfo is None:
        raise Rejected(f"invalid_{name}")
    utc = parsed.astimezone(timezone.utc).replace(tzinfo=None)
    return utc.replace(microsecond=utc.microsecond // 1000 * 1000)


_SPACES = re.compile(r"\s+")


def _label(record: dict) -> str:
    label = _SPACES.sub(" ", _text(record, "class_label")).lower()
    if not label:
        raise Rejected("missing_class_label")
    return label


# ---------- records ----------

def clean_detection(record: dict) -> dict:
    if not isinstance(record, dict):
        raise Rejected("not_an_object")
    row = {
        "schema_version": _schema_version(record),
        "run_id": _uuid(record, "run_id"),
        "video_id": _text(record, "video_id"),
        "frame_index": _integer(record, "frame_index", minimum=0),
        "timestamp_ms": _integer(record, "timestamp_ms", minimum=0),
        "class_label": _label(record),
        "confidence": _unit_interval(record, "confidence"),
        "bbox_x": _unit_interval(record, "bbox_x"),
        "bbox_y": _unit_interval(record, "bbox_y"),
        "bbox_width": _unit_interval(record, "bbox_width"),
        "bbox_height": _unit_interval(record, "bbox_height"),
        "frame_width": _integer(record, "frame_width", minimum=1),
        "frame_height": _integer(record, "frame_height", minimum=1),
        "model_name": _text(record, "model_name"),
        "model_version": _text(record, "model_version"),
        "detected_at": _timestamp(record, "detected_at"),
    }
    if row["bbox_x"] + row["bbox_width"] > 1.0 + BBOX_TOLERANCE \
            or row["bbox_y"] + row["bbox_height"] > 1.0 + BBOX_TOLERANCE:
        raise Rejected("bbox_out_of_range")
    return row


def clean_run(record: dict) -> dict:
    if not isinstance(record, dict):
        raise Rejected("not_an_object")
    source = _text(record, "source").lower()
    if source not in SOURCES:
        raise Rejected("invalid_source")
    status = _text(record, "status").lower()
    if status not in STATUSES:
        raise Rejected("invalid_status")
    classes = record.get("requested_classes") or []
    if not isinstance(classes, list) or not all(isinstance(c, str) for c in classes):
        raise Rejected("invalid_requested_classes")
    fps = None if record.get("fps") is None else _number(record, "fps")
    if fps is not None and fps < 0:
        raise Rejected("fps_out_of_range")
    row = {
        "schema_version": _schema_version(record),
        "run_id": _uuid(record, "run_id"),
        "video_id": _text(record, "video_id"),
        "source": source,
        "duration_ms": _optional_integer(record, "duration_ms"),
        "fps": fps,
        "total_frames": _integer(record, "total_frames", minimum=0),
        "total_detections": _optional_integer(record, "total_detections"),
        "requested_classes": [_SPACES.sub(" ", c.strip()).lower() for c in classes],
        "processing_time_ms": _integer(record, "processing_time_ms", minimum=0),
        "started_at": _timestamp(record, "started_at"),
        "finished_at": _timestamp(record, "finished_at"),
        "app_version": _text(record, "app_version"),
        "status": status,
    }
    if row["finished_at"] < row["started_at"]:
        raise Rejected("finished_before_started")
    return row


def _dedup_key(row: dict) -> tuple:
    return (row["run_id"], row["frame_index"], row["class_label"],
            round(row["bbox_x"], 6), round(row["bbox_y"], 6), round(row["bbox_width"], 6), round(row["bbox_height"], 6))


def _reject(reason: str, raw, source_key: str, now: datetime) -> dict:
    raw_text = raw if isinstance(raw, str) else json.dumps(raw, ensure_ascii=False, separators=(",", ":"))
    run_id = raw.get("run_id") if isinstance(raw, dict) and isinstance(raw.get("run_id"), str) else None
    return {"reject_reason": reason, "raw_record": raw_text[:10_000], "run_id": run_id,
            "source_key": source_key, "rejected_at": now}


def clean_detection_lines(lines, source_key: str, now: datetime | None = None) -> Result:
    """One JSON object per line; empty lines are skipped, duplicates dropped (first one kept)."""
    now = now or _utc_now()
    result = Result()
    seen = set()
    for line in lines:
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            result.rejected.append(_reject("invalid_json", line, source_key, now))
            continue
        try:
            row = clean_detection(record)
        except Rejected as e:
            result.rejected.append(_reject(str(e), record, source_key, now))
            continue
        key = _dedup_key(row)
        if key in seen:
            result.duplicates += 1
            continue
        seen.add(key)
        result.rows.append(row)
    return result


def clean_run_document(text: str, source_key: str, now: datetime | None = None) -> Result:
    now = now or _utc_now()
    result = Result()
    try:
        record = json.loads(text)
    except json.JSONDecodeError:
        result.rejected.append(_reject("invalid_json", text, source_key, now))
        return result
    try:
        result.rows.append(clean_run(record))
    except Rejected as e:
        result.rejected.append(_reject(str(e), record, source_key, now))
    return result


# ---------- files ----------

def read_lines(body: bytes, key: str) -> list[str]:
    data = gzip.decompress(body) if key.endswith(".gz") else body
    return data.decode("utf-8").splitlines()


def to_parquet(rows: list[dict], schema: pa.Schema) -> bytes:
    table = pa.Table.from_pylist(rows, schema=schema)
    buffer = io.BytesIO()
    pq.write_table(table, buffer, compression="snappy")
    return buffer.getvalue()


def _utc_now() -> datetime:
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    return now.replace(microsecond=now.microsecond // 1000 * 1000)
