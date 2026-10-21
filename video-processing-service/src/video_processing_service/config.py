"""Validated FFmpeg settings from a user-editable TOML file."""

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
import tomllib


DEFAULT_CONFIG = Path(__file__).resolve().parent / "config.toml"


@dataclass(frozen=True)
class ToolConfig:
    ffmpeg: str
    ffprobe: str


@dataclass(frozen=True)
class ProxyConfig:
    acceleration: str
    video_codec: str
    width: int
    height: int
    bitrate_kbps: int
    buffer_size_kbps: int
    pixel_format: str
    encoder_preset: str


@dataclass(frozen=True)
class FrameConfig:
    jpeg_quality: int


@dataclass(frozen=True)
class ProgressConfig:
    interval_seconds: Decimal


@dataclass(frozen=True)
class ServiceConfig:
    tools: ToolConfig
    proxy: ProxyConfig
    sampling: FrameConfig
    progress: ProgressConfig


def _section(data: dict, name: str, expected: set[str]) -> dict:
    section = data.get(name)
    if not isinstance(section, dict):
        raise ValueError(f"Missing [{name}] configuration section")
    unknown = set(section) - expected
    missing = expected - set(section)
    if unknown or missing:
        raise ValueError(f"Invalid [{name}] keys; missing={sorted(missing)}, unknown={sorted(unknown)}")
    return section


def _positive_int(value: object, name: str) -> int:
    if type(value) is not int or value <= 0:
        raise ValueError(f"{name} must be a positive integer")
    return value


def _nonempty_string(value: object, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be a nonempty string")
    return value


def _read_config(config_path: Path) -> dict:
    try:
        with config_path.open("rb") as file:
            return tomllib.load(file)
    except (OSError, tomllib.TOMLDecodeError) as exc:
        raise ValueError(f"Cannot read configuration {config_path}: {exc}") from exc


def load_config(path: str | Path | None = None) -> ServiceConfig:
    data = _read_config(DEFAULT_CONFIG)
    if path is not None:
        override = _read_config(Path(path))
        for name, values in override.items():
            if name not in data or not isinstance(values, dict):
                raise ValueError(f"Unknown or invalid configuration section: {name}")
            data[name].update(values)

    unknown_sections = set(data) - {"tools", "proxy", "sampling", "progress"}
    if unknown_sections:
        raise ValueError(f"Unknown configuration sections: {sorted(unknown_sections)}")
    tools = _section(data, "tools", {"ffmpeg", "ffprobe"})
    proxy = _section(data, "proxy", {
        "acceleration", "video_codec", "width", "height", "bitrate_kbps", "buffer_size_kbps",
        "pixel_format", "encoder_preset",
    })
    sampling = _section(data, "sampling", {"jpeg_quality"})
    progress = _section(data, "progress", {"interval_seconds"})

    codec = _nonempty_string(proxy["video_codec"], "proxy.video_codec")
    if codec not in {"h264_nvenc", "libx264"}:
        raise ValueError("proxy.video_codec must be h264_nvenc or libx264")
    acceleration = _nonempty_string(proxy["acceleration"], "proxy.acceleration")
    if acceleration not in {"cpu", "cuda"}:
        raise ValueError("proxy.acceleration must be cpu or cuda")
    if acceleration == "cuda" and codec != "h264_nvenc":
        raise ValueError("proxy.acceleration=cuda requires proxy.video_codec=h264_nvenc")
    if acceleration == "cuda" and proxy["pixel_format"] != "yuv420p":
        raise ValueError("proxy.acceleration=cuda currently requires proxy.pixel_format=yuv420p")
    width = _positive_int(proxy["width"], "proxy.width")
    height = _positive_int(proxy["height"], "proxy.height")
    if width % 2 or height % 2:
        raise ValueError("proxy.width and proxy.height must be even")
    jpeg_quality = _positive_int(sampling["jpeg_quality"], "sampling.jpeg_quality")
    if jpeg_quality > 31:
        raise ValueError("sampling.jpeg_quality must be between 1 and 31")
    interval_value = progress["interval_seconds"]
    if isinstance(interval_value, bool) or not isinstance(interval_value, (int, float)):
        raise ValueError("progress.interval_seconds must be a number")
    try:
        progress_interval = Decimal(str(interval_value))
    except InvalidOperation as exc:
        raise ValueError("progress.interval_seconds must be a number") from exc
    if not progress_interval.is_finite() or progress_interval < Decimal("0.1"):
        raise ValueError("progress.interval_seconds must be at least 0.1")
    return ServiceConfig(
        tools=ToolConfig(
            ffmpeg=_nonempty_string(tools["ffmpeg"], "tools.ffmpeg"),
            ffprobe=_nonempty_string(tools["ffprobe"], "tools.ffprobe"),
        ),
        proxy=ProxyConfig(
            acceleration=acceleration,
            video_codec=codec,
            width=width,
            height=height,
            bitrate_kbps=_positive_int(proxy["bitrate_kbps"], "proxy.bitrate_kbps"),
            buffer_size_kbps=_positive_int(proxy["buffer_size_kbps"], "proxy.buffer_size_kbps"),
            pixel_format=_nonempty_string(proxy["pixel_format"], "proxy.pixel_format"),
            encoder_preset=_nonempty_string(proxy["encoder_preset"], "proxy.encoder_preset"),
        ),
        sampling=FrameConfig(jpeg_quality=jpeg_quality),
        progress=ProgressConfig(interval_seconds=progress_interval),
    )
