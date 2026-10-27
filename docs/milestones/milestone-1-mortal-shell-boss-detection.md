# Project YCG — Milestone 1 — Mortal Shell Boss Fight Detection Prototype

## Milestone goal

Detect active boss fights from Mortal Shell gameplay video.

## Pipeline

User input: Game name + task type
Task type:
→ Build knowledge base for the game (current version limited, only boss info, testing only for Mortal Shell)
→ Active boss fights detection: additional parameter: gameplay video

## Controller workspace

The initial English frontend exposes a game name and two actions:
**Build Knowledge Base** and **Build Chapters**. Build Knowledge Base provides a
Start button; Build Chapters provides multiple-video drag and drop and file
selection. An activity log is available below either action.
Build Chapters shows a Process button below the video picker. It is enabled
only while a nonblank game name and at least one video are selected. The desktop
window supplies absolute native file paths; the controller validates both inputs
and checks that a knowledge-base JSON exists for the selected game before
requesting proxy creation, frame sampling, boss fight detection and boundary refinement from the Python service.
A missing knowledge base is reported in the activity log and no job is started.
Knowledge content is not yet passed into the video-processing pipeline.
The controller's `backend/src/main/resources/application.properties` sets
`ycg.video.sampling-interval-seconds` to 30 seconds initially;
the UI does not expose an interval field. Videos are read in place,
without uploads or source copies. A batch runs sequentially and stops on failure.
Completed proxies and frame directories remain available. Each video finishes
all four stages before the next begins. The activity log shows the exact FFmpeg
commands, stage, live position, proxy percentage, sampled/expected frame counts,
FPS, speed, and completion or failure. Events
are persisted by the controller and replayed on frontend reconnection.

Build Knowledge Base is connected to a synchronous backend request. Start
reports a pending request and then success with the exact saved JSON path, or
failure, in the activity log. Existing knowledge requires explicit confirmation
before rebuilding; cancelling leaves it unchanged.
Build Chapters currently ends after boss fight interval refinement. It produces
raw predictions and derived fight intervals, not chapter candidates. Its label does not
expand the milestone beyond active boss fight detection.

## Build knowledge base for the game

Research:
Qwen3 14B Q5_K_M
LM Studio
Parallel Search MCP

### Persistent knowledge-base increment

`POST /api/knowledge-base/build` accepts a game name and optional boolean
`overwrite` (default false). Existing knowledge returns `already_exists` with
its absolute file path without invoking AI. After user confirmation, the frontend
repeats the request with `overwrite: true`. The previous file is invalidated
immediately; failed rebuilding leaves Process blocked until knowledge is rebuilt.

Otherwise, the controller renders the version-controlled prompt with the game
name and the fixed source `fextralife.com`, calls the native LM Studio chat API,
and validates the final message as JSON containing `bosses` and `sources`.
`classification` is required but accepts any JSON value, including null.
Only a validated answer is saved as UTF-8 JSON under
`controller-application/backend/data/knowledge-base/<Game Title>.json` before
HTTP 200 is returned with its absolute path. Unsafe filename characters are
escaped consistently for saving and lookup. Complete output is published using
an atomic move; storage failures produce explicit errors. Concurrent knowledge
builds are rejected. The JSON content is no longer printed to the backend console.
Using the saved content for boss detection remains deferred.

Connection failures, timeouts, upstream HTTP errors, and invalid model responses
produce explicit error responses. Configuration and the REST contract are
documented in the [controller README](../../controller-application/README.md).

## Active boss fights detection

Original video
→ 720p proxy
→ sample frame every 30 sec
→ boss UI detection
→ candidate segment refinement

### Batch visual detection increment

The existing Python service hosts a separate vision module and calls local
LM Studio with `qwen3-vl-8b-instruct`. Every sampled proxy frame is sent once,
in chronological groups of at most five images (externally configurable).
Each group is a separate context with one structured response. The external
prompt template substitutes `<?GAME_TITLE>` with the UI's game title and asks
for `activeBossFight: true` only when a boss health bar is visible in the top
region. Every image is evaluated independently; knowledge-base content is not
included. The existing knowledge-base prerequisite for Process remains in place.

The Python service validates result counts, indices, order and boolean types.
Each successful group is persisted as JSONL containing image index, frame filename,
source timestamp in milliseconds and the prediction. A final result file appears
only on full success. Errors stop the job and preserve partial results; there is
no automatic retry or resume. Java owns workflow and result paths and relays
progress to the existing activity log. The prompt hash and model are recorded in
the job's event journal. See the video service README for configuration and API details.

### Boundary refinement increment

Python reads the completed coarse JSONL and refines every observed state change
at 5-second, then 1-second spacing, using the same configurable vision adapter and
external prompt. Known endpoints are reused; only new interior images are sent.
Every additional transition is retained, without smoothing or short-gap merging.
Intervals start at the first positive image and end exclusively at the next
negative image. Their transition brackets are at most one second wide.

Positive video edges are marked as clipped. A positive last coarse sample causes
the remaining tail to be examined; EOF never becomes a fabricated negative.
Equal coarse states do not trigger a search for hidden short fights.

Per-window evidence remains on disk; `boss-intervals.json` is published only after
successful refinement. Java logs final intervals to its console and the replayable
Activity log. Failures stop processing while preserving intermediate results;
automatic resume remains deferred. Boss-name OCR and knowledge matching remain
subsequent increments.
