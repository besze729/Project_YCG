"""Refine observed health-bar transitions; keep evidence separate from intervals."""

from dataclasses import asdict
from decimal import Decimal
import json
import os
from pathlib import Path
import shlex
import subprocess
from threading import Event
from typing import Callable

from ..config import ServiceConfig
from ..service import SamplingConfig, _probe, sample_frames
from .client import VisionError
from .config import VisionConfig, prompt_hash
from .detection import detect_frames


def read_detections(path: Path, duration_ms: int) -> dict[int, bool]:
    """Reject incomplete or ambiguous evidence instead of manufacturing negatives."""
    points: dict[int, bool] = {}
    previous_index = -1
    with path.open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            try:
                row = json.loads(line)
                timestamp, index, active = row["sourceTimestampMs"], row["imageIndex"], row["activeBossFight"]
                if (type(timestamp) is not int or type(index) is not int or type(active) is not bool
                        or not 0 <= timestamp < duration_ms or index <= previous_index
                        or (points and timestamp <= next(reversed(points)))):
                    raise ValueError("Invalid index, timestamp or prediction")
            except (ValueError, KeyError, TypeError) as exc:
                raise VisionError(f"Invalid detection at {path.name}:{line_number}: {exc}") from exc
            points[timestamp] = active
            previous_index = index
    if not points:
        raise VisionError(f"No detections in {path.name}")
    return points


def boundary_windows(points: dict[int, bool], duration_ms: int) -> list[tuple[int, int]]:
    ordered = sorted(points)
    windows = [(left, right) for left, right in zip(ordered, ordered[1:])
               if points[left] != points[right]]
    # EOF is a coverage boundary, never an invented negative prediction.
    if points[ordered[-1]]:
        windows.append((ordered[-1], duration_ms))
    return windows


def build_intervals(points: dict[int, bool], duration_ms: int) -> list[dict]:
    intervals = []
    current = None
    previous = None
    for timestamp in sorted(points):
        if points[timestamp] and current is None:
            current = {"startMs": timestamp, "startBoundary": None if previous is None else
                       {"beforeMs": previous, "afterMs": timestamp},
                       "startClipped": previous is None}
        elif not points[timestamp] and current is not None:
            intervals.append({**current, "endMs": timestamp,
                              "endBoundary": {"beforeMs": previous, "afterMs": timestamp},
                              "endClipped": False})
            current = None
        previous = timestamp
    if current is not None:
        intervals.append({**current, "endMs": duration_ms, "endBoundary": None, "endClipped": True})
    return intervals


class MinimumDurationFilter:
    """Reject bounded candidates without changing any recorded model prediction."""

    def __init__(self, minimum_ms: int, output: Path, on_progress: Callable[[dict], None]):
        self.minimum_ms = minimum_ms
        self.path = output / "rejected-intervals.jsonl"
        self.on_progress = on_progress
        self.rejected_windows: list[tuple[int, int]] = []
        self.path.touch(exist_ok=False)

    def excludes(self, left: int, right: int) -> bool:
        return any(start <= left and right <= end for start, end in self.rejected_windows)

    def inspect(self, points: dict[int, bool], duration_ms: int, *, stage: str, final: bool = False):
        if self.minimum_ms == 0:
            return
        for interval in build_intervals(points, duration_ms):
            boundary = interval["startBoundary"]
            left = boundary["beforeMs"] if boundary else interval["startMs"]
            right = interval["endMs"]
            if self.excludes(left, right):
                continue
            # No future 5s/1s pass can introduce a positive before this point.
            # Any additional fights found inside the bracket are bounded by the
            # same envelope, so skipping it cannot remove a qualifying interval.
            earliest_start = left + min(1000, interval["startMs"] - left) if boundary else left
            maximum_ms = right - earliest_start
            duration = interval["endMs"] - interval["startMs"]
            precise = final or (all(not interval[key] or
                                   interval[key]["afterMs"] - interval[key]["beforeMs"] <= 1000
                                   for key in ("startBoundary", "endBoundary"))
                               and (not interval["endClipped"] or duration_ms - max(points) <= 1000))
            if (duration if precise else maximum_ms) >= self.minimum_ms:
                continue
            record = {"schemaVersion": 1, "state": "warning", "reason": "below_minimum_duration",
                      "stage": stage, "minimumDurationMs": self.minimum_ms,
                      "windowStartMs": left, "windowEndMs": right,
                      "earlyRejected": not precise}
            if precise:
                record.update(interval)
                record["durationMs"] = duration
                description = f"duration {Decimal(duration) / 1000} s"
            else:
                record["maximumDurationMs"] = maximum_ms
                description = f"maximum possible duration {Decimal(maximum_ms) / 1000} s; further refinement skipped"
            start = interval["startMs"] if precise else left
            label = "interval" if precise else "candidate window"
            record["message"] = (f"Boss fight {label} {_display_timestamp(start)} -> {_display_timestamp(right)} "
                                 f"rejected: {description}; minimum {Decimal(self.minimum_ms) / 1000} s "
                                 "(below_minimum_duration).")
            # Persist before notifying the controller; failures must not silently
            # discard the reason even if a later window or connection fails.
            with self.path.open("a", encoding="utf-8", newline="\n") as destination:
                destination.write(json.dumps(record) + "\n")
                destination.flush()
                os.fsync(destination.fileno())
            self.rejected_windows.append((left, right))
            self.on_progress(record)


def _display_timestamp(timestamp_ms: int) -> str:
    seconds, millis = divmod(timestamp_ms, 1000)
    minutes, seconds = divmod(seconds, 60)
    hours, minutes = divmod(minutes, 60)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d}.{millis:03d}"


def refine_boundaries(game_name: str, video: Path, detections: Path, output: Path,
                      service_config: ServiceConfig, vision_config: VisionConfig, *,
                      api_token: str = "", on_progress: Callable[[dict], None],
                      cancelled: Event | None = None) -> None:
    def check_cancelled():
        if cancelled is not None and cancelled.is_set():
            raise VisionError("Controller disconnected from boss refinement")

    check_cancelled()
    duration_ms = int(_probe(video, service_config).duration_seconds * 1000)
    points = read_detections(detections, duration_ms)
    if min(points) != 0:
        raise VisionError("Coarse detections must start at source timestamp 0")
    # mkdir is exclusive: reruns cannot overwrite evidence from a previous attempt.
    output.mkdir(parents=True, exist_ok=False)
    metadata = {"schemaVersion": 1, "gameName": game_name, "inputVideo": str(video),
                "detectionsFile": str(detections), "durationMs": duration_ms,
                "model": vision_config.model,
                "promptSha256": prompt_hash(vision_config.render_prompt(game_name)),
                "maxImagesPerRequest": vision_config.max_images_per_request,
                "minimumDurationMs": vision_config.minimum_boss_fight_seconds * 1000,
                "refinementStepsMs": [5000, 1000]}
    (output / "manifest.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    on_progress({**metadata, "state": "started"})
    duration_filter = MinimumDurationFilter(metadata["minimumDurationMs"], output, on_progress)
    duration_filter.inspect(points, duration_ms, stage="coarse")
    for step_ms in (5000, 1000):
        windows = boundary_windows(points, duration_ms)
        for number, (left, right) in enumerate(windows, 1):
            check_cancelled()
            if duration_filter.excludes(left, right):
                continue
            # The right endpoint is known already, or is the exclusive EOF.
            last = left + ((right - left - 1) // step_ms) * step_ms
            if last <= left:
                continue
            window = output / f"{step_ms}ms" / f"{number:06d}-{left}-{right}"
            window.mkdir(parents=True)
            context = {"stepSeconds": step_ms // 1000, "windowIndex": number,
                       "totalWindows": len(windows), "windowStartMs": left, "windowEndMs": right}

            def sampling_progress(event):
                check_cancelled()
                data = asdict(event)
                data.pop("operation", None)
                if event.command:
                    data["commandLine"] = (subprocess.list2cmdline(event.command) if os.name == "nt"
                                           else shlex.join(event.command))
                on_progress({**data, **context, "state": "running", "phase": "sampling"})

            def detection_progress(event):
                check_cancelled()
                # Only the whole refinement stage may emit a terminal event.
                on_progress({**event, **context, "state": "running", "phase": "detection"})

            try:
                sample_frames(video, window / "frames",
                              SamplingConfig(Decimal(step_ms) / 1000, Decimal(left + step_ms) / 1000,
                                             Decimal(last) / 1000),
                              service_config, on_progress=sampling_progress)
                detect_frames(game_name, window / "frames", window / "detections.jsonl", vision_config,
                              api_token=api_token, on_progress=detection_progress, cancelled=cancelled)
                fresh = read_detections(window / "detections.jsonl", duration_ms)
                if list(fresh) != list(range(left + step_ms, last + 1, step_ms)):
                    raise VisionError("Refinement frame timestamps do not match the requested window")
                points.update(fresh)
                duration_filter.inspect(points, duration_ms, stage=f"{step_ms // 1000}s")
            except Exception as exc:
                raise VisionError(f"Boss refinement {step_ms // 1000}s window {left}..{right} ms failed: {exc}") from exc
    check_cancelled()
    intervals = [interval for interval in build_intervals(points, duration_ms)
                 if not duration_filter.excludes(interval["startMs"], interval["endMs"])]
    for interval in intervals:
        for key in ("startBoundary", "endBoundary"):
            boundary = interval[key]
            if boundary and boundary["afterMs"] - boundary["beforeMs"] > 1000:
                raise VisionError("Refinement left an unresolved boundary wider than 1000 ms")
    duration_filter.inspect(points, duration_ms, stage="final", final=True)
    intervals = [interval for interval in intervals
                 if not duration_filter.excludes(interval["startMs"], interval["endMs"])]
    result = {**metadata, "intervals": intervals}
    partial = output / "boss-intervals.partial.json"
    final = output / "boss-intervals.json"
    with partial.open("x", encoding="utf-8", newline="\n") as destination:
        json.dump(result, destination, ensure_ascii=False, indent=2)
        destination.flush()
        os.fsync(destination.fileno())
    check_cancelled()
    os.replace(partial, final)
    on_progress({**metadata, "state": "completed", "percent": 100,
                 "outputFile": str(final), "intervals": intervals})
