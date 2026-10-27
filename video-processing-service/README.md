# Video Processing Service

Standalone Python 3.11+ video processing component for Project YCG. It uses local
FFmpeg/FFprobe to create a 720p H.264 proxy and extract JPEG frames for coarse
or focused visual analysis. A separate vision module sends sampled screenshots
to local LM Studio for per-image boss health bar detection.

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
proxy. Only one operation (proxy, sampling, or boss detection) runs at once;
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

## Boss fight detection

After sampling, the controller calls `POST /api/boss-detection`:

```json
{
  "gameName": "Mortal Shell",
  "framesDirectory": "F:\\YCG\\job\\0001-frames",
  "outputFile": "F:\\YCG\\job\\0001-boss-detections.jsonl"
}
```

Paths must be absolute; the input directory must contain only sampled JPEGs
named `frame_<index>_HH-MM-SS.mmm.jpg`. Images are ordered by their numeric index;
indices and source timestamps must be unique and increasing. The timestamp is
read from the filename, including fractional seconds and nonzero sampling starts.

The vision configuration is separate from FFmpeg settings:

- [`vision/config.toml`](src/video_processing_service/vision/config.toml) selects
  the LM Studio base URL, model, batch limit, timeouts, output-token limit and prompt file.
- [`vision/boss_fight_prompt.tpl`](src/video_processing_service/vision/boss_fight_prompt.tpl)
  is the editable prompt. `<?GAME_TITLE>` is replaced once with `gameName`.
- `YCG_VISION_CONFIG` selects a partial TOML override containing `[vision]`.
  A relative `prompt_file` is resolved beside the file that specifies it.
  Settings and prompt are captured once per detection request; edits apply to
  subsequent requests without rebuilding or restarting the service.
- `LM_STUDIO_API_TOKEN` supplies optional bearer authentication. On Windows,
  `start-server.ps1` also reads the existing token from
  `%LOCALAPPDATA%/ProjectYCG/lmstudio-token.dpapi` when the environment value is absent.
  Use the same Windows user that saved the token. Tokens are never stored in TOML.

Defaults are `http://127.0.0.1:1234`, `qwen3-vl-8b-instruct`, at most **5 images**
per request, a 3-second connect timeout, a 600-second request socket timeout,
and 1024 output tokens. For example:

```toml
[vision]
max_images_per_request = 5
prompt_file = "my_boss_fight_prompt.tpl"
```

The client sends full proxy JPEGs to `/v1/chat/completions`, one request at a
time, with explicit image-index labels, temperature 0 and a JSON response schema.
Each request has its own context and no tools. The last batch can be smaller;
12 images produce requests of 5, 5 and 2 images. Changing batch size can require
adjusting LM Studio's loaded context and the output-token limit. No automatic
batch reduction, retries, model unloading, cropping or name recognition occurs.

Responses must contain exactly one `{imageIndex, activeBossFight}` per input,
in the same order, with a JSON boolean. Malformed, truncated or misaligned output
fails the stage; it is never converted to `false`. The boolean is a raw model
prediction using the prompt's top-of-image health-bar rule, not a verified fight
interval. Interval aggregation and refinement are deferred.

The NDJSON stream uses `operation: "boss-detection"` and the existing
`started`/`running`/`completed`/`failed` states. It sends heartbeats while waiting
for LM Studio. Progress includes processed/total frames, percent and the current
batch's `results`. Start/completion events record `schemaVersion: 1`, `model`,
`promptSha256` (SHA-256 of the rendered prompt) and `maxImagesPerRequest`.
Every event identifies `outputFile` and `partialOutputFile`.

Each validated batch is flushed to `0001-boss-detections.partial.jsonl`. Each line is:

```json
{"imageIndex":0,"frameFile":"frame_000000_00-00-00.000.jpg","sourceTimestampMs":0,"activeBossFight":false}
```

The final `.jsonl` is published only after every frame succeeds. On failure,
the partial file is retained and the job fails; no later batch/video starts.
Existing final or partial output returns HTTP 409 to prevent accidental overwrite.
Partial results are inspectable but automatic resume is not implemented. Start a
new analysis with a new output path when retrying. A disconnected controller stops
further batches after the current request returns or times out; it does not cancel
an inference already executing in LM Studio. Disconnecting the frontend does not
cancel the controller job.

Invalid request shapes return 422, relative paths or an invalid output extension
400, a missing frame directory 404, and a busy worker or existing output 409.
Configuration errors return 500. Frame-content, LM Studio and storage errors after
acceptance are terminal `failed` events, with the affected batch identified for
model failures. The generated OpenAPI contract is available at `/openapi.json`.

## FFmpeg configuration

[`src/video_processing_service/config.toml`](src/video_processing_service/config.toml) contains the FFmpeg executable paths, acceleration mode, H.264 encoder,
maximum proxy width and height, target bitrate, rate-control buffer, pixel format,
encoder preset, JPEG quality, and progress update interval. The default uses
CUDA decoding and scaling followed by NVENC H.264 at 2500 kbps CBR, with a
5000 kbit rate-control buffer and a maximum resolution of 1280 × 720 while
preserving source aspect ratio. To use a CPU encoder,
set `acceleration = "cpu"`, `video_codec = "libx264"`, and select a libx264
preset such as `medium`.
Pass a small override file with `--config PATH` to change only the desired
values per run. For example, a CPU override can contain:

```toml
[proxy]
acceleration = "cpu"
video_codec = "libx264"
encoder_preset = "medium"
bitrate_kbps = 3000
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

An opt-in real five-image test is available with
`.\.venv\Scripts\python.exe tests/vision_smoke.py`. It creates small synthetic
720p frames and calls the detection API with the real LM Studio client; provide
`LM_STUDIO_API_TOKEN` if required. It verifies transport, ordering and JSON shape,
not gameplay accuracy. Artifacts stay under ignored `output/`.

For the same test against the local Postman simulation, see
[`postman/simulations/boss-detection.context.md`](postman/simulations/boss-detection.context.md).
