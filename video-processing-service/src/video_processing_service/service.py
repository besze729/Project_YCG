"""FFmpeg-backed proxy generation and source-timestamped frame extraction."""

from collections import deque
from dataclasses import dataclass, replace
from decimal import Decimal, InvalidOperation, ROUND_CEILING, ROUND_FLOOR
import json
import logging
import math
import os
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
from threading import Thread
from time import monotonic
from typing import Callable, Literal

from .config import ServiceConfig


LOG = logging.getLogger(__name__)
MILLISECOND = Decimal("0.001")
CLOCK = re.compile(r"^(\d+):([0-5]\d):([0-5]\d)(?:\.(\d{1,3}))?$")


class VideoProcessingError(RuntimeError):
    """A video processing stage failed without publishing partial output."""


def _seconds(value: str | int | float | Decimal, name: str) -> Decimal:
    if isinstance(value, str):
        match = CLOCK.fullmatch(value)
        if match:
            hours, minutes, seconds, fraction = match.groups()
            result = (Decimal(hours) * 3600 + Decimal(minutes) * 60
                      + Decimal(seconds) + Decimal((fraction or "0").ljust(3, "0")) / 1000)
        else:
            try:
                result = Decimal(value)
            except InvalidOperation as exc:
                raise ValueError(f"{name} must be seconds or HH:MM:SS[.mmm]") from exc
    else:
        try:
            result = Decimal(str(value))
        except (InvalidOperation, TypeError) as exc:
            raise ValueError(f"{name} must be seconds or HH:MM:SS[.mmm]") from exc
    if not result.is_finite() or result < 0 or result != result.quantize(MILLISECOND):
        raise ValueError(f"{name} must be a nonnegative value with millisecond precision")
    return result


def _ffmpeg_time(seconds: Decimal) -> str:
    return f"{seconds:.3f}"


def _filename_time(seconds: Decimal) -> str:
    milliseconds = int(seconds * 1000)
    hours, remainder = divmod(milliseconds, 3_600_000)
    minutes, remainder = divmod(remainder, 60_000)
    secs, millis = divmod(remainder, 1000)
    return f"{hours:02d}-{minutes:02d}-{secs:02d}.{millis:03d}"


@dataclass(frozen=True)
class SamplingConfig:
    interval_seconds: str | int | float | Decimal
    start_time: str | int | float | Decimal = 0
    end_time: str | int | float | Decimal | None = None

    def __post_init__(self) -> None:
        interval = _seconds(self.interval_seconds, "interval_seconds")
        start = _seconds(self.start_time, "start_time")
        end = None if self.end_time is None else _seconds(self.end_time, "end_time")
        if interval == 0:
            raise ValueError("interval_seconds must be greater than zero")
        if end is not None and end < start:
            raise ValueError("end_time must be at or after start_time")
        object.__setattr__(self, "interval_seconds", interval)
        object.__setattr__(self, "start_time", start)
        object.__setattr__(self, "end_time", end)


@dataclass(frozen=True)
class VideoInfo:
    duration_seconds: Decimal


@dataclass(frozen=True)
class SamplingResult:
    directory: Path
    frame_count: int


@dataclass(frozen=True)
class ProcessingProgress:
    operation: Literal["proxy", "sample"]
    state: Literal["started", "running", "completed", "failed"]
    frame_count: int | None
    fps: float | None
    speed: float | None
    source_seconds: float | None
    duration_seconds: float
    percent: float | None
    total_frames: int | None = None
    detail: str | None = None
    command: tuple[str, ...] | None = None


ProgressCallback = Callable[[ProcessingProgress], None]


def _run_capture(command: list[str], stage: str) -> str:
    started = monotonic()
    LOG.info("%s started", stage)
    try:
        result = subprocess.run(command, capture_output=True, text=True, check=False)
    except OSError as exc:
        raise VideoProcessingError(f"{stage} could not start {command[0]}: {exc}") from exc
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise VideoProcessingError(f"{stage} failed (exit {result.returncode}): {detail}")
    LOG.info("%s finished in %.1f seconds", stage, monotonic() - started)
    return result.stdout


def _optional_number(value: str | None) -> float | None:
    try:
        number = float(value) if value is not None else math.nan
    except ValueError:
        return None
    return number if math.isfinite(number) else None


def _progress_fields(fields: dict[str, str]) -> tuple[int | None, float | None, float | None,
                                                     Decimal | None]:
    frame_text = fields.get("frame")
    frame = int(frame_text) if frame_text and frame_text.isdecimal() else None
    fps = _optional_number(fields.get("fps"))
    speed = _optional_number((fields.get("speed") or "").strip().removesuffix("x"))
    time_text = fields.get("out_time_us")
    try:
        elapsed = Decimal(time_text) / 1_000_000 if time_text else None
        if elapsed is not None and (not elapsed.is_finite() or elapsed < 0):
            elapsed = None
    except InvalidOperation:
        elapsed = None
    return frame, fps, speed, elapsed


def _stop_process(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


def _report_failure(callback: ProgressCallback | None, event: ProcessingProgress) -> None:
    if callback is None:
        return
    try:
        callback(event)
    except Exception:
        LOG.exception("Progress callback failed while reporting a processing failure")


def _run_ffmpeg(command: list[str], stage: str, interval: Decimal,
                on_fields: Callable[[dict[str, str]], None], *,
                on_command: Callable[[tuple[str, ...]], None] | None = None) -> None:
    command = command[:1] + [
        "-nostats", "-stats_period", str(interval), "-progress", "pipe:1",
    ] + command[1:]
    if on_command is not None:
        on_command(tuple(command))
    started = monotonic()
    LOG.info("%s started", stage)
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True,
                                   encoding="utf-8", errors="replace", bufsize=1)
    except OSError as exc:
        raise VideoProcessingError(f"{stage} could not start {command[0]}: {exc}") from exc

    errors: deque[str] = deque(maxlen=40)

    def read_errors() -> None:
        assert process.stderr is not None
        for line in process.stderr:
            message = line.rstrip()
            if message:
                errors.append(message)
                LOG.warning("%s: %s", stage, message)

    error_reader = Thread(target=read_errors, daemon=True)
    error_reader.start()
    fields: dict[str, str] = {}
    try:
        assert process.stdout is not None
        for line in process.stdout:
            key, separator, value = line.rstrip("\r\n").partition("=")
            if not separator:
                continue
            fields[key] = value
            if key == "progress":
                on_fields(fields)
                fields = {}
        code = process.wait()
        error_reader.join()
        if code != 0:
            detail = "\n".join(errors) or "FFmpeg provided no error details"
            raise VideoProcessingError(f"{stage} failed (exit {code}): {detail}")
    except BaseException:
        _stop_process(process)
        error_reader.join()
        raise
    finally:
        if process.stdout is not None:
            process.stdout.close()
        if process.stderr is not None:
            process.stderr.close()
    LOG.info("%s finished in %.1f seconds", stage, monotonic() - started)


def _probe(video: Path, config: ServiceConfig) -> VideoInfo:
    if not video.is_file():
        raise VideoProcessingError(f"Input video does not exist: {video}")
    output = _run_capture([
        config.tools.ffprobe, "-v", "error", "-select_streams", "v:0",
        "-show_entries", "format=duration:stream=codec_type,duration",
        "-of", "json", str(video),
    ], f"probe {video}")
    try:
        data = json.loads(output)
        streams = data["streams"]
        if not streams or streams[0].get("codec_type") != "video":
            raise ValueError("no video stream")
        stream_duration = streams[0].get("duration")
        try:
            duration = Decimal(stream_duration)
        except (TypeError, InvalidOperation):
            duration = Decimal(data["format"]["duration"])
        if not duration.is_finite() or duration <= 0:
            raise ValueError("missing positive duration")
    except (KeyError, TypeError, InvalidOperation, ValueError) as exc:
        raise VideoProcessingError(f"Cannot determine video duration for {video}: {exc}") from exc
    return VideoInfo(duration_seconds=duration)


def _vacant_output(path: Path) -> None:
    if path.exists():
        raise VideoProcessingError(f"Output already exists: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)


def create_proxy(input_video: str | Path, output_video: str | Path,
                 config: ServiceConfig, *,
                 on_progress: ProgressCallback | None = None) -> Path:
    """Create an H.264 MP4 proxy using bounded resolution and configured CBR."""
    source = Path(input_video).resolve()
    output = Path(output_video).resolve()
    if output.suffix.lower() != ".mp4":
        raise ValueError("Proxy output must be an .mp4 file")
    _vacant_output(output)
    duration = _probe(source, config).duration_seconds
    proxy = config.proxy
    if proxy.acceleration not in {"cpu", "cuda"}:
        raise ValueError(f"Unsupported proxy acceleration: {proxy.acceleration}")
    if proxy.acceleration == "cuda" and (
        proxy.video_codec != "h264_nvenc" or proxy.pixel_format != "yuv420p"
    ):
        raise ValueError("CUDA proxy creation requires h264_nvenc and yuv420p")
    scale_options = (f"w={proxy.width}:h={proxy.height}:"
                     "force_original_aspect_ratio=decrease:force_divisible_by=2")
    if proxy.acceleration == "cuda":
        input_options = ["-hwaccel", "cuda", "-hwaccel_output_format", "cuda"]
        scale = f"scale_cuda={scale_options}:format=nv12"
        pixel_format_options: list[str] = []
    else:
        input_options = []
        scale = f"scale={scale_options}"
        pixel_format_options = ["-pix_fmt", proxy.pixel_format]
    bitrate = f"{proxy.bitrate_kbps}k"
    command = [
        config.tools.ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "warning", "-y",
        *input_options,
        "-i", str(source), "-map", "0:v:0", "-an", "-sn", "-dn",
        "-vf", scale, "-c:v", proxy.video_codec, "-preset", proxy.encoder_preset,
        *pixel_format_options, "-b:v", bitrate,
        "-minrate", bitrate, "-maxrate", bitrate,
        "-bufsize", f"{proxy.buffer_size_kbps}k",
    ]
    if proxy.video_codec == "h264_nvenc":
        command += ["-rc", "cbr"]
    else:
        command += ["-x264-params", "nal-hrd=cbr"]
    latest: ProcessingProgress | None = None

    def report_command(actual_command: tuple[str, ...]) -> None:
        if on_progress:
            on_progress(ProcessingProgress(
                "proxy", "started", 0, None, None, 0.0,
                float(duration), 0.0, command=actual_command,
            ))

    def report(fields: dict[str, str]) -> None:
        nonlocal latest
        if on_progress is None:
            return
        frame, fps, speed, elapsed = _progress_fields(fields)
        position = min(duration, elapsed) if elapsed is not None else None
        latest = ProcessingProgress(
            "proxy", "running", frame, fps, speed,
            float(position) if position is not None else None,
            float(duration),
            float(position / duration * 100) if position is not None else None,
        )
        on_progress(latest)

    try:
        with TemporaryDirectory(prefix=".ycg-proxy-", dir=output.parent) as staging:
            temporary = Path(staging) / "proxy.mp4"
            _run_ffmpeg(command + ["-f", "mp4", str(temporary)],
                        f"create proxy {source}", config.progress.interval_seconds, report,
                        on_command=report_command)
            if not temporary.is_file() or temporary.stat().st_size == 0:
                raise VideoProcessingError(f"Proxy creation produced no video: {source}")
            _probe(temporary, config)
            if output.exists():
                raise VideoProcessingError(f"Output appeared during processing: {output}")
            os.replace(temporary, output)
    except BaseException as exc:
        _report_failure(on_progress, ProcessingProgress(
            "proxy", "failed", None, None, None, None, float(duration), None,
            detail=str(exc) or type(exc).__name__,
        ))
        raise
    if on_progress:
        completed = (replace(latest, state="completed", source_seconds=float(duration),
                             percent=100.0) if latest else
                     ProcessingProgress("proxy", "completed", None, None, None,
                                        float(duration), float(duration), 100.0))
        on_progress(completed)
    return output


def sample_frames(video: str | Path, output_directory: str | Path,
                  sampling: SamplingConfig, config: ServiceConfig, *,
                  on_progress: ProgressCallback | None = None) -> SamplingResult:
    """Extract one high-quality JPEG near each requested source timestamp.

    The sample grid starts at start_time. An explicit end_time is inclusive;
    source duration is exclusive because there is no frame at the end instant.
    """
    source = Path(video).resolve()
    output = Path(output_directory).resolve()
    _vacant_output(output)
    duration = _probe(source, config).duration_seconds
    start = sampling.start_time
    interval = sampling.interval_seconds
    end = sampling.end_time
    if start >= duration:
        raise ValueError(f"start_time must be before video duration ({duration} seconds)")
    if end is not None and end > duration:
        raise ValueError(f"end_time exceeds video duration ({duration} seconds)")

    # The probe duration is exclusive; an explicit end is inclusive.
    before_duration = int(((duration - start) / interval).to_integral_value(rounding=ROUND_CEILING))
    through_end = (before_duration if end is None else
                   int(((end - start) / interval).to_integral_value(rounding=ROUND_FLOOR)) + 1)
    count = min(before_duration, through_end)
    # Decode at most half an interval beyond the last requested point so the
    # fps filter can choose the nearest source frame without emitting extras.
    read_length = min(duration - start,
                      max(MILLISECOND, (count - 1) * interval + interval / 2))
    if read_length <= 0:
        raise VideoProcessingError("Sampling range contains no decodable video")

    command = [
        config.tools.ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "warning", "-y",
        "-ss", _ffmpeg_time(start), "-i", str(source), "-t", _ffmpeg_time(read_length),
        "-map", "0:v:0", "-an", "-sn", "-dn",
        "-vf", f"fps=fps=1000/{int(interval * 1000)}:start_time=0:round=near",
        "-fps_mode", "passthrough", "-q:v", str(config.sampling.jpeg_quality),
        "-frames:v", str(count), "-start_number", "0",
    ]
    latest: ProcessingProgress | None = None

    def report_command(actual_command: tuple[str, ...]) -> None:
        if on_progress:
            on_progress(ProcessingProgress(
                "sample", "started", 0, None, None,
                float(start), float(duration), None,
                total_frames=count, command=actual_command,
            ))

    def report(fields: dict[str, str]) -> None:
        nonlocal latest
        if on_progress is None:
            return
        frame, fps, speed, _ = _progress_fields(fields)
        position = (start + (min(frame, count) - 1) * interval
                    if frame is not None and frame > 0 else start)
        latest = ProcessingProgress("sample", "running", frame, fps, speed,
                                    float(position), float(duration), None,
                                    total_frames=count)
        on_progress(latest)

    try:
        with TemporaryDirectory(prefix=".ycg-frames-", dir=output.parent) as staging:
            temporary = Path(staging)
            _run_ffmpeg(command + [str(temporary / "frame_%06d.jpg")],
                        f"sample frames {source}", config.progress.interval_seconds, report,
                        on_command=report_command)
            frames = sorted(temporary.glob("frame_*.jpg"))
            if len(frames) != count:
                raise VideoProcessingError(
                    f"Frame sampling produced {len(frames)} of {count} frames for {source} "
                    f"at {start}–{start + (count - 1) * interval} seconds"
                )
            for index, frame in enumerate(frames):
                timestamp = start + index * interval
                frame.rename(temporary / f"frame_{index:06d}_{_filename_time(timestamp)}.jpg")
            if output.exists():
                raise VideoProcessingError(f"Output appeared during processing: {output}")
            os.rename(temporary, output)
    except BaseException as exc:
        _report_failure(on_progress, ProcessingProgress(
            "sample", "failed", None, None, None, None, float(duration), None,
            total_frames=count, detail=str(exc) or type(exc).__name__,
        ))
        raise
    if on_progress:
        completed = (replace(latest, state="completed", frame_count=count,
                             source_seconds=float(start + (count - 1) * interval))
                     if latest else ProcessingProgress(
                         "sample", "completed", count, None, None,
                         float(start + (count - 1) * interval), float(duration),
                         None, total_frames=count))
        on_progress(completed)
    return SamplingResult(directory=output, frame_count=count)
