Use Qwen3 for Knowledge Base Generation

## Status
Accepted

## Context
Project YCG needs game-specific knowledge such as boss names, locations, and recognizable events. This information should preferably be collected with free, locally running tools.

## Decision
Use **Qwen3 14B GGUF (Q5_K_M)** running in **LM Studio** as the initial local LLM for knowledge-base generation.

## Consequences
- No per-request LLM API cost.
- Runs locally and can be integrated into the pipeline.
- Knowledge quality depends on the model and retrieved sources.
- The exact model may be replaced later if a better option is found.