"""LM Studio's local image chat adapter. No tools or conversation history."""

import base64
import http.client
import json
from pathlib import Path
import socket
from urllib.parse import urlsplit

from .config import VisionConfig


class VisionError(RuntimeError):
    pass


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON field: {key}")
        result[key] = value
    return result


def _invalid_constant(value: str):
    raise ValueError(f"Invalid JSON constant: {value}")


def parse_json(text: str):
    return json.loads(text, object_pairs_hook=_unique_object, parse_constant=_invalid_constant)


def validate_results(text: str, indices: list[int]) -> list[dict]:
    try:
        body = parse_json(text)
        if not isinstance(body, dict) or set(body) != {"results"}:
            raise ValueError("Expected a results object")
        results = body["results"]
        if not isinstance(results, list) or len(results) != len(indices):
            raise ValueError("Expected exactly one result per input image")
        for index, result in zip(indices, results):
            if not isinstance(result, dict) or set(result) != {"imageIndex", "activeBossFight"}:
                raise ValueError("Invalid result fields")
            if type(result["imageIndex"]) is not int or result["imageIndex"] != index:
                raise ValueError("Image indices must match the input order exactly")
            if type(result["activeBossFight"]) is not bool:
                raise ValueError("activeBossFight must be a JSON boolean")
        return results
    except (ValueError, TypeError) as exc:
        raise VisionError(f"Invalid LM Studio detection response: {exc}") from exc


class LmStudioVisionClient:
    def __init__(self, config: VisionConfig, api_token: str = ""):
        self.config = config
        self.api_token = api_token

    def detect(self, prompt: str, images: list[tuple[int, Path]]) -> list[dict]:
        if not images or len(images) > self.config.max_images_per_request:
            raise ValueError("Image count exceeds the configured batch bounds")
        content = []
        for index, path in images:
            content.append({"type": "text", "text": f"imageIndex: {index}"})
            content.append({"type": "image_url", "image_url": {
                "url": "data:image/jpeg;base64," + base64.b64encode(path.read_bytes()).decode("ascii"),
            }})
        schema = {"type": "object", "additionalProperties": False, "required": ["results"],
                  "properties": {"results": {"type": "array", "minItems": len(images), "maxItems": len(images),
                    "items": {"type": "object", "additionalProperties": False,
                              "required": ["imageIndex", "activeBossFight"],
                              "properties": {"imageIndex": {"type": "integer"},
                                             "activeBossFight": {"type": "boolean"}}}}}}
        payload = {"model": self.config.model, "temperature": 0, "stream": False,
                   "max_tokens": self.config.max_output_tokens,
                   "messages": [{"role": "system", "content": prompt}, {"role": "user", "content": content}],
                   "response_format": {"type": "json_schema", "json_schema": {
                       "name": "boss_fight_detection", "strict": True, "schema": schema}}}
        url = urlsplit(self.config.base_url)
        connection_type = http.client.HTTPSConnection if url.scheme == "https" else http.client.HTTPConnection
        connection = connection_type(url.hostname, url.port, timeout=self.config.connect_timeout_seconds)
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.api_token:
            headers["Authorization"] = "Bearer " + self.api_token
        try:
            connection.connect()
            connection.sock.settimeout(self.config.request_timeout_seconds)
            connection.request("POST", url.path.rstrip("/") + "/v1/chat/completions",
                               json.dumps(payload, ensure_ascii=False).encode("utf-8"), headers)
            response = connection.getresponse()
            if response.status != 200:
                raise VisionError(f"LM Studio returned HTTP {response.status}; check the vision model and API token")
            raw = response.read(1_048_577)
            if len(raw) > 1_048_576:
                raise VisionError("LM Studio response exceeds the 1 MiB limit")
            try:
                envelope = parse_json(raw.decode("utf-8"))
                choices = envelope["choices"]
                if len(choices) != 1 or choices[0]["finish_reason"] != "stop":
                    raise ValueError("Response was not completed")
                text = choices[0]["message"]["content"]
                if not isinstance(text, str):
                    raise ValueError("Missing final text")
            except (ValueError, TypeError, KeyError, IndexError) as exc:
                raise VisionError("LM Studio returned an invalid or incomplete chat response") from exc
            return validate_results(text, [index for index, _ in images])
        except (TimeoutError, socket.timeout) as exc:
            raise VisionError("LM Studio request timed out; processing may still be running on the server") from exc
        except (OSError, http.client.HTTPException) as exc:
            raise VisionError("Could not communicate with the local LM Studio server") from exc
        finally:
            connection.close()
