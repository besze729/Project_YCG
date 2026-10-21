# ADR — Video Preprocessing with FFmpeg

## Context

Project YCG needs a preprocessing component before boss fight detection.

The component must:

- create a low-resolution proxy video from the original video,
- extract sample frames based on a configurable sampling strategy.

## Decision

Create a dedicated **Video Processing Service**, implemented in Python.

Use **FFmpeg** as the underlying video processing engine.

The service provides two main operations:

```text
createProxy(inputVideo, proxyConfig)

sampleFrames(video, samplingConfig)
```

### Proxy Generation

Create a 480p proxy video from the original input.

### Frame Sampling

Extract frames according to the requested sampling configuration and persist them as high-quality JPEG files.

Frame identity and timestamp are encoded directly into the filename.

Example:

```text
frame_000042_00-21-00.000.jpg
```

No additional frame metadata file is required.

### Sampling Configuration

```text
samplingConfig:
    intervalSeconds
    startTime
    endTime
```

- `intervalSeconds` — time between sampled frames.
- `startTime` — optional beginning of the sampling range.
- `endTime` — optional end of the sampling range.

If `startTime` and `endTime` are omitted, the entire video is sampled.

This allows both coarse sampling:

```text
intervalSeconds = 30
```

and later fine-grained sampling of a specific range:

```text
intervalSeconds = 1
startTime = 01:10:30
endTime = 01:12:00
```

## Rationale

FFmpeg provides robust and efficient video decoding, transcoding, and timestamp-based frame extraction.

Persisted JPEG sample frames make the analysis pipeline:

- reproducible,
- easy to debug,
- reusable by multiple analysis components,
- independent from repeated video decoding.

Configurable time ranges and sampling intervals also support future **coarse-to-fine sampling** around detected boss encounters.

## Consequences

- FFmpeg becomes an external runtime dependency.
- Sample frames require additional disk space.
- Frame persistence can later be made optional or cache-based if necessary.