# Meeting notes: handoff for the next session

Status as of 2026-10-14. Read this together with `docs/meeting-notes-spec.md` (the spec, version 2.0).
Goal for the next session: **stage 7 (Notion export)**, then stage 8 (Settings tab).

## 1. Where things stand

| Stage | Status | Commit |
|---|---|---|
| 0 git init, tag `v1.0-video` | done | `2297088` (`main`) |
| 1 record screen + microphone | done | `fd9081a` |
| 2 one `recording.mp4` (slide detector removed later) | done | `62e1999` |
| 3 Whisper on GPU, Russian + English | done | `65387dc` |
| GPU only, clear error without CUDA | done | `62a7800` |
| 4 notes with a local model + Markdown | done | `dfe31db` |
| 5 system audio (WASAPI loopback), echo filter, VRAM check | done | see `git log` ("Meetings: system sound ...") |
| 6 upload a recording, screen at 30 fps | done | see `git log` ("Meetings: upload ...") |
| 7 Notion export | **next** | |
| 8 Settings tab | todo | |
| 9 build for a friend | todo | |

- Repository: https://github.com/IvanSpinko/visiontrack-object-detection-aws, remote `origin`.
- Work happens on branch `meeting-notes`; `main` and tag `v1.0-video` are the video-analysis release.
  Merge `meeting-notes` into `main` (pull request) when the user asks.
- Tests: 84, all green (`mvn package`). They need the NVIDIA GPU (NVENC in the capture tests).

## 2. Working agreement with the user

- The user writes in Russian; answer in Russian.
- **Every file in the repo is English-only**: code, comments, UI, config, tests, docs. No Cyrillic at all.
  Before each commit: `git diff --cached --name-only --diff-filter=d` and check for characters in the Cyrillic
  range U+0400 to U+04FF. Test data uses English text even where the feature is about Russian speech.
- **Single author on GitHub**: no `Co-Authored-By` trailer and no "Generated with Claude Code" line in
  commits or pull requests. The history was rewritten once to remove them; do not add them back.
- One stage at a time: short plan per file first → code → `mvn package` with tests → acceptance
  check in the running app → commit → `git push`.
- **Everything on the GPU, no CPU fallback** (2026-10-14): without CUDA a clear "needs an NVIDIA graphics card"
  error. What still runs on the CPU: section 6.
- **Scope of version 2.0** (user decision 2026-10-14):
  - notes come from **what was said only**: the user's microphone ("Me") and the other participants (system
    audio, "Other participants"). No visual analysis: the slide detector and slide images were removed;
  - the screen is still recorded into `recording.mp4`, with the mouse pointer, only so the user can rewatch it;
  - users can also **upload** an existing recording (audio or video; only the sound is used);
  - notes are written by a **local model** only (llama.cpp + Gemma 4 12B): free, offline, no API keys.
    The Claude client written earlier was never committed and is gone;
  - output now: a simple `notes.md` (+ `notes.json`); later Notion.
- Don't rebuild `target/*.jar` while the user runs the app from it: replacing a running Spring Boot jar breaks
  it (likely cause of a "live camera stopped detecting" report on 2026-10-14). Use port 18080 for checks.

## 3. How the pipeline works now

```
Start ─► MeetingRecorder ─► ffmpeg processes (bundled with JavaCV)
           screen: ddagrab (fallback gdigrab), 30 fps, pointer, NVENC ─► video/screen-NNN.mkv
           mic:    dshow, 16 kHz mono PCM                             ─► audio/mic-NNN.mkv
           system: audio-loopback.exe | ffmpeg (f32le pipe → 16 kHz)   ─► audio/system-NNN.mkv
Stop  ─► parts joined: recording.mp4 (screen + mic and system mixed with amix, AAC), audio/mic.wav, audio/system.wav
      ─► MeetingJobService (background, one at a time, resumed after restart)
           0. ObjectDetectorProvider.unloadIfUnused (frees YOLO's VRAM)
           1. WhisperCppTranscriber (both tracks, one whisper-server) ─► EchoFilter ─► transcript.json + .md
           2. free VRAM check (llama-server --list-devices) ─► LlamaServer + LocalNotesGenerator
              ─► notes.json + notes.md (MarkdownExporter)
           status RECORDED → PROCESSING (step + percent) → READY / FAILED
```

### System sound (stage 5)
- `native/audio-loopback/AudioLoopback.cs`: WASAPI loopback of the default output device via raw COM interop,
  float32 PCM to stdout at the device format (`--info` prints `<rate> <channels> <name>`); silence is written by
  the clock when Windows sends nothing (nothing playing). Built by `packaging/build-loopback.ps1` with
  `%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe` (C# 5: no string interpolation, no `?.`) into
  `tools/audio-loopback/` (gitignored); `get-models.ps1` runs it too.
- `CaptureProcess.startPiped` runs helper | ffmpeg with `ProcessBuilder.startPipeline`; stop = destroy the helper,
  ffmpeg sees the end of input and closes the last part. Both PIDs go to `meeting.json`; crash recovery kills
  `ffmpeg*` and `audio-loopback*` processes.
- If the helper is missing or fails, the meeting records without the system sound and gets a warning.
- `EchoFilter`: a mic segment is dropped when ≥ 60% of its words are on the system track within ±2 s. Verified on
  2026-10-14 with a Bluetooth speaker and no headphones: 3 echoed mic segments removed, the phrase kept once as
  "Other participants".
- `WhisperCppTranscriber.isSoundLabel` drops "*music playing*", "[Music]", "(applause)", music notes.
- Free VRAM: `MeetingJobService.notesMemoryProblem` needs model file size + 1 GB; otherwise a warning tells the
  user how much is free/needed and to press "Process again".

- Meeting folder: `%USERPROFILE%\Documents\VisionTrack\Meetings\YYYY-MM-DD_HH-MM_<title>\`
  with `meeting.json` (the only record; `MeetingStore` scans folders; unknown old fields like `slideCount` are
  ignored), `recording.mp4`, `audio/mic.wav`, `transcript.*`, `notes.*`, `logs/` (ffmpeg, whisper-server and
  llama-server logs, no transcript text).
- Crash safety: captures write 5-minute Matroska parts; ffmpeg PIDs are kept in `meeting.json` and
  orphaned captures are killed and joined on the next start (`MeetingRecorder.recoverInterrupted`).
- REST (`MeetingController`, loopback requests only): `GET /api/meetings/devices`, `POST /start`,
  `POST /{id}/stop`, `GET /`, `GET /{id}`, `POST /{id}/process`, `GET /{id}/notes`, `GET /{id}/transcript`,
  `GET /{id}/recording`, `POST /{id}/open-folder`.
- UI: "Meetings" tab in `static/index.html` (third `<script>` block): devices, consent prompt before the
  first recording (localStorage), REC timer, list with progress, links Notes / Transcript / Recording,
  "Process again". Tray icon gets a red dot while recording (`BrowserLauncher`).
- Config: `visiontrack.meetings.*` in `application.yml` / `VisionTrackProperties.Meetings`
  (folder, fps, segment length, `whisper.*`, `notes.*`).
- Programs and models: `packaging/get-models.ps1` installs `tools/whisper`, `tools/llama` and
  `models/whisper`, `models/llm` (all gitignored, ~10 GB). Tested on 2026-10-14, including a clean llama install.

### Speech recognition (stage 3)
- whisper.cpp `b5454` (CUDA 12.4 build): Silero VAD (`whisper-vad-speech-segments.exe`, on the CPU) →
  `SpeechChunks.plan` (≤ 30 s pieces) → `whisper-server` per piece with `language=auto`; a language outside
  `[ru, en]` is redone with the likeliest allowed one. One hour of speech in ~1 min on the RTX 5070.

### Notes (stage 4)
- llama.cpp `b11624` (`llama-b11624-bin-win-cuda-12.4-x64.zip` + cudart zip), `llama-server` started per meeting
  with `-c 16384 -np 1 -ngl all -fa on --reasoning off --no-webui`; CUDA checked first with `--list-devices`
  (the server log does not say where layers went).
- Model `models/llm/gemma-4-12b-it-qat-q4_0.gguf` (from `google/gemma-4-12B-it-qat-q4_0-gguf`, Apache 2.0).
  Compared on 2026-10-14 with Qwen3 14B Q4_K_M on a Russian/English test meeting (see spec 5.4): Gemma was
  correct and concise; Qwen invented due dates and listed every remark. Qwen was deleted.
- Request: `POST /v1/chat/completions` with `response_format: {type: json_schema, json_schema: {schema}}`
  (grammar-constrained), temperature 0.2. The request names the notes language (`NotesPrompt.mainLanguage`, by
  amount of text per Whisper language), because Gemma wrote English notes for a Russian meeting when asked to
  pick the language itself.
- `LocalNotesGenerator`: `NotesChunker` 10-minute parts; a part that does not fit the context (counted with
  `/tokenize`) is halved; partial notes merged, hierarchically if they don't fit together.
- Measured: a 3-minute meeting ~15 s; Whisper + notes for a 42 s Russian clip 22 s, peak VRAM 8.5 GB of 12
  (about 3.1 GB of it used by Windows and other programs).

### Upload and screen rate (stage 6)
- `MeetingImporter` (`POST /api/meetings/import`) saves the file as `upload/source.<ext>` in a new meeting folder
  with one track `upload` and queues it; `MeetingJobService.prepareUpload` runs `RecordingImport.prepare` first
  (step "Preparing the recording"): `audio/mixed.wav` for Whisper, `recording.mp4` (H.264 copied, else NVENC) or
  `recording.<ext>` for audio (track kind `audio`). `Meeting.playback()` is what `/recording` serves.
- Transcript source `mixed` → speaker "Participants"; `LocalNotesGenerator.withoutUnknownMe` removes "Me" owners
  when the recorder's microphone is not in the transcript.
- Checked on 2026-10-14 in the running app: Russian TTS as mp3 and English as VP9/Opus webm → READY with transcript
  and notes; a .txt is refused with 400.
- Screen recording at 30 fps (`screen-fps: 30`), NVENC constant quality (`-rc vbr -cq 28 -b:v 0 -maxrate 8M`,
  keyframe every 2 s): ~0.4-0.6 GB per hour on a mostly still 2560x1440 screen, up to ~3.6 GB with constant motion.
  Measured 30.0 fps in the running app.

## 4. Stage 7: what to build next (Notion export)

Plan to propose to the user:
- Before coding: check the current Notion API version and objects (`Notion-Version` header, databases vs data
  sources, file uploads), e.g. via WebFetch of developers.notion.com.
- Settings: the Notion integration token and the target page/database in `%LOCALAPPDATA%\VisionTrack\settings.json`
  (stage 8 adds the UI; until then edited by hand, like the Claude key was planned). Never in jar, git or logs.
- `NotionExporter implements NotesExporter`: "Meetings" database row (Name, Date, Duration, Project, Tags,
  Participants, Type) + page body Summary → Decisions → Action items (to-do blocks) → Timeline → Transcript
  (toggle); limits ≤ 100 blocks per request, ≤ 2000 chars per rich text, ~3 req/s with retries.
- Export status in `meeting.json`, a "Send to Notion" / "Retry" button; the local notes stay the source of truth.
- Tests: block building and splitting by limits against a stub HTTP server.

## 5. Later stages: reminders
- **7 Notion** details: "Create databases" button; optional "Tasks" database with relation to the meeting.
- **8 Settings**: `%LOCALAPPDATA%\VisionTrack\settings.json`, devices with level test, Notion token,
  delete audio after processing.
- **9 build for a friend**: add `tools/` and `models/` to `packaging/build-desktop.ps1` (the jpackage app needs
  the `visiontrack.meetings.whisper.*` and `notes.*` paths pointing into `$APPDIR`), drop Linux natives from
  the jar, 7z archive, update `packaging/README.txt` (NVIDIA driver 528+, 12 GB VRAM recommended, consent).

## 6. Known gaps and risks
- Work still done on the CPU:
  - Silero VAD (`WhisperCppTranscriber.detectSpeech`): with `-ug` whisper.cpp b5454 (the newest Windows CUDA
    build as of 2026-10-14) aborts with "pre-allocated tensor (leaf_0) in a buffer (CUDA0) that cannot run the
    operation". It takes seconds per hour of audio. GPU option: Silero ONNX on ONNX Runtime CUDA;
  - AAC audio encoding in `Ffmpeg.mux` (no GPU audio encoder exists); gdigrab fallback capture itself;
  - colour conversion of annotated video frames (`VideoWriter`), video decoding in `VideoReader`.
- Without CUDA but with NVENC (e.g. `CUDA_VISIBLE_DEVICES=-1`) screen recording still works: NVENC takes
  ddagrab frames through D3D11. On a PC without NVIDIA ffmpeg fails in `h264_nvenc`, and `MeetingRecorder`
  turns that into "Screen recording needs an NVIDIA graphics card ..." (not testable on this PC).
- If a video analysis runs while a meeting is processed, YOLO cannot be unloaded; the free VRAM check then skips
  the notes with a clear warning ("Process again" later).
- Notes quality: Gemma sometimes turns vague deadlines ("by the end of next week") into a date, and once missed
  an action item that another run found. "We decide X today" was listed as a decision until the prompt said a
  plan to decide later is not one. The notes footer asks the user to check names and dates.
- Speech and notes were verified with Windows TTS voices, a speaker playing them and a hand-written transcript
  only; ask the user for a real call (Zoom/Teams) in Russian and English.
- System sound follows the default output device at the start of the recording; switching headphones/speakers
  during a call is not followed (the helper keeps the old device, possibly silent).
- Only the device's own format is supported if it is 32-bit float or 16-bit PCM (the usual Windows mix formats);
  anything else makes the helper exit with "unsupported output format" (the meeting records without it).
- Track offsets come from process start times; the screen starts ~1 s later than the mic. Fine for watching.
- `.idea/vcs.xml` is untracked on purpose (created by IntelliJ); leave it out of commits.

## 7. Dev environment notes (this PC)
- Windows 11, Java 21, RTX 5070 (sm_120, 12 GB, driver 610). The CUDA 12.4 builds of whisper.cpp and llama.cpp
  run via PTX JIT (first start slower, then cached). ONNX Runtime YOLO also runs on CUDA.
- Maven is not on PATH: `"/c/Program Files/JetBrains/IntelliJ IDEA 2026.2.0.1/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"`.
- Run for checks: `java -jar target/visiontrack-object-detection-aws-1.0-SNAPSHOT.jar --server.port=18080
  --visiontrack.open-browser=false` (port 8080 may be the user's own run). Stop it by the PID on the port.
- No-GPU test: start the app with `CUDA_VISIBLE_DEVICES=-1`; expect the "NVIDIA graphics card" errors.
- Test speech: Windows voices "Microsoft Irina Desktop" (ru) and "Microsoft David Desktop" (en) via
  `System.Speech`; build a 16 kHz mono WAV with the bundled ffmpeg.
- A meeting can be processed without recording: create a folder with `meeting.json` (status `RECORDED`) and
  `audio/mic.wav`; or, to test only the notes, a `transcript.json` and a mic track whose file is missing.
  The app picks it up on start (`MeetingJobService.resumeUnfinished`).
- Compare notes models: `--visiontrack.meetings.notes.model=models/llm/<file>.gguf`.
- Always delete test meetings in `Documents\VisionTrack` afterwards; recordings capture the user's real screen.
- In the Bash tool, Python patch scripts with Java text blocks or `\n` inside heredocs break easily; prefer the
  Edit tool for those.
