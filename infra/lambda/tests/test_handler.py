import gzip
import io

import pyarrow.parquet as pq
import pytest

import handler
from conftest import SAMPLES

RUN = "0f8fad5b-d9cb-469f-a165-70867728950e"
PART_KEY = f"raw/detections/dt=2026-10-15/run_id={RUN}/part-0003.jsonl.gz"
RUN_KEY = f"raw/runs/dt=2026-10-15/run_id={RUN}/run.json"


class FakeS3:
    """The two calls the handler makes, backed by a dict."""

    def __init__(self, objects):
        self.objects = dict(objects)
        self.puts = []

    def get_object(self, Bucket, Key):
        return {"Body": io.BytesIO(self.objects[(Bucket, Key)])}

    def put_object(self, Bucket, Key, Body, ContentType):
        self.puts.append(Key)
        self.objects[(Bucket, Key)] = Body


def read_parquet(s3, key):
    return pq.read_table(io.BytesIO(s3.objects[("lake", key)]))


def test_detection_part_goes_to_curated_and_rejected():
    body = gzip.compress((SAMPLES / "detections_sample.jsonl").read_bytes())
    s3 = FakeS3({("lake", PART_KEY): body})

    summary = handler.process_object(s3, "lake", PART_KEY)

    curated = f"curated/detections/dt=2026-10-15/run_id={RUN}-part-0003.parquet"
    rejected = f"rejected/detections/dt=2026-10-15/run_id={RUN}-part-0003.parquet"
    assert s3.puts == [curated, rejected]
    assert summary["rows"] == 4 and summary["rejected"] == 7 and summary["duplicates"] == 1
    assert read_parquet(s3, curated).num_rows == 4
    assert "reject_reason" in read_parquet(s3, rejected).column_names


def test_reprocessing_writes_the_same_keys():
    body = gzip.compress((SAMPLES / "detections_sample.jsonl").read_bytes())
    s3 = FakeS3({("lake", PART_KEY): body})

    handler.process_object(s3, "lake", PART_KEY)
    handler.process_object(s3, "lake", PART_KEY)

    assert len(set(s3.puts)) == 2
    assert len([k for k in s3.objects if k[1].startswith("curated/")]) == 1


def test_run_summary_goes_to_curated_runs():
    s3 = FakeS3({("lake", RUN_KEY): (SAMPLES / "run_sample.json").read_bytes()})

    handler.process_object(s3, "lake", RUN_KEY)

    table = read_parquet(s3, f"curated/runs/dt=2026-10-15/run_id={RUN}.parquet")
    assert table.num_rows == 1
    assert table.column("requested_classes").to_pylist() == [["person", "cup", "laptop"]]


def test_prefix_before_raw_is_kept():
    keys = handler.output_keys("team-a/" + RUN_KEY)
    assert keys["curated"] == f"team-a/curated/runs/dt=2026-10-15/run_id={RUN}.parquet"


@pytest.mark.parametrize("key", [
    "raw/detections/dt=2026-10-15/run_id=x/part-0000.jsonl.gz",
    f"raw/detections/dt=2026-10-15/run_id={RUN}/run.json",
    f"raw/runs/dt=2026-10-15/run_id={RUN}/part-0000.jsonl.gz",
    f"curated/runs/dt=2026-10-15/run_id={RUN}.parquet",
    "raw/other.txt",
])
def test_unexpected_keys_are_skipped(key):
    s3 = FakeS3({})
    assert handler.process_object(s3, "lake", key) == {"key": key, "skipped": True}
    assert s3.puts == []


def test_event_keys_are_url_decoded(monkeypatch):
    s3 = FakeS3({("lake", RUN_KEY): (SAMPLES / "run_sample.json").read_bytes()})
    monkeypatch.setattr(handler, "_client", lambda: s3)
    encoded = RUN_KEY.replace("=", "%3D")
    event = {"Records": [{"s3": {"bucket": {"name": "lake"}, "object": {"key": encoded}}}]}

    result = handler.handler(event, None)

    assert result["processed"][0]["rows"] == 1
