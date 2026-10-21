# ADR — Video Preprocessing with FFmpeg

## Context

Project YCG needs a preprocessing component before boss fight detection.

The component must:

- create a low-resolution proxy video from the original video,
- extract sample frames based on a configurable sampling strategy.

## Decision

Create a dedicated **Video Processing Service**, implemented in Python.

Use **FFmpeg** as the underlying video processing engine.
Codec: H.264
Use nvenc coder to get performance benefit from NVIDIA video card

The service provides two main operations:

```text
createProxy(inputVideo, proxyConfig)

sampleFrames(video, samplingConfig)
```

### Proxy Generation

Create a 480p proxy video from the original input.
Target bitrate: 2000Kbps (constant bitrate)

The default path decodes and scales on the CPU and encodes with NVENC. An
optional `proxy.acceleration = "cuda"` setting requests CUDA hardware decoding,
`scale_cuda`, and NVENC encoding. Both paths retain the same proxy resolution,
H.264 output, bitrate, and source timeline. CUDA mode requires a compatible
source decoder, FFmpeg build, NVIDIA driver, and GPU. An unsupported setup fails
with the FFmpeg error instead of silently changing processing paths.

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

### Processing Progress

The service streams FFmpeg progress while proxy generation or frame sampling is
running. Its CLI shows frame count, FPS, processing speed, and source position;
proxy generation also shows approximate percent complete. The Python operations
offer structured progress events so the controller can later relay them to the
frontend. The start event includes the exact FFmpeg argument list, which the CLI
prints before status updates. FFmpeg errors remain visible, and completion is
reported only after the output has been validated and published. The update
interval is configurable.

For sparse sampling, the displayed source position is the most recent requested
sample timestamp. A continuous completion percentage would be misleading because
FFmpeg output timestamps advance only when a JPEG is emitted.

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
- CUDA proxy processing is an optional hardware-dependent setting; speed must be
  measured with representative recordings because it is not always faster.
- Sample frames require additional disk space.
- Frame persistence can later be made optional or cache-based if necessary.
