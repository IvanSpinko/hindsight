# Spec: "Meeting Notes" module for VisionTrack

Version 2.0 · 2026-10-14 · status: approved. Version 2.0 narrows the scope: notes come from **speech only**,
written by a **local model** (no paid API); the slide detector and the Claude client of version 1.0 are dropped.

## 1. Goal

Add a mode to VisionTrack for online meetings (Zoom, Teams, Meet, Discord, etc.): the user records a meeting
or uploads an existing recording, the app transcribes everything that was said and produces **structured
meeting notes**: a simple local Markdown file now, a Notion page later. The screen is recorded too, but only
so the user can rewatch visual details; it is not analysed.

## 2. Context: what already exists

- Java 21 + Spring Boot 3.5, desktop build via jpackage (`dist/VisionTrack`, tray icon, `desktop` profile).
- ONNX Runtime GPU on CUDA (`OnnxYoloDetector`), CUDA libraries in `app/cuda`.
- FFmpeg via JavaCV (`VideoReader`, `VideoWriter`).
- Job queue and statuses (`AnalysisJobService`, `JobRepository`), web UI `static/index.html`.
- Video analysis (YOLO-World + IoU tracker) and live camera detection. **Do not break existing functionality.**

## 3. Scope

**In scope:**
- local use only, on the user's PC running Windows 10/11 x64; free and offline, no API keys;
- **GPU (NVIDIA CUDA) is required** for all heavy processing. Without CUDA the app shows a clear error
  that an NVIDIA graphics card is required; there is no CPU fallback (decision of 2026-10-14). On the GPU:
  YOLO, Whisper, the notes model and screen encoding (NVENC). Still on the CPU: Silero VAD (whisper.cpp b5454
  aborts with `-ug`), AAC audio and video decoding (see the handoff note);
- the app can be given to a friend as an archive: their data is separate, mine is never shared.

**Out of scope:** analysing the screen (slides, shared documents), paid LLM APIs, server, accounts, AWS,
payments, installer signing, macOS/Linux desktop, AMD/DirectML, live transcript during the meeting.

## 4. User flow

1. The user opens the "Meetings" tab and either
   - chooses a monitor and a microphone → "Start recording" → ... → "Stop", or
   - uploads an existing recording (audio or video file).
2. While recording, a recording indicator (●REC + timer) is visible in the window and in the tray.
3. A processing job runs with statuses and progress: speech recognition, then notes.
4. When done, the UI links to the notes, the transcript, the recording and the folder (later: the Notion page).

## 5. Functional requirements

### 5.1 Capture
- **Screen:** FFmpeg `ddagrab` (fallback: `gdigrab`), 1 frame per second, with the mouse pointer, encoded by
  NVENC. Only for people to watch.
- **Microphone:** FFmpeg `dshow` → transcript speaker "Me".
- **System audio (other participants): required.** WASAPI loopback of the default output device; FFmpeg cannot
  do this, so a small helper (`audio-loopback.exe`, C# 5 compiled with the compiler built into Windows, no
  dependencies; source in `native/audio-loopback`) writes float PCM to a pipe into ffmpeg. Windows sends no
  loopback data while nothing plays, so the helper writes silence by the clock and the track stays in step with
  the microphone. Transcript speaker "Other participants". If the helper is missing or the device cannot be
  captured, the meeting is recorded without it and shows a warning.
- Audio tracks are captured separately (16 kHz mono) on a shared timeline, so the transcript can tell "Me" from
  "Other participants". For the user a meeting is **one recording**: after "Stop" the screen and both sounds are
  combined into `recording.mp4`.
- Without headphones the microphone also hears the other participants: the same phrase then appears on both
  tracks at almost the same time. Such duplicates are removed from the transcript (kept on the system track):
  a microphone segment is dropped when ≥ 60% of its words were said on the system track within ±2 s
  (`EchoFilter`). Verified with a speaker and no headphones on 2026-10-14.
- Recording cannot start without an explicit user action. Before the first recording, a warning
  is shown that participants must consent to being recorded.

### 5.2 Uploading a recording
- Audio (mp3, m4a, wav, ogg, flac) or video (mp4, mkv, webm, mov). Only the sound is used.
- The file is copied into a new meeting folder; ffmpeg extracts 16 kHz mono audio for speech recognition.
  A video is kept as `recording.mp4` (re-encoded with NVENC only if the browser cannot play it).
- All voices are on one track, so the transcript speaker is "Participants" (no "Me").

### 5.3 Speech recognition
- whisper.cpp (`large-v3-turbo` q8_0) on CUDA as a child process; without CUDA the step fails with
  "Speech recognition needs an NVIDIA graphics card".
- Languages: Russian and English (configurable list, Russian is the fallback). Meetings often switch
  between them, so the language is detected for every piece of speech (≤ 30 s, cut at pauses).
- Both tracks are recognised with one loaded model, then merged in time order.
- Sound labels that Whisper writes instead of text ("*music playing*", "[Music]", "(applause)") are dropped.
- Output: segments `{start, end, source, language, text}`; `transcript.json` and `transcript.md`.
- Measured: one hour of speech in ~1 min on an RTX 5070.

### 5.4 Notes (local model)
- llama.cpp `llama-server` (CUDA build) as a child process, started for the notes step and stopped afterwards.
- Model: **Gemma 4 12B instruct, QAT Q4_0** (~7 GB, Apache 2.0). Chosen on 2026-10-14 over Qwen3 14B Q4_K_M on a
  Russian/English test meeting: correct facts and relative dates, one timeline entry per topic, ~15 s per short
  meeting, ~7.7 GB VRAM. Qwen3 14B invented due dates, listed every remark and needed ~8.7 GB.
- Input: the transcript with timestamps and speakers, meeting title, date and length, the language of most of the
  speech (models fall back to English when asked to choose themselves).
- Output: **JSON constrained by the schema in section 6** (llama.cpp grammar via `response_format`), then
  validated: unknown values → `null` / `[]`. The prompt says: never invent facts.
- Long meetings: ~10 min parts → partial notes → merged notes; a part (or a merge) that does not fit the context is
  split further.
- Without the model files the step is skipped with a warning; the transcript is kept.
- Object detection (YOLO) is unloaded while a meeting is processed, so the speech and notes models have the GPU.
- **Video memory:** the models run one after another, never together: Whisper (~1 GB) is stopped before the
  notes model (~7.7 GB) starts. Other programs (the call itself, a browser, games) may hold part of the 12 GB, so
  the free memory is checked before the notes model starts; if it is short, the notes are skipped with a message
  saying how much is free and needed and to close GPU programs and press "Process again".

### 5.5 Export
- **Markdown (always):** `Documents\VisionTrack\Meetings\YYYY-MM-DD_HH-MM_<title>\`
  - `recording.mp4` (screen + sound), `notes.md`, `notes.json`, `transcript.md`, `transcript.json`,
    `audio\` (speech recognition input, deleted depending on a setting).
- **Notion (later):**
  - "Meetings" database: Name (title), Date (date), Duration min (number), Project (select),
    Tags (multi-select), Participants (multi-select), Type (select), Export status;
  - page body: Summary → Decisions → Action items (to-do) → Timeline with timestamps → Transcript (toggle);
  - "Tasks" database (optional): Task, Owner, Due date, Status, relation to the meeting;
  - respect API limits: ≤100 blocks per request, ≤2000 characters per rich text, ~3 requests/s (retries with backoff);
  - on error: the local copy stays, status "not sent", a "Retry export" button;
  - during implementation, check the current Notion API version.

### 5.6 Settings ("Settings" tab, later)
- Devices: monitor, microphone, system audio source, a "Test" button (audio level).
- Notion token, default page or database.
- Whether to delete audio after processing.
- Storage: `%LOCALAPPDATA%\VisionTrack\settings.json`. **Secrets never end up in the jar, in git or in logs.**

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
  "timeline": [{ "time": "00:03:12", "topic": "...", "details": "..." }],
  "openQuestions": ["..."]
}
```

Unknown fields = `null` or an empty array; never make anything up (stated in the prompt, enforced by
`MeetingNotes.cleaned`). Participants are people, not the speaker labels "Me" / "Other participants".

## 7. Architecture

Package `org.example.visiontrack.meeting`:

| Class / interface | Responsibility |
|---|---|
| `MeetingRecorder` | start/stop, capture coordination, shared timeline |
| `ScreenCapture`, `AudioCapture` | FFmpeg arguments / loopback helper |
| `Transcriber` (interface) | `WhisperCppTranscriber` (+ `WhisperServer` process) |
| `NotesGenerator` (interface) | `LocalNotesGenerator` (+ `LlamaServer` process), `NotesChunker`, `NotesPrompt` |
| `NotesExporter` (interface) | `MarkdownExporter`, later `NotionExporter` |
| `MeetingJobService` | background processing, statuses, progress |
| `MeetingController` | REST: start/stop, list, process again, transcript, notes, recording, open folder; later import and Notion export |

## 8. Non-functional requirements
- Processing a one-hour meeting on a GPU (RTX class): ≤ 10 min.
- Minimal PC load while recording (the meeting must not lag): capture only, no recognition.
- A crash during recording does not lose what has already been recorded (recording in parts).
- Clear errors in the UI: "Microphone not found", "... needs an NVIDIA graphics card with CUDA".
- Logs in `%LOCALAPPDATA%\VisionTrack\visiontrack.log`, without secrets and without transcript text.
- Unit tests: JSON parsing and validation, chunking, Markdown export, the notes generator against a fake model,
  later Notion blocks (splitting by limits).

## 9. Stages and acceptance criteria

| # | Stage | Done when |
|---|---|---|
| 0 | `git init`, first commit, tag `v1.0-video` | done |
| 1 | Record screen and microphone to files | done |
| 2 | ~~Slide detector~~ (removed in v2.0), one `recording.mp4` | done |
| 3 | Whisper on GPU, Russian + English | done; without CUDA a clear "NVIDIA GPU required" error |
| 4 | Notes with a local model + Markdown | done; `notes.json` follows the schema, `notes.md` is readable; without the model only the transcript is saved |
| 5 | System audio (loopback) | done; the other participant's voice is in the transcript as "Other participants"; echo duplicates removed |
| 6 | Upload a recording | an mp3 / mp4 from Zoom or OBS gets a transcript and notes like a recorded meeting |
| 7 | Notion export | page created with all properties and checkboxes; retrying the export works |
| 8 | "Settings" tab | all settings persist between runs, secrets do not end up in logs |
| 9 | Build for a friend | `tools\` and `models\` in the archive, lightweight jar without Linux natives, updated `README.txt` (NVIDIA driver 528+, 12 GB VRAM recommended, recording consent) |

Work one stage at a time: at the end of each stage, build, test, check in the running app, commit.

## 10. Risks
- WASAPI loopback requires native code. If it drags on, temporarily use "Stereo Mix".
- VRAM: the notes model needs ~7.7 GB plus what Windows and other programs use (~3 GB on the dev PC);
  8 GB GPUs need a smaller model (e.g. a 4B model). No CPU fallback.
- Build size grows by ~10 GB because of the models. Later the models can be downloaded on first run.
- Local notes are weaker than those of large cloud models, especially on long meetings: dates and owners must
  be checked by the user (stated under every `notes.md`).
- Notion API changes. Check against the documentation during implementation.
