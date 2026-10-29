# Project YCG – Initial Technical Specification

**Project codename:** Project YCG  
**Long codename:** Youtube Chapterization For Games  
**Document status:** Initial specification / feasibility baseline  
**Primary implementation target:** Local execution  
**Initial target game:** Mortal Shell

---

## 1. Project Overview

Project YCG is a local AI-assisted video analysis system designed to automatically generate meaningful YouTube chapter timestamps for long-form gameplay videos.

The primary use case is a YouTube creator working with long gameplay recordings, including:

- no-commentary gameplay,
- commentary gameplay,
- streamed gameplay,
- full playthroughs,
- challenge runs,
- boss compilations,
- and other long-form gaming content.

The system analyses the visual content of a gameplay video, identifies recognisable gameplay events, and produces a timestamped chapter list suitable for direct or near-direct insertion into a YouTube video description.

Example output:

```text
00:00 Intro
00:22 Starting a new cycle
01:23 Fallgrim
03:23 Grisha
05:36 Fallgrim
31:28 Ven Noctivagu
32:17 Fallgrim
50:02 Evil Baghead
50:51 Fallgrim
1:20:20 Seat of Infinity
2:50:04 Crucix, the Twiceborn
3:18:00 Imrod, the Unrepentant
3:50:15 Tarsus, the First Martyr
04:04:25 Ending + Outro
```

The long-term objective is not to create a Mortal Shell-specific tool, but a general gameplay chapterization system capable of adapting itself to different games.

---

# 2. Problem Statement

Creating YouTube chapters manually for long gameplay videos is time-consuming.

A single gameplay recording may be:

- 6–10 hours long,
- 4K resolution,
- 80–120 Mbps,
- larger than 100 GB.

A complete game may also span multiple videos. Large games such as Elden Ring may require approximately 70–200+ hours of recorded gameplay depending on the type of playthrough.

Manually reviewing these recordings and identifying chapter boundaries therefore does not scale well.

Project YCG should reduce this workload by automatically detecting important gameplay events and producing candidate chapter timestamps.

The system does not initially need to achieve fully autonomous 100% accuracy. A highly automated workflow where only uncertain events require human review is considered successful.

---

# 3. Core Design Principle

Project YCG should primarily understand the **visual gameplay content**.

The transcript must **not be considered a reliable primary source of truth**.

In commentary videos, the player may:

- talk about unrelated topics,
- refer to previous events,
- discuss future plans,
- interact with viewers,
- respond to stream chat,
- mention bosses or locations that are not currently visible.

For example, the speaker mentioning a boss name must not automatically cause the system to classify the current gameplay segment as that boss encounter.

Speech transcription may eventually be used as an optional secondary signal, but chapter identification must not depend on it.

The fundamental evidence source should therefore be:

> **What is visibly happening in the video?**

---

# 4. Long-Term System Vision

The final system should operate approximately as follows:

```text
Game title
    │
    ▼
Game Knowledge Acquisition
    │
    ├── bosses
    ├── locations
    ├── major events
    ├── NPCs
    ├── encounters
    └── visual references
    │
    ▼
Game Knowledge Base
    │
    │
Gameplay video
    │
    ▼
Proxy Generation
    │
    ▼
Coarse Visual Analysis
    │
    ▼
Candidate Event Detection
    │
    ▼
Fine-Grained Boundary Analysis
    │
    ▼
Event Timeline
    │
    ▼
Chapter Generation
    │
    ▼
Human Review
    │
    ▼
Corrections / Additional Knowledge
    │
    └──────────────► Game Knowledge Base
```

The system should become progressively more accurate for a specific game as additional videos and user corrections become available.

---

# 5. Game Knowledge Acquisition

Before analysing gameplay, Project YCG should eventually be capable of building a game-specific knowledge base.

Given only a game title, for example:

```text
Mortal Shell
```

the system should gather publicly available information about the game.

Potential knowledge categories include:

### Bosses

Examples:

```text
Grisha
Ven Noctivagu
Crucix, the Twiceborn
Imrod, the Unrepentant
Tarsus, the First Martyr
```

### Locations

Examples:

```text
Fallgrim
Seat of Infinity
Shrine of Ash
Crypt of Martyrs
```

### Other Recognisable Events

Future examples may include:

- major NPC encounters,
- new cycle / NG+ start,
- endings,
- important cutscenes,
- unique encounters,
- dungeon transitions,
- major quest events.

The system should also attempt to associate visual reference material with these entities.

Possible sources include:

- game wikis,
- guides,
- walkthroughs,
- gameplay screenshots,
- boss guides,
- publicly available videos,
- chaptered walkthrough videos.

The resulting data should be normalised into a structured game knowledge base.

Example conceptual structure:

```text
game/
├── bosses/
│   ├── grisha/
│   │   ├── metadata
│   │   ├── visual references
│   │   └── embeddings
│   └── tarsus/
│
├── locations/
│   ├── fallgrim/
│   └── seat_of_infinity/
│
└── events/
```

This knowledge acquisition process is part of the long-term architecture and does not have to be fully automated in the first milestone.

---

# 6. Human-in-the-Loop Learning

The system should support manual correction of unidentified or incorrectly classified events.

For example:

```text
02:43:18
Detected event: UNKNOWN
```

The user may provide:

```text
This is Tarsus, the First Martyr.
```

The system should then be able to associate additional visual information from this section of the video with that entity.

This new information should become reusable input for future video analysis.

The intended progression is therefore:

```text
First playthrough
    ↓
automatic recognition
    +
manual corrections
    ↓
improved game knowledge

Second playthrough
    ↓
higher recognition accuracy

Third playthrough
    ↓
even less human intervention
```

The initial implementation should favour external knowledge storage, visual references, embeddings, and user corrections rather than requiring the underlying AI model to be retrained after every correction.

---

# 7. Source Video Characteristics

The system must be designed for very large source files.

Representative source video:

```text
Resolution:     3840 × 2160
Frame rate:     60 FPS
Bitrate:        80–120 Mbps
Duration:       6–10 hours
File size:      100+ GB
```

Direct AI processing of the complete source video at original quality would be unnecessarily expensive.

Project YCG should therefore use a multi-resolution processing strategy.

---

# 8. Analysis Proxy Generation

Before AI-based analysis, the original video should be converted into a significantly smaller analysis proxy.

Example:

```text
SOURCE VIDEO

3840 × 2160
60 FPS
100 Mbps
100+ GB

       │
       ▼

ANALYSIS PROXY

up to 1280 × 720, preserving source aspect ratio

significantly reduced bitrate
currently 2.5 Mbps
```

The current default target is 720p at 2500 kbps. The exact proxy parameters
must still be evaluated experimentally.

Potentially even lower resolutions may be useful for the first coarse analysis pass if recognition accuracy remains acceptable.

The source video must remain available for later high-resolution verification.

The proxy is therefore not intended to replace the original video.

Its purpose is to reduce:

- decoding cost,
- storage bandwidth,
- frame extraction cost,
- AI inference cost,
- overall processing time.

---

# 9. Progressive Video Analysis

The video must not initially be analysed frame-by-frame.

A 10-hour 60 FPS video contains approximately:

```text
2,160,000 frames
```

Analysing every frame with an AI model would be unnecessarily expensive.

Project YCG should therefore use progressive temporal refinement.

## 9.1 Coarse Pass

Initial proposal:

```text
1 analysed frame every 30 seconds
```

For a 10-hour video this results in only:

```text
1,200 coarse analysis frames
```

This pass attempts to determine the approximate current gameplay state or event.

Example:

```text
01:00:00 → Fallgrim
01:00:30 → Fallgrim
01:01:00 → Fallgrim
01:01:30 → Grisha boss encounter
```

This suggests that an event transition occurred somewhere between:

```text
01:01:00
and
01:01:30
```

---

# 10. Fine-Grained Boundary Search

When the coarse pass detects that two analysed frames represent different meaningful states, Project YCG should reanalyse only that interval with higher temporal resolution.

Example:

```text
01:01:00 → Fallgrim
01:01:30 → Grisha
```

Instead of analysing the entire video more frequently, the system investigates only:

```text
01:01:00–01:01:30
```

The interval may then be sampled at:

```text
5 seconds
```

and subsequently:

```text
1 second
```

or finer resolution if necessary.

Conceptually:

```text
30-second sampling
       ↓
transition detected
       ↓
5-second sampling
       ↓
boundary narrowed
       ↓
1-second sampling
       ↓
precise chapter candidate
```

The original high-resolution video may be accessed during the final verification stage if the proxy does not contain sufficient visual detail.

This coarse-to-fine approach is a fundamental architectural principle of Project YCG.

---

# 11. Local AI Requirement

The AI-related components should run locally.

Cloud AI services should not be required for core video recognition.

Reasons include:

- privacy,
- predictable costs,
- very large video files,
- avoiding large video uploads,
- experimentation freedom,
- reusable local processing,
- long-running batch workloads.

The implementation should primarily use free and preferably open-source software.

The exact AI architecture is currently an open design question.

Possible categories include:

- vision-language models,
- image embedding models,
- OCR,
- image classifiers,
- object detection,
- visual similarity search,
- rule-based event detection,
- combinations of the above.

Project YCG should not assume that a single model will solve the entire recognition problem.

A multi-stage pipeline is preferred.

---

# 12. Available Hardware

The current target machine is:

```text
CPU:
Intel Core i9-14900K

GPU:
NVIDIA GeForce RTX 4080 SUPER
16 GB VRAM

System memory:
96 GB RAM

Storage:
2 TB NVMe SSD
4 TB NVMe SSD
12 TB HDD
```

The hardware significantly influences model selection.

The first implementation should therefore target models and configurations that can operate realistically within:

```text
16 GB GPU VRAM
+
96 GB system RAM
```

CPU/GPU hybrid inference may be considered where appropriate, but processing speed remains an important factor because individual videos can be many hours long.

Fast NVMe storage should preferably be used for:

- active proxy videos,
- extracted frames,
- temporary analysis data,
- model files,
- embedding databases.

The HDD is better suited for archival source material and long-term storage.

---

# 13. Milestone Strategy

Because general-purpose gameplay understanding is a difficult problem, development should begin with a deliberately limited scope.

The first milestone will target **one game only**.

Selected game:

# Mortal Shell

Mortal Shell is considered a suitable initial test case because it is:

- a complete commercial game,
- relatively small in scope,
- visually distinctive,
- structured around a limited number of major locations,
- built around easily identifiable bosses,
- familiar enough to allow manual validation of AI results.

This provides a realistic but constrained environment for validating the complete Project YCG pipeline.

---

# 14. Milestone 1 – Mortal Shell Boss Detection

The first functional milestone should focus exclusively on identifying boss fights.

Location detection and general gameplay chapterization are explicitly outside the initial milestone.

Target boss-related output:

```text
03:23 Grisha
31:28 Ven Noctivagu
2:50:04 Crucix, the Twiceborn
3:18:00 Imrod, the Unrepentant
3:50:15 Tarsus, the First Martyr
```

## 14.1 Boss Fight Definition for Milestone 1

For the first implementation, a boss chapter represents only the **actual combat section**.

The initial system does not need to include:

- boss introduction cutscenes,
- arena approach,
- dialogue before combat,
- post-boss cutscenes,
- post-victory transitions.

The relevant interval begins when the actual boss fight starts and ends when the boss is defeated.

For the initial boss-only pipeline, apply a configurable minimum detected fight
duration, defaulting to 10 seconds. Reject intervals strictly shorter than the
minimum; an interval exactly at the minimum is retained. Use the observed
in-video duration for fights clipped at a video edge. Zero disables this filter.
Rejected intervals do not become boss chapters, and their surrounding
Exploration sections form one continuous section.

This is an explicit heuristic, not a correction of the visual model's evidence.
Keep raw predictions and persist each rejection with its reason and threshold;
show it at warning level in the activity log. Further refinement may be skipped
only when the maximum possible interval duration under the sampling rules is
already below the minimum. In that case retain the enclosing time window and
duration upper bound rather than claiming exact start/end times.

A particularly useful visual signal may be the presence and state of the boss health bar.

Conceptually:

```text
boss HP bar appears
      ↓
active boss fight
      ↓
boss HP reaches zero / fight ends
```

Other visual signals may later be combined with this.

The exact boss-recognition method remains an open technical design decision.

---

# 15. Boss Recognition

Boss recognition must be visual.

Potential evidence sources include:

### Visual appearance

Recognition of:

- boss model,
- arena,
- environment,
- characteristic visual effects.

### User interface

Detection of:

- boss health bar,
- displayed boss name,
- changes in HUD state.

### OCR

If the game displays the boss name on screen, OCR may provide a strong supporting signal.

### Visual reference matching

Frames from the analysed video may be compared with stored boss reference images or their visual embeddings.

### Vision-language model

A local multimodal model may be used to classify ambiguous frames against the known Mortal Shell boss list.

The final design may combine several of these signals rather than relying on one recognition technique.

---

# 16. Boss Encounter Boundary Refinement

Milestone 1 only requires detection of the clean combat interval.

Later milestones may introduce a more semantic concept of a **boss encounter**.

Example future event structure:

```text
boss area entry
→ pre-fight cutscene
→ fight begins
→ player death
→ loading
→ retry
→ boss fight
→ victory
→ post-fight cutscene
→ gameplay resumes
```

This distinction is necessary because real gameplay videos may contain:

- multiple boss attempts,
- player deaths,
- loading screens,
- retries,
- edited sections,
- inserted memes,
- commentary overlays,
- post-processing.

Determining the complete narrative boss encounter is substantially harder than detecting the active combat section.

Therefore it is intentionally deferred to a later milestone.

---

# 17. Future Milestone – Location Recognition

After boss detection is sufficiently reliable, Project YCG should expand to gameplay location recognition.

Target examples for Mortal Shell include:

```text
Fallgrim
Seat of Infinity
Shrine of Ash
Crypt of Martyrs
```

Expected future output:

```text
01:23 Fallgrim
03:23 Grisha
05:36 Fallgrim
1:20:20 Seat of Infinity
2:50:04 Crucix, the Twiceborn
2:56:13 Shrine of Ash
3:18:00 Imrod, the Unrepentant
3:22:31 Crypt of Martyrs
```

Location recognition introduces additional challenges because a player may repeatedly enter and leave the same area.

Temporal smoothing and minimum-duration rules will probably be required to avoid meaningless chapter fragmentation.

---

# 18. Future Milestone – General Event Recognition

Longer-term chapter categories may include:

```text
BOSS
MINIBOSS
LOCATION
DUNGEON
NPC
QUEST
MAJOR_EVENT
CUTSCENE
ENDING
HUB
SPECIAL_ENCOUNTER
```

The system should ultimately determine which events are meaningful enough to become YouTube chapters rather than simply describing everything visible in the video.

---

# 19. Future Milestone – Automatic Game Bootstrap

The long-term objective is that adding support for a new game does not require manually building the entire knowledge base.

Expected workflow:

```text
User:
Game = Elden Ring

System:
Researches Elden Ring
      ↓
finds known bosses
      ↓
finds locations
      ↓
finds major events
      ↓
collects visual references
      ↓
creates initial knowledge base
      ↓
analyses user's gameplay
```

The first analysed playthrough may contain unidentified events.

The user can label them.

Those corrections then become additional knowledge for future playthroughs.

The intended target is therefore not:

> A model that already knows every possible game.

Instead:

> A system capable of automatically bootstrapping useful game-specific knowledge and progressively specialising itself through additional evidence and user corrections.

---

# 20. Expected Final User Workflow

The eventual user experience should be approximately:

```text
1. Select game:
   Mortal Shell

2. Select video:
   Virtuous Cycle - Mortal Shell Full Playthrough.mp4

3. Start analysis

4. System:
   - prepares game knowledge
   - creates analysis proxy
   - performs coarse analysis
   - investigates candidate transitions
   - performs visual recognition
   - generates chapter candidates

5. User reviews uncertain results

6. System outputs:
```

```text
00:00 Intro
00:22 Starting a new cycle
01:23 Fallgrim
03:23 Grisha
05:36 Fallgrim
31:28 Ven Noctivagu
32:17 Fallgrim
50:02 Evil Baghead
50:51 Fallgrim
1:20:20 Seat of Infinity
1:42:00 Vim
1:43:45 Seat of Infinity
2:50:04 Crucix, the Twiceborn
2:56:13 Shrine of Ash
3:11:11 Vim
3:11:51 Shrine of Ash
3:18:00 Imrod, the Unrepentant
3:22:31 Crypt of Martyrs
3:30:25 Enslaved Grisha
3:31:39 Crypt of Martyrs
3:50:15 Tarsus, the First Martyr
3:55:24 The Dark Shells (Corrupted Seeds of the Shells)
04:04:25 Ending + Outro
```

The result should require minimal editing before being pasted into a YouTube description.

### Initial boss-only chapter export

For the current incremental implementation, cover each video's complete timeline
with `BOSS_FIGHT` for detected fight intervals and `Exploration` for their
complement. `Exploration` is a placeholder label, not a location or gameplay-state
recognition result. Emit a chapter at time zero and at each label change, with
whole-second YouTube timestamps. Do not append an empty chapter at the video end.

After successful processing, show a **Show result** button. It opens a separate
window containing the plain chapter list in a selectable textarea. For multiple
videos, display one video's list at a time with a video selector. Filenames,
explanations and validation notes must remain outside the copyable chapter text.

Preserve accepted interval boundaries after minimum-duration filtering rather
than extending or merging short sections to meet YouTube's chapter-count or
minimum-duration requirements. Report those limits
separately from the text. Original millisecond interval evidence remains unchanged;
the export uses whole seconds, resolving timestamp collisions without duplicate
or zero-length chapters. Boss names and richer chapter labels remain later work.

---

# 21. Initial Functional Requirements

For the first Mortal Shell milestone, Project YCG should be able to:

1. Accept a gameplay video as input.
2. Handle multi-hour source videos.
3. Generate a lower-resolution analysis proxy.
4. Extract frames from the proxy at configurable intervals.
5. Begin with approximately one analysed frame every 30 seconds.
6. Identify candidate Mortal Shell boss fights using visual information.
7. Perform more detailed analysis around suspected event boundaries.
8. Associate detected encounters with known boss names.
9. Produce timestamped boss chapter candidates.
10. Preserve confidence information internally.
11. Allow incorrect or unidentified detections to be manually corrected.
12. Produce a human-readable chapter list suitable for YouTube.

---

# 22. Initial Non-Functional Requirements

### Local execution

Core AI inference and video analysis must run locally.

### Free software

The implementation should prioritise free and open-source tools.

### Large-file support

The architecture must avoid unnecessary duplication or full-resolution frame extraction from 100+ GB video files.

### Resource efficiency

The system should minimise expensive AI inference through:

- proxy generation,
- temporal sampling,
- coarse-to-fine analysis,
- targeted reprocessing.

### Reproducibility

Recognition results should be traceable to:

- source timestamp,
- analysed frames,
- detection method,
- confidence,
- associated game entity.

### Extensibility

The architecture must not hard-code Mortal Shell so deeply that supporting another game requires rewriting the analysis engine.

Game-specific knowledge should be separated from the generic video-analysis pipeline.

---

# 23. Explicit Non-Goals for Milestone 1

The first milestone does **not** need to:

- support arbitrary games,
- identify every Mortal Shell location,
- identify NPCs,
- detect quests,
- understand narrative context,
- process every video frame,
- rely on transcripts,
- identify complete boss encounter narratives,
- include intro cutscenes in boss chapters,
- perfectly handle edited meme sequences,
- automatically research every game from the internet,
- achieve fully autonomous 100% chapter accuracy,
- retrain an AI model after every correction.

These capabilities may be added incrementally.

---

# 24. Key Technical Questions Still Open

The following require experimentation before final architectural decisions are made:

### Proxy quality

Evaluate whether the current 720p default offers the best trade-off against:

```text
480p
or lower
```

provides the best trade-off between recognition quality and processing cost.

### Temporal sampling

Initial proposal:

```text
30 seconds
```

but this must be validated against real Mortal Shell gameplay.

### Visual recognition technology

Candidates include:

- visual embeddings,
- multimodal VLM inference,
- OCR,
- UI detection,
- classical computer vision,
- object detection,
- combinations of these approaches.

### Local AI model

The exact model must be selected based on:

- recognition accuracy,
- video/image understanding capability,
- inference speed,
- 16 GB VRAM constraint,
- 96 GB RAM availability,
- quantisation support.

### Boss boundary detection

Determine whether boss HP bar recognition alone is sufficient for the first milestone or whether additional signals are necessary.

### Knowledge storage

Determine how game entities, visual references, embeddings, corrections, and analysis history should be persisted.

---

# 25. Milestone Roadmap

## M0 – Technical Foundation

Establish:

```text
video input
→ proxy generation
→ frame extraction
→ timestamp mapping
```

No AI recognition required yet.

---

## M1 – Mortal Shell Boss Recognition

Implement:

```text
Mortal Shell
→ known boss dataset
→ visual analysis
→ boss detection
→ combat timestamp detection
→ chapter list
```

Primary goal:

> Reliably detect boss fights from a Mortal Shell gameplay video.

---

## M2 – Boss Encounter Refinement

Add:

- intro cutscene detection,
- encounter start refinement,
- retries,
- deaths,
- multiple attempts,
- victory detection,
- post-fight transitions.

---

## M3 – Mortal Shell Location Recognition

Add:

```text
Fallgrim
Seat of Infinity
Shrine of Ash
Crypt of Martyrs
...
```

and temporal smoothing.

---

## M4 – Mortal Shell Full Chapterization

Combine:

```text
bosses
+
locations
+
important events
+
intro / ending
```

into a complete chapter list.

---

## M5 – Automatic Game Knowledge Acquisition

Given a game title:

```text
game title
→ web research
→ entity extraction
→ image/reference acquisition
→ knowledge base
```

with user-assisted validation.

---

## M6 – Multi-Game Support

Separate generic analysis logic from game-specific knowledge and validate the architecture with additional games.

---

## Long-Term Goal – General Gameplay Chapterization

Target experience:

```text
Game title
+
Gameplay video(s)
        ↓
Project YCG
        ↓
Automatic game research
+
Visual video understanding
+
Progressive learning
        ↓
YouTube-ready chapter list
```

The system should require progressively less user intervention as its knowledge of a particular game improves.

---

# 26. Project Vision Statement

**Project YCG aims to build a local, AI-assisted system that transforms long gameplay videos into meaningful YouTube chapter structures through visual understanding, progressive temporal analysis, automatically acquired game knowledge, and reusable human corrections.**

The project will begin with a deliberately constrained Mortal Shell boss-recognition prototype and progressively evolve toward general-purpose gameplay chapterization.
