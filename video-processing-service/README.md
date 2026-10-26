# Video Processing Service

Standalone Python 3.11+ preprocessing component for Project YCG. It uses local
FFmpeg/FFprobe to create a 480p H.264 proxy and extract JPEG frames for coarse
or focused visual analysis. It does not perform boss recognition.

## Requirements

- Python 3.11 or newer
- FFmpeg and FFprobe on `PATH`, or paths set in the configuration
- NVIDIA driver and FFmpeg with `h264_nvenc` for the default proxy encoder
- For optional CUDA decoding and scaling: FFmpeg with CUDA hardware decoding and
  the `scale_cuda` filter, plus a compatible NVIDIA GPU and driver

Install from this directory with `py -m pip install -e .` on Windows (or
`python -m pip install -e .` elsewhere), then run `ycg-video`. The module can
also run without installation; see the CMD example below.

## Local HTTP service

The CLI works without web dependencies. To run the controller integration from
this directory on Windows:

```powershell
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[api,test]"
.\start-server.ps1
```

The server listens on `127.0.0.1:8090`; `YCG_VIDEO_PORT` overrides the port and
`YCG_VIDEO_CONFIG` selects an optional TOML override file. The server reloads the
configuration for each request. `GET /health` reports readiness and `/docs`
exposes the generated API documentation.

`POST /api/proxy` takes JSON `{gameName,inputVideo,outputVideo}` with a nonblank
game title and absolute paths. It streams `application/x-ndjson` progress with
`started`, `running`, `completed`, or `failed` states, plus heartbeat records.
The start record includes the argument array `command` and display string
`commandLine`. Existing outputs are rejected; a failed run publishes no partial
proxy. Only one processing operation (proxy or sampling) runs at once;
concurrent requests receive HTTP 409.
The controller and Python process must have access to the same local source
paths. No upload, input staging copy, or network share translation is performed.

`POST /api/sample` samples an existing local video, normally the completed proxy:

```json
{
  "gameName": "Mortal Shell",
  "inputVideo": "F:\\YCG\\job\\0001-proxy.mp4",
  "outputDirectory": "F:\\YCG\\job\\0001-frames",
  "intervalSeconds": 30
}
```

`intervalSeconds` is required. Optional `startTime` (default 0) and `endTime`
limit the range. Time values accept seconds as numbers or strings, or
`HH:MM:SS[.mmm]`, with millisecond precision; the interval must be positive.
An explicit end is inclusive, while the video duration is exclusive.
For example, `intervalSeconds: 0.5, startTime: 10, endTime: 11` requests
frames at 10, 10.5, and 11 seconds. A nonempty video shorter than the interval
still produces the sample at 0 seconds.

Sampling streams the same NDJSON lifecycle, with `operation: "sample"`,
`inputVideo`, `outputDirectory`, `frame_count`, `total_frames`, and
`source_seconds` (last requested sample timestamp). `percent` is null.
The final `completed` event is emitted only after the entire frame directory
has been published. Invalid request values return 422, relative paths 400,
missing inputs 404, and an existing output or busy worker 409. Media-dependent
range/probe errors and FFmpeg failures are terminal `failed` stream events.
A disconnected controller interrupts processing at the next callback and
cleans unpublished staging files; completed outputs are retained.

Run `.\.venv\Scripts\python.exe -m unittest discover -s tests -v` to include the
HTTP adapter tests along with the CLI and FFmpeg tests.

## FFmpeg configuration

[`src/video_processing_service/config.toml`](src/video_processing_service/config.toml) contains the FFmpeg executable paths, acceleration mode, H.264 encoder,
maximum proxy width and height, target bitrate, rate-control buffer, pixel format,
encoder preset, JPEG quality, and progress update interval. The default uses CPU
decoding and scaling followed by NVENC H.264 at 2000 kbps CBR, within 854 × 480
while preserving source aspect ratio. To use a CPU encoder,
set `video_codec = "libx264"` and select a libx264 preset such as `medium`.
Pass a small override file with `--config PATH` to change only the desired
values per run. For example, a CPU override can contain:

```toml
[proxy]
acceleration = "cpu"
video_codec = "libx264"
encoder_preset = "medium"
bitrate_kbps = 2500
```

Configuration is read at invocation time; no code edit is needed.
`[progress] interval_seconds` controls FFmpeg status updates (minimum: 0.1 second);
the current packaged value is in `config.toml`.

For CUDA decoding and scaling, put this in a separate override file such as
`cuda.toml`:

```toml
[proxy]
acceleration = "cuda"
```

Run `ycg-video --config cuda.toml proxy input.mp4 output/proxy.mp4`, or use
`py -m video_processing_service --config cuda.toml proxy input.mp4 output\proxy.mp4`
after setting `PYTHONPATH=src` in CMD. CUDA mode requires
`video_codec = "h264_nvenc"` and `pixel_format = "yuv420p"`; it keeps frames on
the GPU through decoding, `scale_cuda`, and encoding. If the source codec or
installed FFmpeg/driver cannot use this path, FFmpeg reports the error and the
service leaves no published proxy. It does not silently switch modes. Set
`acceleration = "cpu"` to restore CPU decoding and scaling; this setting still
uses NVENC for encoding unless `video_codec` is changed separately.

CUDA may be faster for some sources, but it can also be slower. Compare CPU and
CUDA using the same representative source range, resolution, codec settings,
and machine load. The live FPS and speed display helps with that comparison.

## Usage

```text
ycg-video proxy input.mp4 output/proxy.mp4
ycg-video sample output/proxy.mp4 output/coarse --interval-seconds 30
ycg-video sample output/proxy.mp4 output/fine --interval-seconds 1 --start-time 01:10:30 --end-time 01:12:00
```

These commands require the editable installation above. Without installing,
run this from the project directory in Windows CMD:

```cmd
set PYTHONPATH=src
py -m video_processing_service proxy input.mp4 output\proxy.mp4
py -m video_processing_service sample output\proxy.mp4 output\coarse --interval-seconds 30
```

During processing the CLI shows FFmpeg's current frame count, FPS, speed,
and source time. Proxy creation also shows percent complete. On an interactive
terminal the status updates in place; redirected output gets one line per
update. Before each operation, the CLI prints the complete FFmpeg command,
including the progress flags and temporary output path used by that run. The
temporary path is removed after processing. FFmpeg diagnostics and failures
remain visible. Sparse frame sampling reports the last requested sample timestamp
and extracted frame count rather
than a percentage, since its FFmpeg output time advances in jumps.

`sample` accepts seconds or `HH:MM:SS[.mmm]`, with millisecond precision.
The sample grid is anchored at `start-time` (default 0). `end-time` is inclusive;
the video duration is exclusive. Frames are named
`frame_000042_00-21-00.000.jpg`, encoding the zero-based sample index and
requested source timestamp. FFmpeg selects the decoded frame at or immediately
before each target, padding with the first available frame at the start of a
seeked range if necessary. The actual image time can differ by a source frame
interval (or more across gaps in a variable-frame-rate source). Sampling
from a proxy preserves its timeline relative to the original only when the proxy
has the same starting timestamp and unedited duration, as this service produces.

Both operations reject an existing output path. A failed FFmpeg process leaves
no published proxy or frame directory; rerun the command after fixing the error.
Separate output directories can hold coarse and fine passes for reuse. The
service reads video incrementally and runs one FFmpeg process per operation.
Frame sampling stops decoding after the requested number of JPEGs has been
written; it does not apply an output duration limit that could cut off the final
sample near the end of a video.

## Python API

```python
from video_processing_service import (
    SamplingConfig, create_proxy, load_config, sample_frames,
)

config = load_config()
def show_progress(event):
    print(event.operation, event.state, event.source_seconds, event.fps, event.speed)

proxy = create_proxy("input.mp4", "output/proxy.mp4", config,
                     on_progress=show_progress)
result = sample_frames(proxy, "output/coarse", SamplingConfig(30), config,
                       on_progress=show_progress)
print(result.frame_count, result.directory)
```

The optional callback receives immutable `ProcessingProgress` events with
`started`, `running`, `completed`, or `failed` state. Available values include
source time, source duration, frame count, FPS, and speed. Proxy events include
percent complete; sampling events include the requested total frame count.
The `started` event carries the exact FFmpeg argument tuple in `command`.
Values FFmpeg has not reported yet are `None`. A `completed` event is sent only
after the output is validated and published. The HTTP adapter forwards these
events to the controller, which streams them to the frontend.

## Tests

From this directory: set `PYTHONPATH=src`, then run
`py -m unittest discover -s tests -v` on Windows or the equivalent `python`
command elsewhere.
