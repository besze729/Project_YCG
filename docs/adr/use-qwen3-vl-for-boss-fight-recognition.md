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
- Return one `activeBossFight` boolean for each input image, together with its `imageIndex`.
- Use frames from the 720p analysis proxy for detection.
- Initially sample every 30 seconds (configurable).
- Refine detected fight boundaries using 5-second, then 1-second sampling.

### Implemented first increment: batch detection

The earlier three-state `PRESENT`/`ABSENT`/`UNKNOWN` proposal is replaced for
this increment by the explicitly requested boolean contract. The prompt's
decision rule is visibility of a boss health bar in the image's top region.
Model uncertainty remains an accuracy limitation; transport errors, malformed
responses and missing results are processing failures, never negative detections.

Host the vision module inside the existing Python FastAPI service. Java retains
workflow orchestration and progress replay. This follows the controller ADR's
Python vision boundary without introducing another deployed process. The existing
Java knowledge-base client retains its separate model and search integration.

Use LM Studio's `/v1/chat/completions` image input and JSON-schema response format.
Send at most five images per request initially, sequentially, with explicit
indices and no previous conversation or tools. Validate every result's index,
order and boolean type. Store the supplied prompt in an external editable template
with `<?GAME_TITLE>` substitution; capture that prompt and the separate vision
configuration once per run. Preserve successful batches in timestamped JSONL;
publish a final file only on full success and retain partial data on failure.

This increment stops at per-image predictions. Refinement, OCR and knowledge
matching below describe later work, not behavior already provided by Process.

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
