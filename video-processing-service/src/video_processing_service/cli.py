"""Command-line entry point for local preprocessing."""

import argparse
import logging
import os
import shlex
import subprocess
import sys

from .config import load_config
from .service import (ProcessingProgress, SamplingConfig, VideoProcessingError,
                      create_proxy, sample_frames)


def _clock(seconds: float | None) -> str:
    if seconds is None:
        return "--:--:--"
    hours, remainder = divmod(int(seconds), 3600)
    minutes, remaining_seconds = divmod(remainder, 60)
    return f"{hours:02d}:{minutes:02d}:{remaining_seconds:02d}"


class ConsoleProgress:
    def __init__(self) -> None:
        self._width = 0

    def __call__(self, event: ProcessingProgress) -> None:
        label = "Proxy" if event.operation == "proxy" else "Sampling"
        if event.state == "started":
            if event.command is not None:
                display_command = (subprocess.list2cmdline(event.command) if os.name == "nt"
                                   else shlex.join(event.command))
                print(f"FFmpeg command: {display_command}", flush=True)
            message = f"{label}: starting"
        elif event.state == "completed":
            message = f"{label}: completed"
            if event.frame_count is not None:
                message += f" | frames {event.frame_count:,}"
            if event.fps is not None:
                message += f" | {event.fps:.1f} fps"
            if event.speed is not None:
                message += f" | {event.speed:.2f}x"
        elif event.state == "failed":
            message = f"{label}: failed"
        else:
            parts = [label]
            if event.percent is not None:
                parts.append(f"{event.percent:.1f}%")
            if event.source_seconds is not None:
                parts.append(f"{_clock(event.source_seconds)} / {_clock(event.duration_seconds)}")
            if event.frame_count is not None:
                frames = f"{event.frame_count:,}"
                if event.total_frames is not None:
                    frames += f"/{event.total_frames:,}"
                parts.append(f"frames {frames}")
            if event.fps is not None:
                parts.append(f"{event.fps:.1f} fps")
            if event.speed is not None:
                parts.append(f"{event.speed:.2f}x")
            message = " | ".join(parts)
        if sys.stdout.isatty():
            sys.stdout.write("\r" + message.ljust(self._width))
            sys.stdout.flush()
            self._width = len(message)
            if event.state != "running":
                sys.stdout.write("\n")
                self._width = 0
        else:
            print(message, flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Project YCG video preprocessing")
    parser.add_argument("--config", help="TOML overrides for packaged defaults")
    commands = parser.add_subparsers(dest="operation", required=True)
    proxy = commands.add_parser("proxy", help="Create an H.264 analysis proxy")
    proxy.add_argument("input_video")
    proxy.add_argument("output_video")
    frames = commands.add_parser("sample", help="Extract timestamped JPEG frames")
    frames.add_argument("video")
    frames.add_argument("output_directory")
    frames.add_argument("--interval-seconds", required=True)
    frames.add_argument("--start-time", default="0")
    frames.add_argument("--end-time")
    args = parser.parse_args()
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(message)s")
    progress = ConsoleProgress()
    try:
        config = load_config(args.config)
        if args.operation == "proxy":
            output = create_proxy(args.input_video, args.output_video, config,
                                  on_progress=progress)
            print(output)
        else:
            sampling = SamplingConfig(args.interval_seconds, args.start_time, args.end_time)
            result = sample_frames(args.video, args.output_directory, sampling, config,
                                   on_progress=progress)
            print(f"{result.frame_count} frames: {result.directory}")
    except (ValueError, VideoProcessingError) as exc:
        parser.exit(1, f"Error: {exc}\n")
    except KeyboardInterrupt:
        parser.exit(130, "\nCancelled.\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
