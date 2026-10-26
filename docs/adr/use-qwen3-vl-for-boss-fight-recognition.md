# ADR — Use Qwen3-VL for Boss Fight Recognition

**Status:** Accepted  
**Milestone:** 1 — Mortal Shell Boss Fight Detection Prototype

## Context

The system must detect boss fights in gameplay videos, determine their start and end timestamps, recognize boss names from the game's UI, and match them against the knowledge base.

All visual analysis must run locally. The initial prototype should avoid custom model training and large labeled datasets.

## Decision

Use **Qwen3-VL-8B-Instruct-GGUF** through the local LM Studio API for both boss health bar detection and boss name OCR.

Preferred quantization: **Q8_0**, with **Q4_K_M** as a fallback if necessary.

### Boss Health Bar Detection

- Use zero-shot visual recognition with structured prompts.
- Introduce few-shot prompting with reference images if needed.
- Return `PRESENT`, `ABSENT`, or `UNKNOWN`.
- Use 480p proxy frames for detection.
- Initially sample every 30 seconds (configurable).
- Refine detected fight boundaries using 5-second, then 1-second sampling.

### Boss Name Recognition

- Extract multiple frames from each detected fight interval.
- Use original-resolution frames for OCR.
- Prefer cropping the boss name region.
- Use Qwen3-VL to extract the displayed boss name.
- Aggregate OCR results across multiple frames.

### Knowledge Base Matching

- Perform deterministic matching in the Java Controller.
- Apply name normalization and optional fuzzy matching.
- Return `MATCHED`, `UNMATCHED`, or `OCR_FAILED`.
- Never force a match when confidence is insufficient.

## Rationale

- No custom model training required.
- Reuses the existing LM Studio infrastructure.
- Supports prompt-based, game-specific recognition.
- Simplifies the initial implementation.
- Allows later replacement with specialized detection or OCR models.

## Consequences

- Recognition is probabilistic and requires validation.
- Vision inference may be slower than specialized classifiers.
- Detection and OCR prompts must be tested and refined.
- The initial implementation targets Mortal Shell only.
- Model selection and recognition strategies may evolve without changing the overall processing pipeline.
