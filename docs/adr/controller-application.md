## Project YCG – Controller Application and Connected Services Technology Stack

- **Controller Application:** Java 25 + Spring Boot
- **Frontend:** React + TypeScript + Vite
- **Frontend form:** Local web application (`localhost`), optionally wrapped with Tauri later for desktop use
- **Frontend ↔ Controller:** REST + SSE for progress events
- **AI / Vision components:** Python
- **Python API:** FastAPI
- **Controller ↔ Python:** REST + JSON
- **Video processing:** FFmpeg invoked directly by the Controller
- **CV / OCR:** Python + OpenCV / appropriate AI models
- **Local LLM:** LM Studio + Qwen3
- **Knowledge Base:** JSON files
- **Job state:** In-memory / JSON initially
- **Architecture:** Modular monolith, not a microservice architecture

### Core Principle

> **The Controller is a deterministic workflow/orchestration application, not an AI application.**

It manages the overall workflow, state, error handling, communication between components, and serves the frontend.