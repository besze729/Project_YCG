# Controller application

Two independent projects:

- `backend/`: Spring Boot application built with Maven and JDK 25.
- `frontend/`: React and TypeScript application built with Vite and npm, with an
  Electron desktop launcher for native local file paths.

The frontend provides an English workspace with a game name field, an action
selector, and an activity log. Build Knowledge Base calls the Spring Boot backend,
which requests and validates a response from the local LM Studio server.
The projects run separately during development;
serving the frontend build through the backend is not wired up yet.
Technology decisions are maintained in [the ADR directory](../docs/adr/).

## Workspace behavior

- **Build Knowledge Base** shows a Start button, enabled after entering a
  non-blank game name. Start sends a REST request and reports progress, completion,
  or errors in the activity log. The inputs and Start button are disabled while
  the request is pending. A valid AI response is saved as a knowledge-base JSON
  file, and its absolute path is shown in the activity log.
- **Build Chapters** shows a drop area and a keyboard-accessible file picker.
  Multiple videos can be selected, inspected by name and size, and removed.
  Selection accepts video MIME types or common video filename extensions;
  FFprobe validates the actual media. Empty files and duplicate paths are skipped.
  Process is enabled only with a nonblank game name and at least one video. It
  creates proxies, samples JPEG frames and analyzes all samples for boss health
  bars through the controller and Python service. Inputs are locked
  while processing. The list displays each selected file's absolute path.
- The bottom **Activity log** displays timestamped information, success,
  warning, and error messages. It retains the latest 500
  entries, follows new entries unless the user has scrolled up, and can be
  cleared.

The desktop window obtains original absolute paths from native file selections
and drag and drop. Only paths are submitted; videos are never uploaded or copied.
The regular browser remains useful for frontend development and knowledge-base
requests, but cannot select video paths for processing. Reloading the current
window restores its job events. A newly opened desktop window follows an earlier
job only if it is still running; it does not replay completed or failed jobs.
Live progress updates replace the corresponding video's stage progress row, keeping
the FFmpeg command visible without filling the log with per-second entries.

## Run the desktop processing workflow

First install the frontend dependencies with `npm ci` in `frontend/` and the
Python HTTP dependencies following the video service README. From the repository
root, start all three modules with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-all.ps1
```

The master launcher starts the backend with `mvn spring-boot:run`; Maven checks
whether sources need compiling. Pass `-U` to build and package the backend
before starting. Press Ctrl+C in the launcher terminal to stop the desktop,
controller, and Python service; closing
the desktop window also stops both services. Logs are stored in the ignored
`controller-application/.cache/master-launcher-*/` directory. Maven output in
`backend.out.log` includes timestamps. The launcher uses the existing DPAPI LM
Studio token file required by `start-backend.ps1`.

For independent development, run the modules in separate terminals:

```powershell
# Python service on 127.0.0.1:8090
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\video-processing-service\start-server.ps1

# Controller on 127.0.0.1:8080; no LM Studio token is needed for proxy creation
mvn -f .\controller-application\backend\pom.xml spring-boot:run

# Build and open the native desktop window
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\controller-application\start-desktop.ps1
```

The token-aware backend launcher may also be used independently; it runs
`mvn spring-boot:run`. Electron downloads
its runtime on first launch. The desktop launcher builds the UI before opening;
it uses the same local Node fallback as `start-frontend.ps1`. Java, Python, and
FFmpeg remain separately installed local processes; an all-in-one installer is
not provided yet. `YCG_CONTROLLER_URL` can override the desktop's loopback HTTP
controller address (default `http://127.0.0.1:8080`).

## Backend

### Video preprocessing contract

`POST /api/chapters/jobs` accepts JSON:

```json
{"gameName":"Mortal Shell","videos":["F:\\Gameplay\\Mortal Shell\\part01.mp4"]}
```

The controller trims and validates the game name (1–200 characters), requires
an existing knowledge-base JSON file for that title, and requires
at least one absolute path to a readable nonempty file. It rejects duplicate
canonical paths. It passes the original resolved paths to Python. No filename
matching, configured input directory, upload, or source copy is involved.
The desktop and both services run on the same machine with access to these files.

An accepted request returns HTTP 202 with `jobId`, `state`, `gameName`, and the
planned `videos` (including proxy `outputPath`, `framesDirectory`, and
`bossDetectionsPath`), plus the
effective `samplingIntervalSeconds`. `GET /api/chapters/jobs/{jobId}` returns
the current snapshot. `GET /api/chapters/jobs/{jobId}/events` is an SSE stream with
numbered `processing` events and `Last-Event-ID` replay. Events contain `state`,
`message`, `jobId`, and, for video events, `videoIndex`, `videoName`,
`operation` (`proxy`, `sample`, `boss-detection`, or `boss-refinement`), and the
Python progress fields (`fps`, `speed`, `source_seconds`, `duration_seconds`,
`frame_count`, `total_frames`, `percent`, `command`, `commandLine`). Sampling
reports extracted/expected frames and the latest sample timestamp with null
`percent`. Its output path is `outputDirectory`; proxy events use `outputVideo`.
Boss detection reports processed/total frames, percent and the validated batch's
`results`. Its output path is `outputFile`. Detection start/completion events also
include the model, rendered-prompt SHA-256, schema version and maximum batch size.
Video stage states are `started`,
`running`, `completed`, and `failed`; batch states are `job_started`,
`job_completed`, and `job_failed`. A lost event connection does not start another job.

One batch runs at a time, with each video processed as proxy creation, full-video
sampling, boss fight detection, then boundary refinement before starting the next video. A failed stage
stops the batch; completed preprocessing and partial detection results are retained.
The batch completes only after all four processing stages and chapter formatting finish for every video.
Commands and events are saved alongside `0001-proxy.mp4`, `0001-frames/`, and
`0001-boss-detections.jsonl` and `0001-boss-refinement/` in `YCG_PROXY_OUTPUT_ROOT/<jobId>/`. Reloading the
current page reconnects to its job. Controller restarts mark unfinished jobs as
interrupted when accessed; they do not restart encoding automatically.
Older jobs remain readable; missing sampling/detection/refinement fields are null and
no new stages are retroactively started. Frontend replay treats old events without
an `operation` field as proxy events.

The controller calls the Python service with `POST /api/proxy`, sending JSON
`{gameName,inputVideo,outputVideo}` using absolute local paths. After proxy
completion it calls `POST /api/sample` with
`{gameName,inputVideo,outputDirectory,intervalSeconds}`, using the proxy as input.
It then calls `POST /api/boss-detection` with `{gameName,framesDirectory,outputFile}`.
Python sends images to local LM Studio in groups of at most five, using the UI's
game title in the external prompt. Raw per-frame booleans retain source timestamps.
Next, `POST /api/boss-refinement` receives
`{gameName,inputVideo,detectionsFile,outputDirectory}` and refines both interval
boundaries at 5-second then 1-second spacing. Each video's `bossRefinementDirectory`
contains `boss-intervals.json` plus intermediate evidence. See the
[refinement contract](../video-processing-service/README.md#boss-fight-boundary-refinement).
Final intervals are logged in time order to the Java console and persisted SSE
`state: "interval"` messages for Activity log replay. Empty results are explicit.
Python's configurable `vision.minimum_boss_fight_seconds` defaults to 10 seconds;
only accepted intervals reach chapter formatting. Each rejection arrives as a
nonterminal `state: "warning"` event, is logged at WARN with its detailed reason,
and is persisted/replayed as a warning in the Activity log. The event carries the
threshold and either exact duration or a conservative upper bound for early
rejection. Raw predictions and `rejected-intervals.jsonl` remain available for
debugging. Set the Python value to zero to disable filtering; there is no UI field.
Previously completed results are not retroactively filtered.

Accepted video-edge intervals are marked as clipped. Java then formats and saves the plain
YouTube chapter list. Boss-name OCR and richer chapter labels remain deferred.
Python responds
with `application/x-ndjson`, one progress object per line, plus idle heartbeats.
The controller relays these through SSE. The `command` array is the actual
process argument list; `commandLine` is its platform-specific display form.
Stage completion is sent only after its output is published. Unexpected EOF and an
unreachable Python service become failed job events.

### Chapter results

Successful jobs expose `resultsAvailable: true` in the job snapshot and terminal
SSE event. Each video also emits `state: "chapters_ready"` after writing
`youtube-chapters.txt` atomically inside its refinement directory. Results become
visible only after the entire job succeeds. The **Show result** button opens a
separate Electron window; multiple videos have a selector, with one pure chapter
list in the selectable textarea. The button is restored on current-session reload
and cleared when another Process attempt begins. The result window permits no
external navigation or additional popups.

`GET /api/chapters/jobs/{jobId}/results` returns:

```json
{
  "jobId": "...",
  "gameName": "Mortal Shell",
  "videos": [{
    "videoIndex": 0,
    "videoName": "gameplay.mp4",
    "durationMs": 120000,
    "chapters": [
      {"startSeconds": 0, "title": "Exploration"},
      {"startSeconds": 21, "title": "BOSS_FIGHT"},
      {"startSeconds": 90, "title": "Exploration"}
    ],
    "text": "00:00 Exploration\n00:21 BOSS_FIGHT\n01:30 Exploration\n",
    "warnings": []
  }]
}
```

The endpoint reads saved version-1 interval artifacts and formats them without
model calls or writes, including for older completed jobs that have interval
artifacts but no text export. Running/failed jobs return 409; unknown jobs or
missing artifacts return 404; unreadable/invalid evidence returns 500.

Each list begins at zero, uses `MM:SS` below one hour and `H:MM:SS` thereafter,
and contains only state changes. Fight-free videos produce `00:00 Exploration`;
a fight starting at zero produces `00:00 BOSS_FIGHT`. There is no end-of-video
marker and no artificial split inside a continuous Exploration section.
Fractional boundaries are rounded down for whole-second export only; at a
timestamp collision the later transition wins, and adjacent equal labels are
combined. The original millisecond intervals are never modified.

[YouTube requires](https://support.google.com/youtube/answer/9884579?hl=en) at least
three chapters, starting at 00:00, each at least ten seconds long. The user chose
to preserve the detected timeline even when these criteria are unmet. Warnings,
including fractional-timestamp rounding when applicable, appear outside the pure
textarea. No chapter is padded or merged to meet the ten-second minimum.

Configuration: `YCG_VIDEO_SERVICE_URL` defaults to `http://127.0.0.1:8090` and
`YCG_PROXY_OUTPUT_ROOT` to `./data/proxy-jobs` relative to the backend working
directory. Set `ycg.video.sampling-interval-seconds` directly in
`backend/src/main/resources/application.properties` (initial value `30`). It
accepts positive seconds with at most millisecond precision and is validated
at controller startup. Restart the controller after changing the file; rebuild
the JAR when running a packaged backend. The interval is captured in each new
job's metadata. `YCG_VIDEO_CONNECT_TIMEOUT` defaults to `3s` and
`YCG_VIDEO_IDLE_TIMEOUT` to `60s` (stream inactivity, not total encoding time).
Both services bind to loopback. The Python server reads `YCG_VIDEO_CONFIG` as an
optional TOML override file on each processing request. JPEG quality stays in
the Python configuration. The UI has no sampling interval control.
Vision settings use the separate `YCG_VISION_CONFIG` override and editable prompt;
see the [video service guide](../video-processing-service/README.md#boss-fight-detection).
The Python process needs access to LM Studio and its bearer token; the provided
Windows launcher loads the same saved token used by the backend.

Validation errors return HTTP 400, unreadable or missing sources 404,
a busy controller or missing knowledge base 409, and local storage failures 500. Errors contain
`code` and `message`. Once a job is accepted, processing errors are reported in
its event stream and snapshot.
The missing-knowledge-base response uses `code: "missing_knowledge_base"` and
asks the user to run Build Knowledge Base first. This check happens before any
job, proxy, or frame sampling is started; knowledge content is not yet consumed
by video processing.

After `mvn package` in `backend/`, run `npm run test:integration` in `frontend/`
to launch isolated Java/Python services on ports 18080/18090 and a hidden Electron
window. This creates two short synthetic videos, selects their real native paths,
verifies that Process is blocked without knowledge, builds JSON through a local
LM Studio HTTP stand-in, checks the logged absolute path, cancellation and confirmed
overwrite, then clicks Process, verifies event replay after reload, checks the H.264/720p proxies
and their duration, verifies timestamped JPEGs, vision requests and JSONL results,
checks proxy/sample/detection/refinement stage ordering and interval logging,
and confirms that the source files are unchanged. Generated
fixtures and logs stay under the ignored `controller-application/.cache/` folder.

Install JDK 25 and Maven, set `JAVA_HOME` to the JDK directory, and make `mvn`
available on `PATH`.

From `controller-application/backend` in PowerShell:

```powershell
mvn spring-boot:run
```

If the LM Studio token has been saved with `ConvertFrom-SecureString` at
`%LOCALAPPDATA%\ProjectYCG\lmstudio-token.dpapi`, start the backend from the
repository root with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\controller-application\start-backend.ps1
```

The execution policy override applies only to this PowerShell process. The
script decrypts the token for the current Windows user, passes it to the backend
through `LM_STUDIO_API_TOKEN`, and removes that environment variable when Maven
exits.

The server listens at `http://127.0.0.1:8080`.

Build and run the backend tests (including a local HTTP stub for LM Studio):

```powershell
mvn verify
```

### Build Knowledge Base API

```http
POST /api/knowledge-base/build
Content-Type: application/json

{"gameName":"Mortal Shell"}
```

`gameName` must be a non-blank string, at most 200 characters after trimming.
The backend stores the validated answer as UTF-8 JSON at
`controller-application/backend/data/knowledge-base/<Game Title>.json` when
launched from the backend directory or with the supplied Maven launchers.
`YCG_KNOWLEDGE_BASE_OUTPUT_ROOT` overrides the default `./data/knowledge-base`
(relative to the backend process working directory).
Normal titles retain their spaces and Unicode characters. Characters forbidden
in Windows filenames, control characters and `%` are escaped as `%XXXX`;
reserved Windows device names have their first character escaped. This keeps
titles such as `Game: Edition` safe and distinct from literal escaped titles.
Names exceeding 240 characters or UTF-8 bytes after escaping are rejected with
HTTP 400; use a shorter title. Lookup and processing use the same filename rule
and the filesystem's case sensitivity.

If the file exists, the request returns `already_exists` with its absolute
`outputPath`, without invoking AI or modifying the file. The desktop displays
a confirmation dialog. Cancel leaves the file unchanged; Rebuild sends the
same request with `"overwrite":true`. A confirmed rebuild removes the old file
immediately, then acquires and validates fresh knowledge. Failure leaves no valid
knowledge base for that title, so Process remains blocked until a build succeeds.
Only complete validated JSON is published, via a temporary file and an atomic
move in the same directory. Storage failures are reported, never as success.
One knowledge-base build is allowed at a time; concurrent builds return HTTP 409.

The backend reads `src/main/resources/templates/knowledge_base_aquire_prompt.tpl`
as UTF-8, replaces `<?GAME_TITLE>` with the game name and `<?SOURCE_SITE>` with
`fextralife.com`, and sends a fresh streaming request to LM Studio's native
`POST /api/v1/chat`. Chat storage is disabled (`store: false`).

The controller reads the `chat.end` event's aggregated `result`. The last
`message` item in that result's `output` array is validated;
reasoning and tool outputs are not treated as the answer. The answer must be a
single JSON object with `bosses` and `sources` arrays. Each boss must have a
non-blank string `displayName` and a present `classification` field.
**Classification accepts any JSON value, including null.** Each source must be
an absolute HTTP(S) URL. Empty arrays are allowed. Markdown fences, malformed
JSON, trailing content, duplicate JSON keys, and missing required fields are
rejected. This checks structure, not the factual accuracy of the model's claims.

| HTTP status | Result |
| --- | --- |
| 200 | `{"status":"completed","message":"Knowledge base saved successfully.","outputPath":"<absolute JSON path>"}` |
| 200 | `{"status":"already_exists","message":"Knowledge base already exists","outputPath":"<absolute JSON path>"}` |
| 400 | Missing, malformed, or invalid game name/request |
| 409 | Another knowledge-base build is running (`busy`) |
| 415 | Unsupported request content type |
| 502 | LM Studio returned an HTTP error, malformed chat envelope, or invalid answer |
| 503 | LM Studio is unreachable or communication was interrupted |
| 504 | LM Studio request timed out |
| 500 | File access/save failure (`storage_error`), prompt template or unexpected application error |

Errors use `{"code":"...","message":"..."}` with an English message. Validated
JSON is saved locally and is not printed to the backend console. The activity
log reports success with the exact absolute JSON path, or the failure message.
There are no automatic retries or subsequent AI processing.
A timeout or closed browser does not guarantee that LM Studio stopped processing.

### LM Studio configuration

Defaults target the local Qwen3 model and the configured Parallel Search MCP
server. Environment variables can override connection settings:

| Environment variable | Default |
| --- | --- |
| `LM_STUDIO_BASE_URL` | `http://127.0.0.1:1234` |
| `LM_STUDIO_MODEL` | `qwen3-14b` |
| `LM_STUDIO_CONNECT_TIMEOUT` | `3s` |
| `LM_STUDIO_REQUEST_TIMEOUT` | `10m` |
| `LM_STUDIO_API_TOKEN` | Empty; no Authorization header |
| `LM_STUDIO_INTEGRATION_ID` | `mcp/parallel-search` |

The integration ID must match `mcp/<server label>` in LM Studio's `mcp.json`.
The chat request sets `reasoning` to `off` and `stream` to `true`. The controller
reads the final `chat.end` SSE event and validates its `result` message as JSON.
Enable **Allow calling servers from mcp.json** in LM Studio for this integration.
LM Studio requires **Require Authentication** when that setting is enabled.
Search credentials stay in LM Studio's configuration. If authentication is
enabled on its HTTP server, create a token in **Server Settings → Manage Tokens**
with permission to use the configured model and MCP server, then supply it as
`LM_STUDIO_API_TOKEN` in the backend process environment. The backend sends it in
the `Authorization: Bearer` header and does not log it.
An empty integration ID omits tool access, which means the prompt alone cannot
give the model live web search. Search sends the game research query to the
configured provider; no gameplay files are sent.

The controller does not download or explicitly load models; select an available
model in LM Studio. Protocol details:
[native chat API](https://lmstudio.ai/docs/developer/rest/chat) and
[MCP via API](https://lmstudio.ai/docs/developer/core/mcp).

## Frontend

Use Node.js 22.12+ with npm, consistent with Electron and `package.json`.
From `controller-application/frontend`:

```powershell
npm ci
npm run dev
```

After installing dependencies, the frontend can also be started from the
repository root with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\controller-application\start-frontend.ps1
```

The launcher uses `npm.cmd` from PATH when available. Otherwise it selects the
newest local Node.js version under `.tools/node-v*-win-x64`, adding that directory
to PATH only while the frontend runs. The existing local distribution can be
used without a system-wide Node.js installation. Dependencies must already be
installed; with the local distribution, run its `npm.cmd ci` from `frontend/`
with its directory added to the current shell's PATH first.

Open `http://127.0.0.1:5173`.

Run the backend alongside the frontend. Vite proxies `/api` requests to
`http://127.0.0.1:8080` in both development and preview mode. The backend owns the
inference timeout. When serving `dist/` elsewhere, configure the same `/api`
route to the backend.

Type-check and create the production build:

```powershell
npm run build
```

The output is written to `frontend/dist/`. `npm run preview` serves that build at
`http://127.0.0.1:4173`.
