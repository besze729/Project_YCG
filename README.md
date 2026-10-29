# Project YCG

**YouTube Chapterization For Games** is a local AI-assisted system for turning
long gameplay recordings into reviewable YouTube chapter suggestions. Its primary
evidence is what happens visually in the game; speech transcription is not the
source of truth for gameplay events.

The current implementation is the **Milestone 1 Mortal Shell boss fight detection
prototype**. It provides an end-to-end desktop workflow: build a game knowledge
base, select local videos, detect and refine active boss fight intervals, filter
short candidates, and export a copyable chapter list. Boss names and location
recognition remain future work.

The project is designed for multi-hour recordings, including 6–10-hour videos and
playthroughs spread across multiple files. Processing uses proxies and selective
frame sampling; original videos are read in place, without uploading or copying
the source files.

## Implemented capabilities

| Area | Current behavior |
| --- | --- |
| Desktop workspace | English React/TypeScript UI in Electron, with native file selection, multi-video drag and drop, and an Activity log. |
| Game knowledge | Local Qwen3 14B GGUF, quantized as **Q5_K_M**, generates structured boss knowledge using web research through Parallel Search MCP. Validated JSON is saved locally; rebuilding existing knowledge requires confirmation. |
| Video preprocessing | FFmpeg creates an H.264 proxy up to 1280 × 720, preserving aspect ratio. The default uses CUDA decoding/scaling and NVENC encoding at 2500 kbps. CPU processing is configurable. |
| Coarse visual detection | Samples the proxy every 30 seconds by default. Local Qwen3-VL evaluates groups of up to five images for a boss health bar in the top region. |
| Boundary refinement | Refines observed state changes at 5-second, then 1-second spacing, reusing existing predictions and retaining additional transitions. |
| Minimum fight duration | Rejects candidates shorter than a configurable minimum, default 10 seconds. Skips further refinement only when the maximum possible duration is already below the minimum. |
| Chapter export | Produces a full-timeline `youtube-chapters.txt` with `BOSS_FIGHT` and `Exploration` labels. **Show result** opens a separate window with selectable text and a video selector. |
| Evidence and diagnostics | Persists source timestamps, predictions, boundary brackets, model/prompt metadata, rejection reasons and replayable progress events. Rejections appear at warning level. |
| Process lifecycle | One launcher starts and stops the Python service, Java controller and desktop app, with per-module logs and readiness checks. |

Knowledge-base creation is a prerequisite for **Build Chapters**, but its content
is **not yet passed into visual detection**. The current detector receives the
game title and images. `Exploration` is the complement of accepted boss intervals,
not an independently recognized location or gameplay state.

## Processing workflow

1. Enter the game title and use **Build Knowledge Base**. An existing saved file
   is reused unless a rebuild is explicitly confirmed. A confirmed rebuild
   invalidates the old file before generation; a failed rebuild requires another
   successful build before video processing can start.
2. Switch to **Build Chapters**, select one or more local videos, then click
   **Process**. Each video completes this pipeline before the next starts:

   ```text
   Original video
     -> 720p analysis proxy
     -> coarse frames (30s)
     -> visual boss health-bar predictions
     -> boundary refinement (5s -> 1s), with minimum-duration checks
     -> accepted fight intervals
     -> YouTube-style chapter text
   ```

3. Review the Activity log and use **Show result** after the whole batch succeeds.
   Copy the desired video's chapters into your workflow after reviewing them.

Example chapter text:

```text
00:00 Exploration
00:12 BOSS_FIGHT
01:19 Exploration
04:46 BOSS_FIGHT
05:46 Exploration
```

Internally, intervals use source-relative milliseconds and `[startMs, endMs)`
boundaries. The export uses whole-second timestamps, starts at zero, and emits
only label changes. Output-format notes appear outside the copyable text; the
formatter does not pad short Exploration sections or invent chapters.

The minimum-duration rule is a deterministic heuristic. Exactly-at-threshold
fights are retained; clipped fights use their duration within the video. Rejected
spans become part of Exploration. Raw model predictions are preserved, with each
rejection recorded separately rather than silently rewritten as a negative.

## Components

| Component | Responsibility | Default address |
| --- | --- | --- |
| `controller-application/frontend/` | React, TypeScript, Vite and Electron; native paths, progress and result display | Desktop app |
| `controller-application/backend/` | Java 25 / Spring Boot; orchestration, knowledge generation, job persistence, SSE events and chapter formatting | `http://127.0.0.1:8080` |
| `video-processing-service/` | Python / FastAPI; FFmpeg operations, vision adapter, refinement and filtering | `http://127.0.0.1:8090` |
| LM Studio | Local Qwen3 14B GGUF (Q5_K_M) knowledge generation and Qwen3-VL image inference | `http://127.0.0.1:1234` |

The desktop calls the controller over REST and receives progress over SSE. The
controller calls Python over REST with streamed NDJSON progress. Python owns
video/frame processing and temporal analysis; Java owns workflow and chapter
formatting. Both services bind to loopback by default.

Visual inference runs locally with the default configuration. Knowledge research
uses the configured external search provider for game queries; gameplay files
are not sent to that provider.

## Setup and launch on Windows

Prerequisites:

- JDK 25 and Maven, with `JAVA_HOME` configured and `mvn` on `PATH`.
- Python 3.11 or newer, and Node.js 22.12 or newer with npm.
- FFmpeg and FFprobe on `PATH`, or their paths configured in the video service.
- For the default proxy settings, a compatible NVIDIA GPU/driver and an FFmpeg
  build supporting CUDA scaling and NVENC. Select CPU settings otherwise.
- A running LM Studio server with **Qwen3 14B GGUF (Q5_K_M)** for knowledge
  generation and Qwen3-VL 8B Instruct for vision. Their default API identifiers
  are `qwen3-14b` and `qwen3-vl-8b-instruct`, respectively. The knowledge model's
  `qwen3-14b` identifier does not encode or enforce quantization: select the
  **Q5_K_M** model in LM Studio and match `LM_STUDIO_MODEL` to its served identifier.
- Parallel Search MCP configured in LM Studio for knowledge research, and an
  LM Studio token with the required access. See the
  [LM Studio setup guide](controller-application/README.md#lm-studio-configuration).

Install project dependencies from this directory:

```powershell
py -m venv .\video-processing-service\.venv
.\video-processing-service\.venv\Scripts\python.exe -m pip install -e ".\video-processing-service[api,test]"
npm.cmd --prefix .\controller-application\frontend ci
```

The supplied master/backend launchers require the token saved for the current
Windows user at `%LOCALAPPDATA%\ProjectYCG\lmstudio-token.dpapi`. If it has not
already been saved, use a secure prompt:

```powershell
$tokenDirectory = Join-Path $env:LOCALAPPDATA 'ProjectYCG'
New-Item -ItemType Directory -Path $tokenDirectory -Force | Out-Null
Read-Host 'LM Studio API token' -AsSecureString |
    ConvertFrom-SecureString |
    Set-Content -LiteralPath (Join-Path $tokenDirectory 'lmstudio-token.dpapi')
```

The launchers decrypt this value for the same Windows user and pass it through
`LM_STUDIO_API_TOKEN`. LM Studio itself must be started separately.

Start the three application modules together:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-all.ps1
```

The launcher starts the backend with `mvn spring-boot:run`. Maven checks whether
sources need compiling; the desktop launcher builds the frontend before opening
Electron. Pass `-U` to additionally package the backend before startup
(`-DskipTests`; run validation separately):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-all.ps1 -U
```

Press **Ctrl+C in the launcher terminal** to stop all three modules. Closing
the desktop window also stops the two services. The master launcher expects ports
8080 and 8090 to be free. Startup/shutdown timings and module output are saved
under `controller-application/.cache/master-launcher-*/`.

For separate module startup, browser-only frontend development, or the existing
local Node fallback under `controller-application/.tools/`, see the
[controller guide](controller-application/README.md#run-the-desktop-processing-workflow).
Video selection for processing requires Electron's native file paths; a regular
browser cannot supply them. An all-in-one installer is not provided yet.

## Configuration

| Setting | Location / override | Default |
| --- | --- | --- |
| Coarse frame interval | `ycg.video.sampling-interval-seconds` in [controller properties](controller-application/backend/src/main/resources/application.properties) | 30 seconds |
| Proxy resolution, codec, hardware, bitrate, JPEG quality | [video TOML](video-processing-service/src/video_processing_service/config.toml), optional `YCG_VIDEO_CONFIG` | 720p, CUDA/NVENC H.264, 2500 kbps |
| Vision model, endpoint, batch size, timeouts | [vision TOML](video-processing-service/src/video_processing_service/vision/config.toml), optional `YCG_VISION_CONFIG` | `qwen3-vl-8b-instruct`, up to 5 images/request |
| Minimum accepted fight duration | `vision.minimum_boss_fight_seconds` in the same vision TOML | 10 seconds; `0` disables filtering |
| Visual detection instructions | [external prompt template](video-processing-service/src/video_processing_service/vision/boss_fight_prompt.tpl) | Top-region boss health-bar detection |
| Knowledge model, endpoint and search integration | Controller `LM_STUDIO_*` environment variables | Qwen3 14B GGUF **Q5_K_M**; API identifier `qwen3-14b`; integration `mcp/parallel-search` |
| Job and knowledge output roots | `YCG_PROXY_OUTPUT_ROOT`, `YCG_KNOWLEDGE_BASE_OUTPUT_ROOT` | Backend `data/` subdirectories |

Python loads configuration per request; vision settings and the prompt are
captured for each detection/refinement request. Controller configuration changes
require a restart. Sampling and minimum-duration controls are currently file
settings, not UI fields. See the [video service guide](video-processing-service/README.md)
for CPU overrides and detailed configuration semantics.

## Saved results and debugging

With the supplied launchers, output is stored under
`controller-application/backend/data/`:

```text
data/
  knowledge-base/<Game Title>.json
  proxy-jobs/<jobId>/
    job.json
    events.jsonl
    0001-proxy.mp4
    0001-frames/
    0001-boss-detections.jsonl
    0001-boss-refinement/
      manifest.json
      5000ms/<window>/frames/ and detections.jsonl
      1000ms/<window>/frames/ and detections.jsonl
      rejected-intervals.jsonl
      boss-intervals.json
      youtube-chapters.txt
```

The numeric prefix identifies each video in the batch. Refinement directories
exist only for windows actually processed. Predictions map back to source
timestamps; the manifest records analysis configuration and model/prompt metadata.
`rejected-intervals.jsonl` retains the threshold, reason, and either exact duration
or an upper bound with its enclosing window when refinement was skipped.

A failed stage stops the batch and preserves completed and partial evidence.
Final artifacts are published only after the corresponding stage succeeds.
Progress can be replayed after reconnecting or reloading the current window.
**Event replay is not processing resume:** interrupted jobs do not automatically
continue, and reruns use new output paths. Previously completed results are not
retroactively filtered when the minimum duration changes.

Generated media, local data, caches and credentials should stay outside version
control. Small synthetic and prediction-only fixtures support repeatable tests.

## Validation

From the project root, run backend tests and build the JAR used by desktop tests:

```powershell
mvn -f .\controller-application\backend\pom.xml verify
```

Run Python tests from the service directory:

```powershell
Push-Location .\video-processing-service
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
Pop-Location
```

Build the frontend or run the desktop integration check:

```powershell
npm.cmd --prefix .\controller-application\frontend run build
npm.cmd --prefix .\controller-application\frontend run test:integration
```

Tests cover timestamp mapping, FFmpeg proxy/sampling behavior, configuration,
model-response validation, refinement, minimum-duration boundaries and safe early
rejection, persisted warnings, chapter formatting and failure handling. A saved
prediction-only regression fixture checks that the two isolated false positives
are removed while the five other fight intervals retain their boundaries.

The desktop integration check uses short synthetic videos, isolated services on
ports 18080/18090, a hidden Electron window and local model stand-ins. It checks
the workflow, result window and warning replay without requiring real AI output.
For real HTTP vision/refinement smoke checks against a local Postman simulation,
see [simulation setup and findings](video-processing-service/postman/simulations/boss-detection.context.md).
Mocked tests establish pipeline behavior, not visual recognition accuracy.

## Current limits and next steps

- The initial scope and testing target Mortal Shell. The game-title input does
  not establish reliable recognition across arbitrary games.
- Visual predictions can be wrong. The duration filter suppresses short noisy
  candidates, but can also reject genuine short fights; it does not improve the
  classifier itself. Game-specific visual references and prompt/model refinement
  remain future work.
- Equal coarse states do not trigger a search for hidden short fights. Existing
  endpoint predictions are reused rather than independently verified. One-second
  boundary brackets describe temporal resolution, not recognition certainty.
- Boss-name OCR, knowledge-base matching, location recognition and richer event
  semantics are not implemented. There is no transcript-analysis stage.
- Automatic retry/resume, cross-video encounter reasoning and an in-app chapter
  editing workflow are not implemented. Results are available for inspection
  and copying; the application does not upload to YouTube.

## Documentation map

The functional specification is the source of truth for intended behavior.
Milestone documents bound the current work; ADRs record technical decisions and
their rationale. This README summarizes the implemented system and links to the
detailed documents rather than replacing them.

| Document | Purpose |
| --- | --- |
| [Functional specification](docs/SPECIFICATION.md) | Product goals, visual-evidence principle, long-video requirements, output semantics and long-term direction. |
| [Milestone 1](docs/milestones/milestone-1-mortal-shell-boss-detection.md) | Mortal Shell prototype scope and the implemented knowledge, detection, refinement, duration-filtering and export increments. |
| [Controller guide](controller-application/README.md) | Desktop/backend setup, LM Studio integration, REST/SSE contracts, job lifecycle and chapter results. |
| [Video service guide](video-processing-service/README.md) | FFmpeg/vision setup, configuration, CLI and HTTP APIs, artifact formats and tests. |
| [Controller architecture ADR](docs/adr/controller-application.md) | Java/Python responsibilities, technology stack and native Electron file paths. |
| [Video processing ADR](docs/adr/video-processing-service.md) | Python-owned FFmpeg processing, proxy generation, sampling and timestamp mapping. |
| [720p proxy ADR](docs/adr/use-720p-proxy-video.md) | Current proxy resolution and bitrate decision; supersedes the [480p ADR](docs/adr/use-480p-proxy-video.md). |
| [Qwen3 knowledge ADR](docs/adr/use-qwen3-for-knowledge-base.md) | Qwen3 14B GGUF with Q5_K_M quantization for knowledge generation. |
| [Parallel Search ADR](docs/adr/use-parallel-search-mcp.md) | Web-search integration for game research. |
| [Qwen3-VL recognition ADR](docs/adr/use-qwen3-vl-for-boss-fight-recognition.md) | Visual model, batch detection, boundary refinement and the accepted minimum-duration amendment; later OCR direction. |
| [Agent instructions](AGENTS.md) | Repository engineering rules, evidence preservation and documentation responsibilities. |
