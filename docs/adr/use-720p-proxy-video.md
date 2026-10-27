# ADR-004: Use 720p Proxy Video for Analysis

## Status

Accepted

## Context

Gameplay videos may be several hours long and recorded at 1440p or 4K. The
previous 480p proxy reduced processing cost, but the analysis should retain more
visual detail for gameplay UI and other recognition evidence.

The initial 720p decision retained the 2000 kbps target. At the same frame
rate, that gives each pixel less data than the 480p proxy received, especially
for high-motion gameplay. The target is raised to 2500 kbps as a quality trial;
recognition improvement has not yet been measured.

## Decision

Replace the default 854 × 480 proxy with a maximum of **1280 × 720**, preserving
the source aspect ratio. Use H.264 with a 2500 kbps CBR target and a 5000 kbit
rate-control buffer. Frame sampling continues to use the generated proxy;
higher-resolution verification can still use the original video.

## Consequences

- Proxy frames retain more visual detail for recognition.
- Decoding, frame extraction, and storage may cost more than with 480p proxies.
- At the target bitrate, the video stream uses approximately 25% more storage
  than the initial 720p, 2000 kbps setting.
- The 2500 kbps bitrate remains configurable and may need evaluation for 720p
  footage; this decision does not claim measured recognition or quality gains.
- Previously generated 480p proxies remain as they are. New jobs use the 720p
  default unless a configuration override selects another size.
