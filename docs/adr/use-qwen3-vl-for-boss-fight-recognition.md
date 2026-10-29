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

The first increment produces per-image predictions, consumed by the refinement
increment below. OCR and knowledge matching remain later work.

### Implemented second increment: boundary refinement

Keep deterministic boundary search and interval aggregation beside the vision
adapter in Python; Java adds a single refinement stage after coarse detection.
This reuses local FFmpeg range sampling and avoids moving image/time-window
management into the controller. No new deployed service is introduced.

Refine only adjacent differing predictions: sample new interior frames every
5 seconds, then refine every resulting transition every 1 second. Reuse existing
endpoint predictions and preserve additional transitions, with no implicit gap
merging. Use the same external detection prompt and batch configuration, captured
once for both refinement passes. Keep raw window predictions separate from the
derived interval artifact and record model/prompt metadata.

Choose `[first positive, first following negative)` boundaries and retain their
uncertainty brackets. Video edges are explicitly clipped, and an active final
coarse sample triggers tail analysis. Persist final intervals atomically and log
them through the controller's console and replayable UI events. This adds temporal
precision, not a guarantee of visual classification accuracy: short fights hidden
between equal coarse states can still be missed. Partial evidence remains useful
for inspection; automatic resume and OCR remain out of scope for this increment.

### Accepted amendment: minimum fight duration (2026-10-29)

The original refinement increment deliberately preserved even isolated positives
to keep model errors inspectable. Manual review found false positives at coarse
sample timestamps that survived as one-second fights because endpoint predictions
are reused. Keep that raw evidence, but supersede unconditional retention in the
derived interval list with an explicit, configurable duration policy.

Python rejects candidates shorter than `minimum_boss_fight_seconds` (default 10;
zero restores unfiltered behavior). Equality is retained, including for clipped
intervals measured only within the video. Do not merge gaps or alter model output.
Check after coarse loading and each completed refinement window. Skip a candidate's
remaining windows only when its maximum possible duration on the existing 1s
refinement grid is below the minimum. For a negative left endpoint L and first
positive P, the earliest possible positive is `L + min(1000, P - L)` milliseconds;
the following negative timestamp, or EOF, bounds the end. A clipped start is fixed.
This envelope also bounds any additional fights that refinement could discover
inside it. Retained intervals still require boundary brackets at most 1s wide.

Persist each rejection immediately in `rejected-intervals.jsonl` and emit a
nonterminal warning through the controller's WARN logger and replayable Activity
log. Early rejection records the envelope and upper bound, not fabricated exact
boundaries. Store the threshold in the manifest and final artifact; preserve raw
JSONL predictions. Existing artifacts remain readable without retroactive filtering.

This trades genuine short-fight recall for fewer noisy chapters and saves image
extraction/model calls on provably short candidates. It does not improve the
visual classifier; game-specific visual guidance remains future work.

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
