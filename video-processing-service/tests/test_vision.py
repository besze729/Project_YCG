from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import base64
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event, Thread
import time
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from video_processing_service.api import create_app
from video_processing_service.vision.client import LmStudioVisionClient, VisionError, validate_results
from video_processing_service.vision.config import load_vision_config, prompt_hash
from video_processing_service.vision.detection import detect_frames, list_frames, partial_path


class VisionConfigurationTests(unittest.TestCase):
    def test_external_template_override_and_single_substitution(self):
        default = load_vision_config()
        self.assertEqual(default.max_images_per_request, 5)
        self.assertEqual(default.model, "qwen3-vl-8b-instruct")
        self.assertIn("Mortal Shell", default.render_prompt("Mortal Shell"))
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "prompt.tpl").write_text("Game: <?GAME_TITLE>", encoding="utf-8")
            config = root / "vision.toml"
            config.write_text('[vision]\nmax_images_per_request=2\nprompt_file="prompt.tpl"\n', encoding="utf-8")
            loaded = load_vision_config(config)
            self.assertEqual(loaded.max_images_per_request, 2)
            self.assertEqual(loaded.render_prompt('Árvíz <?GAME_TITLE>'), 'Game: Árvíz <?GAME_TITLE>')
            (root / "prompt.tpl").write_text("Changed: <?GAME_TITLE>", encoding="utf-8")
            self.assertEqual(loaded.render_prompt("Game"), "Game: Game")
            self.assertEqual(load_vision_config(config).render_prompt("Game"), "Changed: Game")
            for value in ("0", "-1", "true", "1.5"):
                config.write_text(f"[vision]\nmax_images_per_request={value}\n", encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "positive integer"):
                    load_vision_config(config)
            config.write_text('[vision]\nprompt_file="prompt.tpl"\n', encoding="utf-8")
            for template in ("No title marker", "<?GAME_TITLE> <?UNKNOWN>"):
                (root / "prompt.tpl").write_text(template, encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "template markers"):
                    load_vision_config(config)

    def test_invalid_or_misaligned_results_are_not_false_detections(self):
        valid = [{"imageIndex": 4, "activeBossFight": True}, {"imageIndex": 8, "activeBossFight": False}]
        self.assertEqual(validate_results(json.dumps({"results": valid}), [4, 8]), valid)
        for text in ("not JSON", "```json\n{}\n```", "{}", '{"results":[]}',
                     json.dumps({"results": valid[::-1]}), json.dumps({"results": [valid[0], valid[0]]}),
                     '{"results":[{"imageIndex":4,"activeBossFight":"false"},{"imageIndex":8,"activeBossFight":false}]}',
                     '{"results":[{"imageIndex":4,"activeBossFight":null},{"imageIndex":8,"activeBossFight":false}]}',
                     '{"results":[],"results":[]}', '{"results":NaN}'):
            with self.subTest(text=text), self.assertRaises(VisionError):
                validate_results(text, [4, 8])


class VisionHttpTests(unittest.TestCase):
    """Exercise the production client over HTTP using the LM Studio response contract."""

    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.requests = []
        self.status = 200
        self.delay = 0
        self.fail_after = None
        self.entered, self.release = Event(), Event()
        self.hold = False
        case = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                case.requests.append((self.path, dict(self.headers), body))
                case.entered.set()
                if case.hold:
                    case.release.wait(5)
                time.sleep(case.delay)
                if case.status != 200:
                    status, response = case.status, {"error": "secret upstream body"}
                else:
                    indices = [int(item["text"].split(": ")[1]) for item in body["messages"][1]["content"] if item["type"] == "text"]
                    result = {"results": [{"imageIndex": index, "activeBossFight": index % 2 == 0} for index in indices]}
                    if case.fail_after is not None and len(case.requests) > case.fail_after:
                        result["results"] = []
                    status, response = 200, {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(result)}}]}
                data = json.dumps(response).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.config = replace(load_vision_config(), base_url=f"http://127.0.0.1:{self.server.server_port}/lmstudio")

    def frames(self, count, name="frames"):
        directory = self.root / name
        directory.mkdir()
        for index in reversed(range(count)):
            # Distinct payloads expose accidental image reuse; decoding belongs to LM Studio.
            (directory / f"frame_{index:06}_01-02-{index:02}.125.jpg").write_bytes(b"jpeg-fixture-" + bytes([index]))
        return directory

    def test_batch_sizes_payload_and_source_timestamps(self):
        for count, sizes in ((1, [1]), (5, [5]), (6, [5, 1]), (12, [5, 5, 2])):
            with self.subTest(count=count):
                self.requests.clear()
                frames = self.frames(count, f"frames-{count}")
                output = self.root / f"result-{count}.jsonl"
                events = []
                detect_frames('Mortal Shell', frames, output, self.config, api_token="test-token", on_progress=events.append)
                self.assertEqual([len(body["messages"][1]["content"]) // 2 for _, _, body in self.requests], sizes)
                records = [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]
                self.assertEqual([record["imageIndex"] for record in records], list(range(count)))
                self.assertEqual([record["sourceTimestampMs"] for record in records], [3_720_125 + i * 1000 for i in range(count)])
                self.assertEqual([record["activeBossFight"] for record in records], [i % 2 == 0 for i in range(count)])
                self.assertEqual(events[-1]["state"], "completed")
                self.assertEqual(events[0]["promptSha256"], prompt_hash(self.config.render_prompt("Mortal Shell")))
                self.assertFalse(partial_path(output).exists())
                next_index = 0
                for path, headers, body in self.requests:
                    self.assertEqual(path, "/lmstudio/v1/chat/completions")
                    self.assertEqual(headers["Authorization"], "Bearer test-token")
                    self.assertEqual(body["messages"][0]["content"], self.config.render_prompt("Mortal Shell"))
                    self.assertEqual(len(body["messages"]), 2)
                    self.assertFalse(body["stream"])
                    self.assertEqual(body["temperature"], 0)
                    self.assertTrue(body["response_format"]["json_schema"]["strict"])
                    content = body["messages"][1]["content"]
                    for label, image in zip(content[::2], content[1::2]):
                        self.assertEqual(label["text"], f"imageIndex: {next_index}")
                        self.assertEqual(base64.b64decode(image["image_url"]["url"].split(",")[1]), b"jpeg-fixture-" + bytes([next_index]))
                        next_index += 1

    def test_failed_second_batch_retains_only_validated_results(self):
        self.fail_after = 1
        frames, output = self.frames(12), self.root / "result.jsonl"
        events = []
        with self.assertRaisesRegex(VisionError, "images 5..9"):
            detect_frames("Game", frames, output, self.config, on_progress=events.append)
        self.assertFalse(output.exists())
        self.assertEqual(len(partial_path(output).read_text().splitlines()), 5)
        self.assertEqual(len(self.requests), 2)
        self.assertNotIn("completed", [event["state"] for event in events])
        with self.assertRaisesRegex(VisionError, "already exists"):
            detect_frames("Game", frames, output, self.config, on_progress=events.append)

    def test_http_errors_timeout_and_cancellation(self):
        frames = self.frames(1)
        image = next(frames.iterdir())
        for status in (401, 500):
            self.status = status
            with self.assertRaisesRegex(VisionError, f"HTTP {status}") as failure:
                LmStudioVisionClient(self.config).detect("prompt", [(0, image)])
            self.assertNotIn("secret", str(failure.exception))
        self.status, self.delay = 200, 0.2
        with self.assertRaisesRegex(VisionError, "timed out"):
            LmStudioVisionClient(replace(self.config, request_timeout_seconds=0.03)).detect("prompt", [(0, image)])
        cancelled = Event()
        cancelled.set()
        before = len(self.requests)
        with self.assertRaisesRegex(VisionError, "disconnected"):
            detect_frames("Game", frames, self.root / "cancelled.jsonl", self.config, on_progress=lambda event: None, cancelled=cancelled)
        self.assertEqual(len(self.requests), before)

    def test_api_stream_shared_worker_failure_and_recovery(self):
        frames = self.frames(6)
        config_path = self.root / "vision.toml"
        config_path.write_text(f'[vision]\nbase_url="{self.config.base_url}"\n', encoding="utf-8")
        body = {"gameName": " Game ", "framesDirectory": str(frames), "outputFile": str(self.root / "result.jsonl")}
        with patch.dict(os.environ, {"YCG_VISION_CONFIG": str(config_path)}), TestClient(create_app()) as client:
            self.hold = True
            with ThreadPoolExecutor() as pool:
                pending = pool.submit(client.post, "/api/boss-detection", json=body)
                try:
                    self.assertTrue(self.entered.wait(3))
                    self.assertEqual(client.post("/api/boss-detection", json={**body, "outputFile": str(self.root / "second.jsonl")}).status_code, 409)
                    source = self.root / "source.mp4"
                    source.write_bytes(b"fixture")
                    self.assertEqual(client.post("/api/proxy", json={"gameName": "Game", "inputVideo": str(source), "outputVideo": str(self.root / "out.mp4")}).status_code, 409)
                finally:
                    self.release.set()
                response = pending.result(timeout=5)
            events = [json.loads(line) for line in response.text.splitlines()]
            self.assertEqual([event["state"] for event in events], ["started", "running", "running", "completed"])
            self.assertTrue(all(event["operation"] == "boss-detection" and event["gameName"] == "Game" for event in events))
            self.assertEqual(client.post("/api/boss-detection", json=body).status_code, 409)
            body["outputFile"] = str(self.root / "failed.jsonl")
            self.status = 401
            events = [json.loads(line) for line in client.post("/api/boss-detection", json=body).text.splitlines()]
            self.assertEqual(events[-1]["state"], "failed")
            self.assertIn("HTTP 401", events[-1]["detail"])
            self.assertFalse(Path(body["outputFile"]).exists())
            body["outputFile"] = str(self.root / "recovered.jsonl")
            self.status = 200
            self.assertEqual(json.loads(client.post("/api/boss-detection", json=body).text.splitlines()[-1])["state"], "completed")
            self.assertEqual(client.post("/api/boss-detection", json=body, headers={"Origin": "https://example.com"}).status_code, 403)
            for change, status in (({"framesDirectory": "relative"}, 400), ({"outputFile": str(self.root / "bad.txt")}, 400), ({"gameName": " "}, 422)):
                self.assertEqual(client.post("/api/boss-detection", json={**body, **change}).status_code, status)

    def test_malformed_frame_names_and_duplicate_indices_fail_before_model_calls(self):
        frames = self.frames(1)
        (frames / "frame_000000_01-03-00.000.jpg").write_bytes(b"duplicate index")
        with self.assertRaisesRegex(VisionError, "unique"):
            list_frames(frames)
        (frames / "unexpected.jpg").write_bytes(b"unknown time")
        with self.assertRaisesRegex(VisionError, "Unexpected"):
            list_frames(frames)
        self.assertEqual(self.requests, [])


if __name__ == "__main__":
    unittest.main()
