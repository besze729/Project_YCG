# Boss detection simulation

Created 2026-10-27 from the documented LM Studio chat-completions and structured-output
contract. This is a locally authored protocol mock; no cloud workspace or owner mock
was used for the user's local LM Studio installation.

## Purpose

Run the real Python detection API and HTTP client against the LM Studio protocol
mock in `../mocks/lmstudio-vision/default.js`. It returns deterministic negative
predictions to verify transport and persistence, not recognition accuracy.

## Execution

From `video-processing-service`, with Postman 1.75+ and Node on PATH:

```powershell
postman simulation run postman/simulations/boss-detection.sim.yaml --port 19490 --output ndjson --no-history
```

In another terminal in the same directory:

```powershell
.\.venv\Scripts\python.exe tests/vision_smoke.py --base-url http://localhost:19490/lmstudio
```

The test creates five synthetic 720p JPEGs and calls the production API through
FastAPI's test client. Confirm `VISION_FIVE_IMAGE_API_SMOKE_OK` and one HTTP 200
`request` event for the simulation's `lmstudio` member. Artifacts are under
ignored `output/vision-smoke-*`. Stop the simulation with Ctrl+C.

## Assumptions

- 2026-10-27: The actual local Qwen3 VL 8B Instruct accepted the same five-image
  JSON-schema request in an independent live smoke run. This mock does not model
  model loading, GPU capacity or classification quality.
- 2026-10-27: The simulator can access its user-local `~/.postman` runtime data;
  restricted sandboxes may require permission for that directory.

## Observations

<!-- begin:observations -->
- 2026-10-27: `tests/vision_smoke.py` passed. The sole member, `lmstudio`, served
  one `POST /v1/chat/completions` with HTTP 200; no members were idle. Five ordered
  records were published with source times 0, 1000, 2000, 3000 and 4000 ms.
<!-- end:observations -->

## What didn't work

- 2026-10-27: CLI 1.75 rejected a directory in the simulation member's `path`;
  referencing its `config.yaml` explicitly fixed startup.
