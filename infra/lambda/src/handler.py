"""S3-triggered Lambda: one raw export file -> Parquet in curated/ (and rejected/ for invalid records).

raw/detections/dt=D/run_id=R/part-NNNN.jsonl.gz -> curated/detections/dt=D/run_id=R-part-NNNN.parquet
raw/runs/dt=D/run_id=R/run.json                 -> curated/runs/dt=D/run_id=R.parquet
Output keys are derived from the input key only, so a repeated event overwrites the same object (idempotent).
"""

from __future__ import annotations

import json
import logging
import re
from urllib.parse import unquote_plus

import transform

log = logging.getLogger()
log.setLevel(logging.INFO)

RAW_KEY = re.compile(
    r"^(?P<prefix>(?:.*/)?)raw/(?P<kind>detections|runs)/dt=(?P<dt>\d{4}-\d{2}-\d{2})/run_id=(?P<run>[0-9a-fA-F-]{36})/"
    r"(?P<file>part-\d{4}\.jsonl\.gz|run\.json)$"
)

_s3 = None


def _client():
    global _s3
    if _s3 is None:
        import boto3  # provided by the Lambda runtime
        _s3 = boto3.client("s3")
    return _s3


def handler(event, context):
    results = []
    for record in event.get("Records", []):
        bucket = record["s3"]["bucket"]["name"]
        key = unquote_plus(record["s3"]["object"]["key"])
        results.append(process_object(_client(), bucket, key))
    return {"processed": results}


def output_keys(key: str) -> dict | None:
    match = RAW_KEY.match(key)
    if not match:
        return None
    m = match.groupdict()
    if (m["kind"] == "runs") != (m["file"] == "run.json"):
        return None
    name = f"run_id={m['run']}" if m["kind"] == "runs" else f"run_id={m['run']}-{m['file'].removesuffix('.jsonl.gz')}"
    partition = f"{m['kind']}/dt={m['dt']}/{name}.parquet"
    return {
        "kind": m["kind"],
        "curated": f"{m['prefix']}curated/{partition}",
        "rejected": f"{m['prefix']}rejected/{partition}",
    }


def process_object(s3, bucket: str, key: str) -> dict:
    keys = output_keys(key)
    if keys is None:
        log.warning(json.dumps({"event": "skipped", "key": key, "reason": "not a raw export key"}))
        return {"key": key, "skipped": True}

    body = s3.get_object(Bucket=bucket, Key=key)["Body"].read()
    if keys["kind"] == "detections":
        result = transform.clean_detection_lines(transform.read_lines(body, key), key)
        schema = transform.DETECTIONS_SCHEMA
    else:
        result = transform.clean_run_document(body.decode("utf-8"), key)
        schema = transform.RUNS_SCHEMA

    # An all-invalid file still gets an (empty) curated object, so re-processing replaces earlier output
    s3.put_object(Bucket=bucket, Key=keys["curated"], Body=transform.to_parquet(result.rows, schema),
                  ContentType="application/vnd.apache.parquet")
    if result.rejected:
        s3.put_object(Bucket=bucket, Key=keys["rejected"],
                      Body=transform.to_parquet(result.rejected, transform.REJECTED_SCHEMA),
                      ContentType="application/vnd.apache.parquet")

    summary = {"event": "processed", "key": key, "curated_key": keys["curated"], "rows": len(result.rows),
               "rejected": len(result.rejected), "duplicates": result.duplicates}
    log.info(json.dumps(summary))
    return summary
