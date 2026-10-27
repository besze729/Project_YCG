"""Batch detection with source timestamps and durable, inspectable results."""

from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
from threading import Event
from typing import Callable

from .client import LmStudioVisionClient, VisionError
from .config import VisionConfig, prompt_hash


FRAME_NAME = re.compile(r"frame_(\d+)_(\d{2,})-([0-5]\d)-([0-5]\d)\.(\d{3})\.jpg")


@dataclass(frozen=True)
class Frame:
    index: int
    timestamp_ms: int
    path: Path


def list_frames(directory: Path) -> list[Frame]:
    frames = []
    for path in directory.iterdir():
        match = FRAME_NAME.fullmatch(path.name)
        if not path.is_file() or match is None:
            raise VisionError(f"Unexpected entry in sampled frame directory: {path.name}")
        index, hours, minutes, seconds, millis = map(int, match.groups())
        frames.append(Frame(index, ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis, path))
    frames.sort(key=lambda frame: frame.index)
    if not frames:
        raise VisionError("No sampled frames to analyze")
    for previous, current in zip(frames, frames[1:]):
        if current.index <= previous.index or current.timestamp_ms <= previous.timestamp_ms:
            raise VisionError("Sampled frame indices and timestamps must be unique and increasing")
    return frames


def partial_path(output: Path) -> Path:
    return output.with_suffix(".partial.jsonl")


def detect_frames(game_name: str, directory: Path, output: Path, config: VisionConfig,
                  *, api_token: str = "", on_progress: Callable[[dict], None],
                  cancelled: Event | None = None) -> None:
    frames = list_frames(directory)
    prompt = config.render_prompt(game_name)
    partial = partial_path(output)
    if output.exists() or partial.exists():
        raise VisionError("Detection output or partial output already exists")
    output.parent.mkdir(parents=True, exist_ok=True)
    client = LmStudioVisionClient(config, api_token)
    common = {"total_frames": len(frames), "model": config.model,
              "promptSha256": prompt_hash(prompt), "schemaVersion": 1,
              "maxImagesPerRequest": config.max_images_per_request}

    def check_cancelled():
        if cancelled is not None and cancelled.is_set():
            raise VisionError("Controller disconnected from boss detection")

    check_cancelled()
    on_progress({**common, "state": "started", "frame_count": 0, "percent": 0})
    with partial.open("x", encoding="utf-8", newline="\n") as file:
        for offset in range(0, len(frames), config.max_images_per_request):
            check_cancelled()
            batch = frames[offset:offset + config.max_images_per_request]
            try:
                results = client.detect(prompt, [(frame.index, frame.path) for frame in batch])
            except (VisionError, OSError) as exc:
                raise VisionError(f"Boss detection failed for images {batch[0].index}..{batch[-1].index}: {exc}") from exc
            check_cancelled()
            records = [{"imageIndex": frame.index, "frameFile": frame.path.name,
                        "sourceTimestampMs": frame.timestamp_ms, "activeBossFight": result["activeBossFight"]}
                       for frame, result in zip(batch, results)]
            file.write("".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records))
            file.flush()
            os.fsync(file.fileno())
            done = offset + len(batch)
            on_progress({"state": "running", "frame_count": done, "total_frames": len(frames),
                         "percent": done * 100 / len(frames), "results": records,
                         "source_seconds": batch[-1].timestamp_ms / 1000})
    check_cancelled()
    if output.exists():
        raise VisionError("Detection output appeared during analysis")
    # The completed file is published by a same-directory rename, like proxy outputs.
    os.replace(partial, output)
    on_progress({**common, "state": "completed", "frame_count": len(frames), "percent": 100})
