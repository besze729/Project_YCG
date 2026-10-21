import json
import os
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

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
            config.write_text('[proxy]\nvideo_codec="libx264"\nencoder_preset="ultrafast"\n', encoding="utf-8")
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


if __name__ == "__main__":
    unittest.main()
