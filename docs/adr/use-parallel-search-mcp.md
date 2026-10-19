Use Parallel Search MCP for Web Search

## Status
Accepted

## Context
The local Qwen model needs web access to collect current and game-specific information for knowledge-base generation.

## Decision
Use **Parallel Search MCP** as the initial web-search integration for Qwen3.

## Consequences
- Qwen can retrieve external information during knowledge generation.
- Search remains separated from the LLM itself.
- The MCP implementation can be replaced later.
- Search results must not be treated as inherently reliable.