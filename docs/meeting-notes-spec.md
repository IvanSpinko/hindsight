# Spec: "Meeting Notes" module for VisionTrack

Version 1.0 · 2026-10-14 · status: approved, ready for implementation

## 1. Goal

Add a mode to VisionTrack for recording online meetings (Zoom, Teams, Meet, Discord, etc.):
the app records the screen and sound, transcribes speech and captures slide content, and then
produces **structured meeting notes**: a local Markdown file and, optionally, a Notion page.

## 2. Context: what already exists

- Java 21 + Spring Boot 3.5, desktop build via jpackage (`dist/VisionTrack`, tray icon, `desktop` profile).
- ONNX Runtime GPU with a CUDA → CPU backend chain (`OnnxYoloDetector`), CUDA libraries in `app/cuda`.
- FFmpeg via JavaCV (`VideoReader`, `VideoWriter`).
- Job queue and statuses (`AnalysisJobService`, `JobRepository`), web UI `static/index.html`.
- Video analysis (YOLO-World + IoU tracker) and live camera detection. **Do not break existing functionality.**

## 3. Scope

**In scope:**
- local use only, on the user's PC running Windows 10/11 x64;
- **GPU (NVIDIA CUDA) is required**, with automatic fallback to CPU if CUDA is unavailable;
- the app can be given to a friend as an archive: their keys and data are separate, mine are never shared.

**Out of scope:** server, accounts, AWS, payments, installer signing, macOS/Linux desktop,
AMD/DirectML, live transcript during the meeting (that is the next version).

## 4. User flow

1. The user opens the "Meetings" tab → chooses a monitor, a microphone and a system audio source → "Start recording".
2. While recording, a recording indicator (●REC + timer) is visible in the window and in the tray.
3. "Stop" → a processing job is created with statuses and progress (like video analysis).
4. When done, the UI shows the notes, a link to the folder and (if configured) to the Notion page.

## 5. Functional requirements

### 5.1 Capture
- **Screen:** FFmpeg `ddagrab` (fallback: `gdigrab`), 1 frame every 1–2 s, monitor selection.
- **Microphone:** FFmpeg `dshow`.
- **System audio (other participants):** WASAPI loopback. FFmpeg cannot do this, so a small
  native helper (`audio-loopback.exe`, C++) is needed that writes PCM to stdout or to a file.
- Audio tracks are captured separately (`mic.wav`, `system.wav`, 16 kHz mono) on a shared timeline, so the
  transcript can tell "Me" from "Other participant(s)". This is internal: for the user a meeting is **one
  recording** — after "Stop" the screen and sound are combined into a single `recording.mp4` for watching,
  and the separate tracks are used only for speech recognition (and deleted afterwards, see 5.6).
- Recording cannot start without an explicit user action. Before the first recording, a warning
  is shown that participants must consent to being recorded.

### 5.2 Slides
- Frame change detector: compare downscaled frames (grayscale difference or hash) against a threshold.
- A screenshot is saved when the frame changed and stayed stable for ≥ 2 s (animations do not create duplicates).
- Each slide: `slides/slide-NN.png` + timestamp.

### 5.3 Speech recognition
- Whisper model on the GPU. Default model: `large-v3-turbo` on CUDA, `small` on CPU (chosen automatically).
- Languages: Russian and English (configurable list, Russian is the fallback). Meetings often switch
  between them, so the language is detected for every piece of speech (≤ 30 s, cut at pauses), not once
  per meeting. Text on slides (often English) is read by Claude from the slide images in stage 4.
- Output: segments `{start, end, source: mic|system, text}`. Source `mic` = "Me", `system` = "Other participant(s)".
- Models are loaded only for the duration of processing and unloaded afterwards (do not keep YOLO and Whisper in VRAM at the same time).
- **Decide in stage 3** (build a prototype, compare, choose):
  - option A: `whisper.cpp` (built with CUDA) as a child process. Simpler and more reliable, can reuse `app/cuda`;
  - option B: ONNX export of Whisper in the existing ONNX Runtime. Single stack, but the decoder and tokenizer have to be written in Java.
  - Recommendation: start with option A.
  - **Decision (stage 3): option A.** whisper.cpp b5454 (CUDA 12.4 build) runs on an RTX 5070 (sm_120,
    kernels JIT-compiled by the driver on first use). One hour of speech is transcribed in ~1 min on the GPU
    (large-v3-turbo q8_0, ~0.9 GB VRAM), far below the 10-minute target; the CPU fallback (small q8_0) works.
    Option B was not prototyped: it would need a Java decoder, tokenizer and language detection for the same
    result, and A already meets every requirement.

### 5.4 Notes (Claude API)
- Input: transcript with timestamps + slide screenshots + lists of existing projects and tags from Notion (if connected).
- Output: **strictly JSON following a schema** (structured output / tool use), not free text. The schema is in section 6.
- Long meetings: split into ~10 min chunks, first partial summaries, then the final notes.
- The model is configurable, default `claude-sonnet-5-5`. During implementation, check the API and parameters against current documentation (`claude-api` skill).
- Without an API key this step is skipped: the transcript and slides are saved, no notes are created.

### 5.5 Export
- **Markdown (always):** `Documents\VisionTrack\Meetings\YYYY-MM-DD_HH-MM_<title>\`
  - `recording.mp4` (screen + sound), `notes.md`, `transcript.md`, `notes.json`, `slides\` + `slides.json`,
    `audio\` (speech recognition input, deleted depending on a setting).
- **Notion (if configured):**
  - "Meetings" database: Name (title), Date (date), Duration min (number), Project (select),
    Tags (multi-select), Participants (multi-select), Type (select), Export status;
  - page body: Summary → Decisions → Action items (to-do) → Timeline with timestamps and slide images → Transcript (toggle);
  - "Tasks" database (optional): Task, Owner, Due date, Status, relation to the meeting;
  - a "Create databases" button creates both databases on the chosen page;
  - respect API limits: ≤100 blocks per request, ≤2000 characters per rich text, ~3 requests/s (retries with backoff);
  - on error: the local copy stays, status "not sent", a "Retry export" button;
  - during implementation, check the current Notion API version (`Notion-Version`, databases and data sources, file uploads).

### 5.6 Settings ("Settings" tab)
- Devices: monitor, microphone, system audio source, a "Test" button (audio level).
- Claude API key, model; Notion token, default page or database.
- Whisper model (auto / manual), whether to delete audio after processing.
- Storage: `%LOCALAPPDATA%\VisionTrack\settings.json`. **Secrets never end up in the jar, in git or in logs.**
- Show the current backend: "CUDA" / "CPU".

## 6. Notes data schema (`MeetingNotes`)

```json
{
  "title": "Release 2.0 planning",
  "type": "call | lecture | interview | other",
  "project": "VisionTrack",
  "tags": ["release", "budget"],
  "participants": ["Ivan", "Anna"],
  "summary": "3–6 sentences",
  "decisions": ["..."],
  "actionItems": [{ "task": "...", "owner": "Anna", "due": "2026-10-20", "sourceTime": "00:12:40" }],
  "timeline": [{ "time": "00:03:12", "topic": "...", "details": "...", "slide": "slide-02.png" }],
  "openQuestions": ["..."]
}
```

Unknown fields = `null` or an empty array; never make anything up (state this rule in the prompt).

## 7. Architecture

New package `org.example.visiontrack.meeting`:

| Class / interface | Responsibility |
|---|---|
| `MeetingRecorder` | start/stop, capture coordination, shared timeline |
| `ScreenCapture`, `AudioCapture` | FFmpeg / loopback helper |
| `SlideChangeDetector` | key frame selection |
| `Transcriber` (interface) | `WhisperCppTranscriber` and/or `OnnxWhisperTranscriber` |
| `NotesGenerator` (interface) | `ClaudeNotesGenerator` |
| `NotesExporter` (interface) | `MarkdownExporter`, `NotionExporter` |
| `MeetingJobService` | background processing, statuses, progress (reuse the `AnalysisJobService` approach) |
| `SettingsService` | reading and writing `settings.json` |
| `MeetingController` | REST: `POST /api/meetings/start`, `POST /api/meetings/{id}/stop`, `GET /api/meetings`, `GET /api/meetings/{id}`, `POST /api/meetings/{id}/export/notion` |

HTTP clients (Claude, Notion): Spring `RestClient` or the official Anthropic Java SDK. Do not add heavy new dependencies unless necessary.

## 8. Non-functional requirements
- Processing a one-hour meeting on a GPU (RTX class): ≤ 10 min, excluding Claude response time.
- Minimal PC load while recording (the meeting must not lag): capture only, no recognition.
- A crash during recording does not lose what has already been recorded (recording in parts).
- Clear errors in the UI: "Microphone not found", "CUDA unavailable — running on CPU", "Invalid Notion key".
- Logs in `%LOCALAPPDATA%\VisionTrack\visiontrack.log`, without secrets and without transcript text.
- Unit tests: slide detector, building Notion blocks (splitting by limits), JSON parsing and validation, Markdown export.

## 9. Stages and acceptance criteria

| # | Stage | Done when |
|---|---|---|
| 0 | `git init`, first commit, tag `v1.0-video` | the repository has no `dist/`, `data/` or secrets; `mvn package` passes |
| 1 | Record screen and microphone to files | a 5-minute recording, files are valid, start/stop buttons in the UI |
| 2 | Slide detector | on a test recording of a presentation ≥ 90% of changes are found, few duplicates |
| 3 | Whisper on GPU (prototype A/B → choice) | transcript of Russian speech with timestamps; "on CUDA" in the log; CPU fallback works |
| 4 | Notes via Claude + Markdown | `notes.json` is valid against the schema, `notes.md` is readable; without a key only the transcript is saved |
| 5 | Notion export | page created with all properties, checkboxes and images; retrying the export works |
| 6 | System audio (loopback) | the other participant's voice from Zoom ends up in the transcript as "Other participant" |
| 7 | "Settings" tab | all settings persist between runs, secrets do not end up in logs |
| 8 | Build for a friend | lightweight jar without Linux natives, 7z archive, updated `README.txt` (NVIDIA driver 528+, recording consent, Notion setup) |

Work one stage at a time: at the end of each stage, build, test, commit.

## 10. Risks
- WASAPI loopback requires native code. If it drags on, temporarily use "Stereo Mix" or microphone only.
- VRAM: Whisper large + other programs on the GPU. Need automatic fallback to a smaller model when memory runs out.
- Build size grows by ~1–1.5 GB because of Whisper. Later we can switch to downloading the model on first run.
- Notion API changes (data sources). Check against the documentation during implementation.

---

## Kickoff prompt

> Read `docs/meeting-notes-spec.md` and study the current project code. Start with stage 0, then stage 1.
> Before writing code, briefly describe the planned changes per file. Work one stage at a time:
> build, tests and a commit at the end of each. GPU (CUDA) must be kept.
