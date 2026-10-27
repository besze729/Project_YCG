"""Real FFmpeg and refinement API against the deterministic Postman LM Studio mock."""

import argparse
import json
import os
from pathlib import Path
import subprocess
from tempfile import mkdtemp
from unittest.mock import patch

from fastapi.testclient import TestClient

from video_processing_service.api import create_app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="Postman simulation LM Studio member URL")
    args = parser.parse_args()
    output = Path(__file__).resolve().parents[1] / "output"
    output.mkdir(exist_ok=True)
    root = Path(mkdtemp(prefix="refinement-smoke-", dir=output))
    source = root / "synthetic.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                    "testsrc2=size=320x180:rate=10:duration=31", "-c:v", "libx264", str(source)], check=True)
    coarse = root / "coarse.jsonl"
    coarse.write_text("".join(json.dumps({"imageIndex": index, "sourceTimestampMs": timestamp,
                                        "frameFile": "seeded.jpg", "activeBossFight": active}) + "\n"
                              for index, (timestamp, active) in enumerate([(0, True), (30000, False)])), encoding="utf-8")
    override = root / "vision.toml"
    override.write_text('[vision]\nbase_url=' + json.dumps(args.base_url) + '\n', encoding="utf-8")
    with patch.dict(os.environ, {"YCG_VISION_CONFIG": str(override), "LM_STUDIO_API_TOKEN": ""}), TestClient(create_app()) as client:
        response = client.post("/api/boss-refinement", json={
            "gameName": "Synthetic test", "inputVideo": str(source), "detectionsFile": str(coarse),
            "outputDirectory": str(root / "refinement"),
        })
    assert response.status_code == 200, response.text
    events = [json.loads(line) for line in response.text.splitlines()]
    (root / "events.json").write_text(json.dumps(events, indent=2), encoding="utf-8")
    assert events[-1]["state"] == "completed", events[-1]
    assert events[-1]["intervals"] == [{"startMs": 0, "startBoundary": None, "startClipped": True,
                                        "endMs": 1000, "endBoundary": {"beforeMs": 0, "afterMs": 1000}, "endClipped": False}]
    rows = [json.loads(line) for path in sorted((root / "refinement").rglob("detections.jsonl"))
            for line in path.read_text().splitlines()]
    assert sorted(row["sourceTimestampMs"] for row in rows) == [1000, 2000, 3000, 4000, 5000, 10000, 15000, 20000, 25000]
    assert sum(e["state"] == "completed" for e in events) == 1
    assert {e["stepSeconds"] for e in events if "stepSeconds" in e} == {1, 5}
    print(json.dumps({"result": "REFINEMENT_API_SMOKE_OK", "frames": len(rows), "artifacts": str(root)}))


if __name__ == "__main__":
    main()
