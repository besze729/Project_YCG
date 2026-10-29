Use Qwen3 for Knowledge Base Generation

## Status
Accepted

## Context
Project YCG needs game-specific knowledge such as boss names, locations, and recognizable events. This information should preferably be collected with free, locally running tools.

## Decision
Use **Qwen3 14B GGUF (Q5_K_M)** running in **LM Studio** as the initial local LLM for knowledge-base generation.

The quantization is part of the selected model configuration. The controller's
default `qwen3-14b` value is an API model identifier, not a complete description
of the model weights. Select the Q5_K_M model in LM Studio and use its served
identifier through `LM_STUDIO_MODEL`; the controller does not select or validate
quantization itself.

## Consequences
- No per-request LLM API cost.
- Runs locally and can be integrated into the pipeline.
- Knowledge quality depends on the model and retrieved sources.
- The exact model may be replaced later if a better option is found.
