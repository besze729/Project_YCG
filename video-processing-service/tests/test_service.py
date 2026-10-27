from dataclasses import replace
from contextlib import redirect_stdout
from decimal import Decimal
from io import StringIO
import json
import os
from pathlib import Path
import shutil
import shlex
import subprocess
import sys
from tempfile import TemporaryDirectory
from time import monotonic
import unittest

from video_processing_service.config import load_config
from video_processing_service.cli import ConsoleProgress
from video_processing_service.service import (
    ProcessingProgress, SamplingConfig, VideoProcessingError, _filename_time,
    _progress_fields, _run_ffmpeg, create_proxy, sample_frames,
)


class ConfigurationTests(unittest.TestCase):
    def test_config_and_cpu_override(self):
        config = load_config()
        self.assertEqual(config.proxy.video_codec, "h264_nvenc")
        self.assertIn(config.proxy.acceleration, {"cpu", "cuda"})
        self.assertEqual(config.proxy.bitrate_kbps, 2500)
        self.assertEqual(config.proxy.buffer_size_kbps, 5000)
        self.assertEqual((config.proxy.width, config.proxy.height), (1280, 720))
        self.assertGreaterEqual(config.progress.interval_seconds, Decimal("0.1"))
        with TemporaryDirectory() as directory:
            path = Path(directory) / "custom.toml"
            path.write_text('[proxy]\nacceleration = "cpu"\nvideo_codec = "libx264"\nencoder_preset = "medium"\n'
                            'width = 960\nheight = 540\nbitrate_kbps = 3000\n',
                            encoding="utf-8")
            overridden = load_config(path)
            self.assertEqual(overridden.proxy.video_codec, "libx264")
            self.assertEqual(overridden.proxy.acceleration, "cpu")
            self.assertEqual((overridden.proxy.width, overridden.proxy.height), (960, 540))
            self.assertEqual(overridden.proxy.bitrate_kbps, 3000)

    def test_progress_interval_override(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "custom.toml"
            path.write_text("[progress]\ninterval_seconds = 0.5\n", encoding="utf-8")
            self.assertEqual(str(load_config(path).progress.interval_seconds), "0.5")
            path.write_text("[progress]\ninterval_seconds = 0.01\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "progress.interval_seconds"):
                load_config(path)

    def test_invalid_config_is_rejected(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "config.toml"
            path.write_text("[proxy]\nbitrate_kbps = -1\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "bitrate_kbps"):
                load_config(path)

    def test_cuda_mode_requires_nvenc_and_yuv420p(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "cuda.toml"
            path.write_text('[proxy]\nacceleration = "cuda"\n', encoding="utf-8")
            self.assertEqual(load_config(path).proxy.acceleration, "cuda")
            path.write_text('[proxy]\nacceleration = "cuda"\n'
                            'video_codec = "libx264"\n', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "requires proxy.video_codec"):
                load_config(path)
            path.write_text('[proxy]\nacceleration = "cuda"\n'
                            'pixel_format = "yuv444p"\n', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "requires proxy.pixel_format"):
                load_config(path)
            path.write_text('[proxy]\nacceleration = "opencl"\n', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "proxy.acceleration"):
                load_config(path)


class TimeTests(unittest.TestCase):
    def test_millisecond_timestamps_and_range(self):
        sampling = SamplingConfig("0.5", "01:10:30.125", "01:10:31.125")
        self.assertEqual(str(sampling.start_time), "4230.125")
        self.assertEqual(_filename_time(sampling.start_time), "01-10-30.125")
        self.assertEqual(_filename_time(sampling.start_time + sampling.interval_seconds),
                         "01-10-30.625")

    def test_bad_ranges_are_rejected(self):
        for args in [(-1,), (0,), ("0.0001",), (1, "01:60:00"), (1, 10, 9)]:
            with self.subTest(args=args), self.assertRaises(ValueError):
                SamplingConfig(*args)


class ProgressTests(unittest.TestCase):
    def test_missing_ffmpeg_values_are_optional(self):
        self.assertEqual(_progress_fields({
            "frame": "12", "fps": "N/A", "speed": " 2.5x", "out_time_us": "1500000",
        }), (12, None, 2.5, 1.5))
        self.assertEqual(_progress_fields({
            "frame": "N/A", "fps": "nan", "speed": "N/A", "out_time_us": "N/A",
        }), (None, None, None, None))

    def test_console_formats_live_status(self):
        output = StringIO()
        with redirect_stdout(output):
            ConsoleProgress()(ProcessingProgress("proxy", "running", 120, 75.2, 1.5,
                                                 90.0, 360.0, 25.0))
        self.assertIn("Proxy | 25.0% | 00:01:30 / 00:06:00 | frames 120 | 75.2 fps | 1.50x",
                      output.getvalue())

    def test_console_shows_exact_ffmpeg_arguments_before_status(self):
        command = ("ffmpeg", "-nostats", "-progress", "pipe:1", "-i", "my video.mp4")
        output = StringIO()
        with redirect_stdout(output):
            ConsoleProgress()(ProcessingProgress(
                "proxy", "started", 0, None, None, 0.0, 30.0, 0.0,
                command=command,
            ))
        expected = (subprocess.list2cmdline(command) if os.name == "nt"
                    else shlex.join(command))
        self.assertEqual(output.getvalue(),
                         f"FFmpeg command: {expected}\nProxy: starting\n")


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"),
                     "FFmpeg and FFprobe required")
class FfmpegIntegrationTests(unittest.TestCase):
    def test_full_video_keeps_final_sample_before_duration(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "late-final-sample.mp4"
            subprocess.run([
                "ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                "testsrc2=size=64x48:rate=60:duration=33.616",
                "-c:v", "libx264", str(source),
            ], check=True)
            result = sample_frames(source, root / "frames", SamplingConfig(30), load_config())
            self.assertEqual(result.frame_count, 2)
            self.assertEqual([frame.name for frame in sorted(result.directory.iterdir())], [
                "frame_000000_00-00-00.000.jpg", "frame_000001_00-00-30.000.jpg",
            ])

    def test_sample_content_matches_requested_timestamps(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "color-timeline.mp4"
            # Brief markers at 0s and 2s reveal sampling shifted by half an interval.
            subprocess.run([
                "ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                "color=c=red:s=32x32:r=10:d=0.2[a];"
                "color=c=blue:s=32x32:r=10:d=1.8[b];"
                "color=c=green:s=32x32:r=10:d=0.2[c];"
                "color=c=yellow:s=32x32:r=10:d=1.8[d];"
                "[a][b][c][d]concat=n=4:v=1:a=0",
                "-c:v", "libx264", str(source),
            ], check=True)
            config = load_config()
            result = sample_frames(source, root / "frames", SamplingConfig(2), config)
            self.assertEqual(result.frame_count, 2)
            pixels = []
            for frame in sorted(result.directory.iterdir()):
                pixels.append(subprocess.run([
                    "ffmpeg", "-v", "error", "-i", str(frame),
                    "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1",
                ], check=True, capture_output=True).stdout[:3])
            red, green = pixels
            self.assertGreater(red[0], 200, f"0s should be red: {list(red)}")
            self.assertLess(red[2], 50)
            self.assertGreater(green[1], 80, f"2s should be green: {list(green)}")
            self.assertLess(green[0], 50)

    @unittest.skipUnless(shutil.which("nvidia-smi"), "NVIDIA GPU required")
    def test_cuda_decode_scale_and_nvenc_match_cpu_proxy_timeline(self):
        gpu = subprocess.run(["nvidia-smi", "-L"], capture_output=True, text=True)
        if gpu.returncode != 0:
            self.skipTest("NVIDIA GPU unavailable")
        with TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "h264-source.mp4"
            subprocess.run([
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=30:duration=4",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                str(source),
            ], check=True)
            base = load_config()
            base = replace(base, proxy=replace(base.proxy, acceleration="cpu"))
            base = replace(base, progress=replace(base.progress,
                                                  interval_seconds=Decimal("1.0")))
            cpu = create_proxy(source, root / "cpu.mp4", base)
            cuda = create_proxy(source, root / "cuda.mp4",
                                replace(base, proxy=replace(base.proxy, acceleration="cuda")))

            def metadata(path: Path) -> dict:
                result = subprocess.run([
                    "ffprobe", "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=codec_name,pix_fmt,width,height,nb_frames,duration",
                    "-of", "json", str(path),
                ], check=True, capture_output=True, text=True)
                return json.loads(result.stdout)["streams"][0]

            cpu_info, cuda_info = metadata(cpu), metadata(cuda)
            for field in ("codec_name", "pix_fmt", "width", "height", "nb_frames", "duration"):
                self.assertEqual(cpu_info[field], cuda_info[field], field)
            self.assertEqual(cuda_info["codec_name"], "h264")
            self.assertEqual(cuda_info["pix_fmt"], "yuv420p")
            frames = sample_frames(cuda, root / "cuda-frames", SamplingConfig(2), base)
            self.assertEqual(frames.frame_count, 2)

            unavailable = replace(base,
                                  proxy=replace(base.proxy, acceleration="cuda"),
                                  tools=replace(base.tools, ffmpeg="missing-ffmpeg-executable"))
            failure_events: list[ProcessingProgress] = []
            with self.assertRaisesRegex(VideoProcessingError, "could not start"):
                create_proxy(source, root / "cuda-unavailable.mp4", unavailable,
                             on_progress=failure_events.append)
            self.assertEqual(failure_events[-1].state, "failed")
            self.assertFalse((root / "cuda-unavailable.mp4").exists())
            self.assertEqual(list(root.glob(".ycg-proxy-*")), [])

    def test_progress_arrives_before_ffmpeg_exits_and_callback_can_stop_it(self):
        command = [
            "ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error",
            "-re", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=10:duration=2",
            "-f", "null", "NUL" if os.name == "nt" else "/dev/null",
        ]
        updates: list[tuple[float, str]] = []
        _run_ffmpeg(command, "test progress", Decimal("0.2"),
                    lambda fields: updates.append((monotonic(), fields["progress"])))
        self.assertIn("continue", [value for _, value in updates[:-1]])
        self.assertEqual(updates[-1][1], "end")
        self.assertGreater(updates[-1][0] - updates[0][0], 0.1)

        slow_command = command.copy()
        slow_command[slow_command.index("testsrc2=size=160x90:rate=10:duration=2")] = (
            "testsrc2=size=160x90:rate=10:duration=10"
        )
        started = monotonic()

        def stop(_fields: dict[str, str]) -> None:
            raise RuntimeError("stop requested")

        with self.assertRaisesRegex(RuntimeError, "stop requested"):
            _run_ffmpeg(slow_command, "test interrupt", Decimal("0.2"), stop)
        self.assertLess(monotonic() - started, 5)

    def test_proxy_and_coarse_fine_sampling(self):
        config = load_config()
        config = replace(config, proxy=replace(config.proxy,
                         acceleration="cpu", video_codec="libx264", encoder_preset="medium"),
                         progress=replace(config.progress,
                                          interval_seconds=Decimal("1.0")))
        with TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.mp4"
            subprocess.run([
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=10:duration=4",
                "-c:v", "libx264", str(source),
            ], check=True)
            proxy_events: list[ProcessingProgress] = []
            proxy = create_proxy(source, root / "proxy.mp4", config,
                                 on_progress=proxy_events.append)
            self.assertTrue(proxy.is_file())
            self.assertEqual([proxy_events[0].state, proxy_events[-1].state],
                             ["started", "completed"])
            command = proxy_events[0].command
            self.assertIsNotNone(command)
            self.assertEqual(command[0], config.tools.ffmpeg)
            self.assertEqual(command[command.index("-progress") + 1], "pipe:1")
            self.assertEqual(command[command.index("-vf") + 1].split(":", 1)[0],
                             "scale=w=1280")
            for option in ("-b:v", "-minrate", "-maxrate"):
                self.assertEqual(command[command.index(option) + 1], "2500k")
            self.assertEqual(command[command.index("-bufsize") + 1], "5000k")
            self.assertTrue(command[-1].endswith("proxy.mp4"))
            self.assertTrue(any(event.state == "running" and event.frame_count is not None
                                and event.fps is not None for event in proxy_events))
            self.assertEqual(proxy_events[-1].percent, 100.0)
            metadata = subprocess.run([
                "ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=codec_name,height,width", "-of", "csv=p=0", str(proxy),
            ], check=True, text=True, capture_output=True).stdout.strip()
            self.assertIn("h264", metadata)
            self.assertIn("1280", metadata)
            self.assertIn("720", metadata)

            override = root / "cpu.toml"
            override.write_text('[proxy]\nacceleration = "cpu"\nvideo_codec = "libx264"\n'
                                'encoder_preset = "medium"\n'
                                '[progress]\ninterval_seconds = 1.0\n',
                                encoding="utf-8")
            source_root = Path(__file__).resolve().parents[1] / "src"
            cli = subprocess.run([
                sys.executable, "-m", "video_processing_service", "--config",
                str(override), "proxy", str(source), str(root / "cli_proxy.mp4"),
            ], env={**os.environ, "PYTHONPATH": str(source_root)},
                check=True, text=True, capture_output=True)
            self.assertIn("Proxy |", cli.stdout)
            self.assertIn("FFmpeg command: ", cli.stdout)
            self.assertIn("-progress pipe:1", cli.stdout)
            self.assertLess(cli.stdout.index("FFmpeg command: "),
                            cli.stdout.index("Proxy: starting"))
            self.assertIn("fps", cli.stdout)
            self.assertIn("Proxy: completed", cli.stdout)
            self.assertTrue((root / "cli_proxy.mp4").is_file())

            sample_events: list[ProcessingProgress] = []
            coarse = sample_frames(proxy, root / "coarse", SamplingConfig(2), config,
                                   on_progress=sample_events.append)
            self.assertEqual(coarse.frame_count, 2)
            self.assertEqual(sample_events[-1].state, "completed")
            self.assertTrue(any(argument.startswith("fps=fps=1000/2000:")
                                for argument in sample_events[0].command))
            self.assertEqual(sample_events[-1].source_seconds, 2.0)
            self.assertTrue(any(event.state == "running" and event.total_frames == 2
                                and event.percent is None for event in sample_events))
            self.assertEqual([path.name for path in sorted(coarse.directory.iterdir())], [
                "frame_000000_00-00-00.000.jpg", "frame_000001_00-00-02.000.jpg",
            ])
            fine = sample_frames(proxy, root / "fine",
                                 SamplingConfig("0.5", "00:00:01", "00:00:02"), config)
            self.assertEqual(fine.frame_count, 3)
            self.assertTrue((fine.directory / "frame_000002_00-00-02.000.jpg").is_file())
            with self.assertRaisesRegex(VideoProcessingError, "already exists"):
                sample_frames(proxy, root / "coarse", SamplingConfig(2), config)

            broken = replace(config, proxy=replace(config.proxy,
                             encoder_preset="invalid-preset"))
            failure_events: list[ProcessingProgress] = []
            with self.assertLogs("video_processing_service.service", level="WARNING"):
                with self.assertRaisesRegex(VideoProcessingError, "create proxy .* failed"):
                    create_proxy(source, root / "failed.mp4", broken,
                                 on_progress=failure_events.append)
            self.assertEqual(failure_events[-1].state, "failed")
            self.assertFalse((root / "failed.mp4").exists())
            self.assertEqual(list(root.glob(".ycg-proxy-*")), [])

            interrupted_events: list[ProcessingProgress] = []

            def stop_after_progress(event: ProcessingProgress) -> None:
                interrupted_events.append(event)
                if event.state == "running":
                    raise RuntimeError("consumer stopped")

            with self.assertRaisesRegex(RuntimeError, "consumer stopped"):
                create_proxy(source, root / "interrupted.mp4", config,
                             on_progress=stop_after_progress)
            self.assertEqual(interrupted_events[-1].state, "failed")
            self.assertFalse((root / "interrupted.mp4").exists())
            self.assertEqual(list(root.glob(".ycg-proxy-*")), [])

            cancelled_events: list[ProcessingProgress] = []

            def cancel(event: ProcessingProgress) -> None:
                cancelled_events.append(event)
                if event.state == "running":
                    raise KeyboardInterrupt()

            with self.assertRaises(KeyboardInterrupt):
                create_proxy(source, root / "cancelled.mp4", config,
                             on_progress=cancel)
            self.assertEqual(cancelled_events[-1].state, "failed")
            self.assertFalse((root / "cancelled.mp4").exists())
            self.assertEqual(list(root.glob(".ycg-proxy-*")), [])


if __name__ == "__main__":
    unittest.main()
