# ADR-003: Use 480p Proxy Video for Analysis

## Status
Accepted

## Context
Gameplay videos may be several hours long and recorded at 1440p or 4K. Initial boss-fight detection does not require full source resolution.

## Decision
Create a **480p proxy at approximately 2000 kbps** and use it as the default input for visual analysis.

## Consequences
- Faster decoding, frame extraction, and analysis.
- Lower storage and compute requirements.
- Some fine visual detail is lost.
- The original video can still be used when higher-resolution analysis is required.