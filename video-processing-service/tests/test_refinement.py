from decimal import Decimal
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from video_processing_service.api import create_app
from video_processing_service.config import load_config
from video_processing_service.service import VideoInfo, _filename_time
from video_processing_service.vision.client import VisionError
from video_processing_service.vision.config import load_vision_config
from video_processing_service.vision.detection import list_frames
from video_processing_service.vision.refinement import build_intervals, read_detections, refine_boundaries


class RefinementTests(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.video = self.root / "proxy.mp4"
        self.video.touch()
        self.coarse = self.root / "coarse.jsonl"
        self.output = self.root / "refinement"
        self.events = []
        self.sent = []
        self.duration = 65000
        self.active = lambda timestamp: 8000 <= timestamp < 42000
        self.cancelled = Event()
        self.fail_on_call = None
        self.calls = 0
        self.save_coarse({0: False, 30000: True, 60000: False})

        def sample(video, directory, sampling, config, **kwargs):
            directory.mkdir()
            start, end, step = (int(value * 1000) for value in
                                (sampling.start_time, sampling.end_time, sampling.interval_seconds))
            for index, timestamp in enumerate(range(start, end + 1, step)):
                (directory / f"frame_{index:06d}_{_filename_time(Decimal(timestamp) / 1000)}.jpg").write_bytes(b"jpeg")

        def detect(client, prompt, images):
            self.calls += 1
            if self.calls == self.fail_on_call:
                raise VisionError("Simulated upstream failure")
            self.assertLessEqual(len(images), 5)
            self.assertIn("Test Game", prompt)
            records = []
            for index, path in images:
                timestamp = next(frame.timestamp_ms for frame in list_frames(path.parent) if frame.index == index)
                self.sent.append(timestamp)
                records.append({"imageIndex": index, "activeBossFight": self.active(timestamp)})
            return records

        for patcher in (patch("video_processing_service.vision.refinement._probe",
                              side_effect=lambda *args: VideoInfo(Decimal(self.duration) / 1000)),
                        patch("video_processing_service.vision.refinement.sample_frames", side_effect=sample),
                        patch("video_processing_service.vision.detection.LmStudioVisionClient.detect", new=detect)):
            patcher.start()
            self.addCleanup(patcher.stop)

    def save_coarse(self, points):
        self.coarse.write_text("".join(json.dumps({"imageIndex": i, "frameFile": "coarse.jpg",
                               "sourceTimestampMs": timestamp, "activeBossFight": active}) + "\n"
                               for i, (timestamp, active) in enumerate(points.items())), encoding="utf-8")

    def run_refinement(self):
        refine_boundaries("Test Game", self.video, self.coarse, self.output, load_config(), load_vision_config(),
                          on_progress=self.events.append, cancelled=self.cancelled)
        return json.loads((self.output / "boss-intervals.json").read_text())

    def test_both_boundaries_and_endpoint_reuse(self):
        result = self.run_refinement()
        self.assertEqual(result["intervals"], [{"startMs": 8000, "endMs": 42000,
                         "startBoundary": {"beforeMs": 7000, "afterMs": 8000},
                         "endBoundary": {"beforeMs": 41000, "afterMs": 42000},
                         "startClipped": False, "endClipped": False}])
        self.assertEqual(self.sent, [5000, 10000, 15000, 20000, 25000, 35000, 40000, 45000, 50000, 55000,
                                     6000, 7000, 8000, 9000, 41000, 42000, 43000, 44000])
        self.assertEqual(len(self.sent), len(set(self.sent)))
        self.assertEqual([e["state"] for e in self.events].count("completed"), 1)
        self.assertEqual({e["stepSeconds"] for e in self.events if "stepSeconds" in e}, {1, 5})

    def test_additional_transitions_split_fights(self):
        self.active = lambda t: 8000 <= t < 13000 or 18000 <= t < 42000
        result = self.run_refinement()
        self.assertEqual([(i["startMs"], i["endMs"]) for i in result["intervals"]], [(8000, 13000), (18000, 42000)])

    def test_empty_result_needs_no_model_calls(self):
        self.save_coarse({0: False, 30000: False, 60000: False})
        self.assertEqual(self.run_refinement()["intervals"], [])
        self.assertEqual(self.sent, [])

    def test_video_edges_and_fractional_tail(self):
        self.duration = 63616
        self.save_coarse({0: True, 30000: True, 60000: True})
        self.active = lambda t: True
        result = self.run_refinement()["intervals"][0]
        self.assertEqual((result["startMs"], result["endMs"]), (0, 63616))
        self.assertTrue(result["startClipped"] and result["endClipped"])
        self.assertEqual(self.sent, [61000, 62000, 63000])

    def test_tail_can_end_before_eof(self):
        self.duration = 71616
        self.save_coarse({0: True, 30000: True, 60000: True})
        self.active = lambda t: t < 68000
        result = self.run_refinement()["intervals"][0]
        self.assertEqual(result["endMs"], 68000)
        self.assertFalse(result["endClipped"])

    def test_nonstandard_coarse_grid_and_already_fine_boundaries(self):
        self.save_coarse({0: False, 7500: True, 11800: False})
        self.active = lambda t: 6200 <= t < 10300
        result = self.run_refinement()["intervals"][0]
        for name in ("startBoundary", "endBoundary"):
            self.assertLessEqual(result[name]["afterMs"] - result[name]["beforeMs"], 1000)
        self.assertTrue(all(t not in (0, 7500, 11800) for t in self.sent))
        self.assertEqual(build_intervals({0: False, 500: True, 900: False}, 1000)[0]["endMs"], 900)

    def test_failure_retains_evidence_without_final_output(self):
        self.fail_on_call = 2
        with self.assertRaisesRegex(VisionError, "5s window 30000..60000"):
            self.run_refinement()
        self.assertFalse((self.output / "boss-intervals.json").exists())
        self.assertEqual(len(list(self.output.rglob("detections.jsonl"))), 1)
        self.assertEqual(len(list(self.output.rglob("detections.partial.jsonl"))), 1)

    def test_cancelled_or_invalid_input_does_not_publish(self):
        self.cancelled.set()
        with self.assertRaisesRegex(VisionError, "disconnected"):
            self.run_refinement()
        self.assertFalse(self.output.exists())
        self.cancelled.clear()
        for points in ({1000: True}, {0: False, 30000: True, 20000: False}, {0: "false"}, {0: False, 65000: True}):
            self.save_coarse(points)
            with self.subTest(points=points), self.assertRaises(VisionError):
                self.run_refinement()
        self.coarse.write_text("[]\n", encoding="utf-8")
        with self.assertRaises(VisionError):
            read_detections(self.coarse, self.duration)

    def test_api_stream_errors_paths_and_exclusive_outputs(self):
        body = {"gameName": "Test Game", "inputVideo": str(self.video),
                "detectionsFile": str(self.coarse), "outputDirectory": str(self.output)}
        with TestClient(create_app()) as client:
            self.assertEqual(client.post("/api/boss-refinement", json=body, headers={"origin": "http://example.com"}).status_code, 403)
            self.assertEqual(client.post("/api/boss-refinement", json={**body, "inputVideo": "relative.mp4"}).status_code, 400)
            response = client.post("/api/boss-refinement", json=body)
            self.assertEqual(response.status_code, 200)
            events = [json.loads(line) for line in response.text.splitlines()]
            self.assertEqual(events[-1]["state"], "completed")
            self.assertEqual(events[-1]["intervals"][0]["startMs"], 8000)
            self.assertTrue(all(e["operation"] == "boss-refinement" for e in events))
            self.assertEqual(client.post("/api/boss-refinement", json=body).status_code, 409)
            self.fail_on_call = self.calls + 1
            failed = client.post("/api/boss-refinement", json={**body, "outputDirectory": str(self.root / "failed")})
            self.assertEqual(json.loads(failed.text.splitlines()[-1])["state"], "failed")


if __name__ == "__main__":
    unittest.main()
