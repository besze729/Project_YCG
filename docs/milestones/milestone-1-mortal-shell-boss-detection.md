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
and requests proxy creation followed by frame sampling from the Python service.
The controller's `backend/src/main/resources/application.properties` sets
`ycg.video.sampling-interval-seconds` to 30 seconds initially;
the UI does not expose an interval field. Videos are read in place,
without uploads or source copies. A batch runs sequentially and stops on failure.
Completed proxies and frame directories remain available. Each video finishes
both stages before the next begins. The activity log shows the exact FFmpeg
commands, stage, live position, proxy percentage, sampled/expected frame counts,
FPS, speed, and completion or failure. Events
are persisted by the controller and replayed on frontend reconnection.

Build Knowledge Base is connected to a synchronous backend request. Start
reports a pending request and then success or failure in the activity log.
Build Chapters currently ends after coarse frame sampling; boss detection is
not yet connected to this button. Its label does not expand the
milestone beyond active boss fight detection.

## Build knowledge base for the game

Research:
Qwen3 14B Q5_K_M
LM Studio
Parallel Search MCP

### Initial backend increment

`POST /api/knowledge-base/build` accepts a game name. The existence check is an
explicit stub returning `false`; the existing-knowledge-base branch returns
"Knowledge base already exists" without invoking AI.

Otherwise, the controller renders the version-controlled prompt with the game
name and the fixed source `fextralife.com`, calls the native LM Studio chat API,
and validates the final message as JSON containing `bosses` and `sources`.
`classification` is required but accepts any JSON value, including null.
Only a validated answer is printed to the backend console before HTTP 200 is
returned. Persistence and further processing remain deferred.

Connection failures, timeouts, upstream HTTP errors, and invalid model responses
produce explicit error responses. Configuration and the REST contract are
documented in the [controller README](../../controller-application/README.md).

## Active boss fights detection

Original video
→ 480p proxy
→ sample frame every 30 sec
→ boss UI detection
→ candidate segment refinement
