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

This first UI increment only prepares inputs and records local interface
feedback. Backend processing is not connected, and Start explicitly reports
that no job was started. The Build Chapters label does not expand the milestone
beyond active boss fight detection.

## Build knowledge base for the game

Research:
Qwen3 14B Q5_K_M
LM Studio
Parallel Search MCP

## Active boss fights detection

Original video
→ 480p proxy
→ sample frame every 30 sec
→ boss UI detection
→ candidate segment refinement
