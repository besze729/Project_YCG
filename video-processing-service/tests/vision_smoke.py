"""Opt-in five-image API smoke test against local LM Studio or its simulation."""

import argparse
import json
import os
from pathlib import Path
import subprocess
from tempfile import mkdtemp
from time import monotonic
from unittest.mock import patch

from fastapi.testclient import TestClient

from video_processing_service.api import create_app
from video_processing_service.config import load_config
from video_processing_service.service import SamplingConfig, sample_frames


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:1234")
    args = parser.parse_args()
    output_root = Path(__file__).resolve().parents[1] / "output"
    output_root.mkdir(exist_ok=True)
    root = Path(mkdtemp(prefix="vision-smoke-", dir=output_root))
    source = root / "synthetic.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                    "testsrc2=size=1280x720:rate=1:duration=5", "-c:v", "libx264", str(source)], check=True)
    sample_frames(source, root / "frames", SamplingConfig(1), load_config())
    override = root / "vision.toml"
    override.write_text('[vision]\nbase_url=' + json.dumps(args.base_url) + '\n', encoding="utf-8")
    start = monotonic()
    with patch.dict(os.environ, {"YCG_VISION_CONFIG": str(override)}), TestClient(create_app()) as client:
        response = client.post("/api/boss-detection", json={
            "gameName": "Mortal Shell", "framesDirectory": str(root / "frames"),
            "outputFile": str(root / "detections.jsonl"),
        })
    assert response.status_code == 200, response.text
    events = [json.loads(line) for line in response.text.splitlines()]
    assert [event["state"] for event in events] == ["started", "running", "completed"], events
    rows = [json.loads(line) for line in (root / "detections.jsonl").read_text().splitlines()]
    assert [row["imageIndex"] for row in rows] == list(range(5)), rows
    assert [row["sourceTimestampMs"] for row in rows] == [0, 1000, 2000, 3000, 4000], rows
    assert all(type(row["activeBossFight"]) is bool for row in rows), rows
    (root / "events.json").write_text(json.dumps(events, indent=2), encoding="utf-8")
    print(json.dumps({"result": "VISION_FIVE_IMAGE_API_SMOKE_OK",
                      "elapsedSeconds": round(monotonic() - start, 2), "artifacts": str(root)}))


if __name__ == "__main__":
    main()
