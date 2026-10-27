## Project YCG – Controller Application and Connected Services Technology Stack

- **Controller Application:** Java 25 + Spring Boot
- **Frontend:** React + TypeScript + Vite
- **Frontend form:** React UI in an Electron desktop window for native video paths;
  a browser preview remains available for development.
- **Frontend ↔ Controller:** REST + SSE for progress events
- **AI / Vision components:** Python
- **Current vision increment:** a separate module in the existing Python service
  calls local LM Studio for batched boss-health-bar detection; Java sequences
  proxy creation, sampling, detection and boundary refinement and relays persisted
  progress and final interval logs. Python owns temporal refinement and aggregation.
- **Python API:** FastAPI
- **Controller ↔ Python:** REST + JSON
- **Video processing:** Controller calls the Python Video Processing Service over
  local REST; that service owns FFmpeg execution (replaces direct controller
  invocation, following the dedicated video-processing-service ADR).
- **CV / OCR:** Python + OpenCV / appropriate AI models
- **Local LLM:** LM Studio + Qwen3
- **Knowledge Base:** JSON files
- **Job state:** In-memory / JSON initially
- **Architecture:** Modular monolith, not a microservice architecture

### Core Principle

> **The Controller is a deterministic workflow/orchestration application, not an AI application.**

It manages the overall workflow, state, error handling, communication between components, and serves the frontend.

### Native video selection decision

Status: Accepted for the local proxy-processing increment.

Browser File objects intentionally hide absolute paths. Source recordings can
exceed 100 GB, so uploading or copying them to obtain a server-side path is not
acceptable. The desktop wrapper exposes Electron's `webUtils.getPathForFile`
through a narrowly scoped preload bridge, preserving the existing file picker
and drag and drop while passing absolute paths to the controller.

Electron replaces the earlier optional Tauri direction for this increment. It
uses the existing Node toolchain and does not require a Rust/MSVC build setup.
The cost is a larger desktop runtime. The renderer remains sandboxed with
context isolation and no Node access. A local app protocol serves built assets
and forwards API requests only to the loopback controller. Backend and Python
processes still run separately; packaging their runtimes is deferred.
