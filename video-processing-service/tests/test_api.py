import json
import os
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
import unittest
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from unittest.mock import patch

from video_processing_service.service import ProcessingProgress, VideoProcessingError

try:
    from fastapi.testclient import TestClient
    from video_processing_service.api import create_app
except ImportError:
    TestClient = None


@unittest.skipUnless(TestClient and shutil.which("ffmpeg"), "Install .[api,test] and FFmpeg")
class ProxyApiTests(unittest.TestCase):
    def test_local_source_streams_command_progress_and_published_output(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "game video.mp4"
            output = root / "proxy.mp4"
            config = root / "cpu.toml"
            config.write_text('[proxy]\nacceleration="cpu"\nvideo_codec="libx264"\nencoder_preset="ultrafast"\n', encoding="utf-8")
            subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                            "testsrc2=size=320x180:rate=10:duration=2", str(source)], check=True)
            original = source.stat()
            with patch.dict(os.environ, {"YCG_VIDEO_CONFIG": str(config)}), TestClient(create_app()) as client:
                body = {"gameName": " Game ", "inputVideo": str(source), "outputVideo": str(output)}
                response = client.post("/api/proxy", json=body)
                self.assertEqual(response.status_code, 200)
                events = [json.loads(line) for line in response.text.splitlines()]
                self.assertEqual(events[0]["state"], "started")
                self.assertEqual(events[0]["gameName"], "Game")
                self.assertIn(str(source.resolve()), events[0]["command"])
                self.assertIn("-progress", events[0]["command"])
                self.assertIn("game video.mp4", events[0]["commandLine"])
                self.assertTrue(any(event["state"] == "running" for event in events))
                self.assertEqual(events[-1]["state"], "completed")
                self.assertTrue(output.is_file())
                self.assertEqual(source.stat().st_size, original.st_size)
                self.assertEqual(source.stat().st_mtime_ns, original.st_mtime_ns)
                self.assertEqual(client.post("/api/proxy", json=body).status_code, 409)
                body["gameName"] = "   "
                self.assertEqual(client.post("/api/proxy", json=body).status_code, 422)

    def test_probe_failure_is_a_terminal_event_and_releases_worker(self):
        with TemporaryDirectory() as directory, TestClient(create_app()) as client:
            root = Path(directory)
            source = root / "corrupt.mp4"
            source.write_bytes(b"not a video")
            body = {"gameName": "Game", "inputVideo": str(source), "outputVideo": str(root / "out.mp4")}
            for _ in range(2):
                response = client.post("/api/proxy", json=body)
                self.assertEqual(response.status_code, 200)
                self.assertEqual(json.loads(response.text.splitlines()[-1])["state"], "failed")
                self.assertFalse((root / "out.mp4").exists())
            self.assertEqual(client.post("/api/proxy", json=body,
                                        headers={"Origin": "https://example.com"}).status_code, 403)
            body["inputVideo"] = "relative.mp4"
            self.assertEqual(client.post("/api/proxy", json=body).status_code, 400)


@unittest.skipUnless(TestClient and shutil.which("ffmpeg") and shutil.which("ffprobe"),
                     "Install .[api,test] and FFmpeg/FFprobe")
class SamplingApiTests(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / "árvíz proxy video.mp4"
        subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                        "testsrc2=size=64x48:rate=10:duration=4", "-c:v", "libx264",
                        str(self.source)], check=True)
        self.client = self.enterContext(TestClient(create_app()))

    def body(self, **values):
        return {"gameName": " Game ", "inputVideo": str(self.source),
                "outputDirectory": str(self.root / "frames"), "intervalSeconds": 30, **values}

    def events(self, body):
        response = self.client.post("/api/sample", json=body)
        self.assertEqual(response.status_code, 200, response.text)
        return [json.loads(line) for line in response.text.splitlines()]

    def test_short_video_with_default_coarse_interval_and_focused_range(self):
        original = self.source.stat()
        for folder, settings, names in [
            ("coarse", {}, ["frame_000000_00-00-00.000.jpg"]),
            ("fine", {"intervalSeconds": 0.5, "startTime": "00:00:01", "endTime": 2},
             ["frame_000000_00-00-01.000.jpg", "frame_000001_00-00-01.500.jpg",
              "frame_000002_00-00-02.000.jpg"]),
            ("end", {"intervalSeconds": 2, "endTime": 4},
             ["frame_000000_00-00-00.000.jpg", "frame_000001_00-00-02.000.jpg"]),
        ]:
            with self.subTest(folder=folder):
                output = self.root / folder
                body = self.body(outputDirectory=str(output), **settings)
                events = self.events(body)
                self.assertEqual(events[0]["state"], "started")
                self.assertEqual(events[-1]["state"], "completed", events[-1])
                self.assertEqual(events[-1]["frame_count"], len(names))
                self.assertTrue(any(event["state"] == "running" for event in events))
                self.assertIn(str(self.source), events[0]["command"])
                self.assertIn("-progress", events[0]["command"])
                for event in events:
                    self.assertEqual(event["operation"], "sample")
                    self.assertEqual(event["gameName"], "Game")
                    self.assertEqual(event["outputDirectory"], str(output))
                    self.assertEqual(event["total_frames"], len(names))
                    self.assertIsNone(event["percent"])
                self.assertEqual(sorted(p.name for p in output.iterdir()), names)
                for frame in output.iterdir():
                    self.assertTrue(frame.read_bytes().startswith(b"\xff\xd8"))
                self.assertEqual(self.client.post("/api/sample", json=body).status_code, 409)
        self.assertEqual(self.source.stat().st_mtime_ns, original.st_mtime_ns)
        self.assertEqual(self.source.stat().st_size, original.st_size)

    def test_invalid_requests_and_ranges_do_not_publish_frames(self):
        for invalid in [0, -1, "nan", "0.0001", True, None]:
            with self.subTest(interval=invalid):
                response = self.client.post("/api/sample", json=self.body(intervalSeconds=invalid))
                self.assertEqual(response.status_code, 422)
        for values, status in [({"startTime": 2, "endTime": 1}, 422),
                               ({"gameName": " "}, 422), ({"inputVideo": "relative.mp4"}, 400),
                               ({"outputDirectory": "relative"}, 400),
                               ({"inputVideo": str(self.root / "missing.mp4")}, 404)]:
            self.assertEqual(self.client.post("/api/sample", json=self.body(**values)).status_code, status)
        self.assertEqual(self.client.post("/api/sample", json=self.body(),
                                         headers={"Origin": "https://example.com"}).status_code, 403)
        for values in [{"startTime": 4}, {"endTime": 5}]:
            events = self.events(self.body(**values))
            self.assertEqual(events[-1]["state"], "failed")
            self.assertEqual(events[-1]["operation"], "sample")
            self.assertIn("duration", events[-1]["detail"])
        self.assertFalse((self.root / "frames").exists())

    def test_ffmpeg_failure_cleans_staging_and_allows_retry(self):
        with patch("video_processing_service.service._run_ffmpeg",
                   side_effect=VideoProcessingError("test decoder failure")):
            events = self.events(self.body(intervalSeconds=1))
        self.assertEqual(events[-1]["state"], "failed")
        self.assertIn("test decoder failure", events[-1]["detail"])
        self.assertFalse((self.root / "frames").exists())
        self.assertEqual(list(self.root.glob(".ycg-frames-*")), [])
        self.assertEqual(self.events(self.body(intervalSeconds=1))[-1]["state"], "completed")

    def test_proxy_and_sample_share_one_worker(self):
        entered, release = Event(), Event()

        def hold_proxy(*args, on_progress):
            entered.set()
            if not release.wait(5):
                raise RuntimeError("test worker timed out")
            on_progress(ProcessingProgress("proxy", "completed", 40, None, None, 4, 4, 100))

        with patch("video_processing_service.api.create_proxy", side_effect=hold_proxy), ThreadPoolExecutor() as pool:
            running = pool.submit(self.client.post, "/api/proxy", json={
                "gameName": "Game", "inputVideo": str(self.source),
                "outputVideo": str(self.root / "out.mp4"),
            })
            try:
                self.assertTrue(entered.wait(5))
                self.assertEqual(self.client.post("/api/sample", json=self.body()).status_code, 409)
            finally:
                release.set()
            self.assertEqual(running.result(timeout=5).status_code, 200)
        self.assertEqual(self.events(self.body(intervalSeconds=1))[-1]["state"], "completed")


if __name__ == "__main__":
    unittest.main()
