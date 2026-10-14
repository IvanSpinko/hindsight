# Meeting notes: handoff for the next session

Status as of 2026-10-14. Read this together with `docs/meeting-notes-spec.md` (the spec, stages 0–8).
Goal for the next session: **stage 4 (notes via Claude + Markdown)**, then stages 5–8.

## 1. Where things stand

| Stage | Status | Commit |
|---|---|---|
| 0 git init, tag `v1.0-video` | done | `2297088` (`main`) |
| 1 record screen + microphone | done | `fd9081a` |
| 2 slide detector, one `recording.mp4` | done | `62e1999` |
| 3 Whisper on GPU, Russian + English | done | `65387dc` |
| 4 notes via Claude + Markdown | **next** | |
| 5 Notion export | todo | |
| 6 system audio (WASAPI loopback) | todo | |
| 7 Settings tab | todo | |
| 8 build for a friend | todo | |

- Repository: https://github.com/IvanSpinko/visiontrack-object-detection-aws, remote `origin`.
- Work happens on branch `meeting-notes`; `main` and tag `v1.0-video` are the video-analysis release.
  Merge `meeting-notes` into `main` (pull request) when the user asks.
- Tests: 45, all green (`mvn package`).

## 2. Working agreement with the user

- The user writes in Russian; answer in Russian.
- **Every file in the repo is English-only**: code, comments, UI, config, tests, docs. No Cyrillic at all.
  Before each commit: `git diff --cached --name-only --diff-filter=d` and check for characters in the Cyrillic range U+0400 to U+04FF.
  Test data uses English text even where the feature is about Russian speech.
- **Single author on GitHub**: no `Co-Authored-By` trailer and no "Generated with Claude Code" line in
  commits or pull requests. The history was rewritten once to remove them; do not add them back.
- One stage at a time: short plan per file first → code → `mvn package` with tests → acceptance
  check in the running app → commit → `git push`.
- GPU (CUDA) must keep working.
- **Everything on the GPU, no CPU processing** (user request, 2026-10-14). This overrides the spec's
  "automatic fallback to CPU" (sections 3 and 5.3). **Decided for now:** without CUDA the app shows a
  clear error that an NVIDIA GPU is required (driver hint included) and does not fall back to the CPU.
  Not implemented yet: do it first in the next session (see "Next task" below). CPU work list in section 6.

### Next task (before stage 4): GPU only, clear error without CUDA
- `WhisperCppTranscriber.startServer`: drop the CPU fallback (`device`, `cpu-model`); if the server does not
  report CUDA, fail the step with a message like "Speech recognition needs an NVIDIA graphics card with
  CUDA (driver 528 or newer). No compatible GPU was found." Show it in the meeting's error in the UI.
- VAD on the GPU: add `-ug` to `whisper-vad-speech-segments`.
- YOLO (`OnnxYoloDetector`): backends `[CUDA]` only; without CUDA the video analysis and live camera show
  the same clear error instead of running on the CPU. Keep the app itself starting, so meetings still record.
- Screen encoding: `h264_nvenc` instead of `libopenh264` (check it on the RTX 5070; NVENC is always present
  on NVIDIA GPUs that CUDA supports).
- Update tests, `application.yml`, `README.md`, `packaging/README.txt` (NVIDIA GPU is now required) and the
  spec (sections 3, 5.3, 8).
- Test without CUDA by starting the app with `CUDA_VISIBLE_DEVICES=-1`: expect the clear error, no CPU work.
- The user wants the result to feel like **one recording**: record → stop → AI extracts the key
  information → Notion. Separate audio/video tracks are an internal detail.
- Screen recording is required: important information is often shown on slides (often in English).

## 3. How the pipeline works now

```
Start ─► MeetingRecorder ─► ffmpeg processes (bundled with JavaCV)
           screen: ddagrab (fallback gdigrab), 1 fps, no mouse pointer ─► video/screen-NNN.mkv
           mic:    dshow, 16 kHz mono PCM                              ─► audio/mic-NNN.mkv
Stop  ─► parts joined: recording.mp4 (screen + AAC sound) and audio/mic.wav
      ─► MeetingJobService (background, one at a time, resumed after restart)
           1. SlideExtractor + SlideChangeDetector ─► slides/slide-NN.png + slides.json
           2. WhisperCppTranscriber               ─► transcript.json + transcript.md
           status RECORDED → PROCESSING (step + percent) → READY / FAILED
```

- Meeting folder: `%USERPROFILE%\Documents\VisionTrack\Meetings\YYYY-MM-DD_HH-MM_<title>\`
  with `meeting.json` (the only record; `MeetingStore` scans folders), `recording.mp4`, `audio/mic.wav`,
  `slides/`, `slides.json`, `transcript.*`, `logs/` (ffmpeg and whisper-server logs, no transcript text).
- Crash safety: captures write 5-minute Matroska parts; ffmpeg PIDs are kept in `meeting.json` and
  orphaned captures are killed and joined on the next start (`MeetingRecorder.recoverInterrupted`).
- REST (`MeetingController`, loopback requests only): `GET /api/meetings/devices`, `POST /start`,
  `POST /{id}/stop`, `GET /`, `GET /{id}`, `POST /{id}/process`, `GET /{id}/slides`,
  `GET /{id}/slides/slide-NN.png`, `GET /{id}/transcript`, `POST /{id}/open-folder`.
- UI: "Meetings" tab in `static/index.html` (third `<script>` block): devices, consent prompt before the
  first recording (localStorage), REC timer, list with progress, slide thumbnails, transcript link,
  "Process again". Tray icon gets a red dot while recording (`BrowserLauncher`).
- Config: `visiontrack.meetings.*` in `application.yml` / `VisionTrackProperties.Meetings`
  (folder, fps, segment length, slide thresholds, `whisper.*`).

### Speech recognition details (stage 3, option A chosen)
- whisper.cpp build `b5454` (`whisper-bin-win-cuda-12.4.0-x64.zip`), installed by
  `packaging/get-whisper.ps1` into `tools/whisper` (exe + DLLs) and `models/whisper`
  (`ggml-large-v3-turbo-q8_0.bin` for CUDA, `ggml-small-q8_0.bin` for CPU, `ggml-silero-v5.1.2.bin` VAD).
  Both folders are gitignored. Without them processing skips the transcript with a warning.
- Silero VAD (`whisper-vad-speech-segments.exe`) → `SpeechChunks.plan` (≤ 30 s pieces; a pause ≥ 2 s always
  splits, ≥ 0.8 s splits after 10 s) → `whisper-server` per piece with `language=auto`; if the detected
  language is not in `[ru, en]`, the piece is redone with the likeliest allowed one.
- Language names in the server response are words ("english"); ISO codes come from `language_probabilities`.
- Measured on the dev PC: one hour of speech in 56 s on CUDA; peak VRAM 3.8 GB with YOLO loaded.

## 4. Stage 4: what to build next (notes via Claude + Markdown)

Before coding: load the `claude-api` skill and check the current Messages API, structured output /
tool use, image input and the model id (spec default `claude-sonnet-5-5`, configurable).

Plan to propose to the user:
- `NotesGenerator` interface + `ClaudeNotesGenerator` (Spring `RestClient` or the official Anthropic Java SDK;
  avoid heavy dependencies).
- Input: `transcript.json` (segments with times, "Me" for `mic`), slide PNGs from `slides.json`
  (downscale before sending, e.g. ≤ 1568 px long side, to save tokens), meeting title and date.
  Slide text (often English) is read by Claude from the images; no OCR needed.
- Output strictly as JSON following `MeetingNotes` (spec section 6) via structured output / a forced tool;
  validate it (Jackson + checks), unknown values → `null` / `[]`. The prompt must say: never invent facts.
- Long meetings: ~10-minute parts → partial summaries → final notes (spec 5.4).
- Write `notes.json` and `notes.md` (sections: summary, decisions, action items, timeline with slide
  links, open questions) via a `MarkdownExporter` (spec section 7: `NotesExporter`).
- New processing step "Writing notes" in `MeetingJobService`; `Meeting` gets a notes summary field;
  UI link "Notes".
- No API key → skip the step, keep transcript and slides (spec 5.4). The key must never be in the jar,
  git or logs. Until the Settings tab (stage 7) exists, read it from an environment variable
  (e.g. `ANTHROPIC_API_KEY`) or `%LOCALAPPDATA%\VisionTrack\settings.json`; ask the user which.
- Tests (spec 8): JSON parsing/validation, Markdown export, chunking of long transcripts; the HTTP client
  tested against a stub, not the real API.
- Acceptance: `notes.json` valid against the schema, `notes.md` readable; without a key only the
  transcript is saved. Needs a real API key from the user for the live check.

## 5. Later stages: reminders
- **5 Notion**: check the current Notion API version (data sources, file uploads) before coding; limits
  ≤ 100 blocks per request, ≤ 2000 chars per rich text, ~3 req/s; retry button; "Create databases" button.
- **6 system audio**: native WASAPI loopback helper (`audio-loopback.exe`, C++), `system.wav` as a second
  `Transcriber` source "Other participants"; `TranscriptWriter.speaker()` already maps `system`.
  Fallback: "Stereo Mix" dshow device.
- **7 Settings**: `%LOCALAPPDATA%\VisionTrack\settings.json`, devices with level test, keys, Whisper model,
  delete audio after processing, show CUDA/CPU.
- **8 build for a friend**: add `tools/whisper` + `models/whisper` to `packaging/build-desktop.ps1`
  (the jpackage app needs `visiontrack.meetings.whisper.*` paths pointing into `$APPDIR`), drop Linux natives
  from the jar, 7z archive, update `packaging/README.txt` (NVIDIA driver 528+, recording consent, Notion).
  Also decide on the known gap below.

## 6. Known gaps and risks
- Work still done on the CPU, to move to the GPU (see section 2):
  - Silero VAD: `whisper-vad-speech-segments` runs without `-ug` (`WhisperCppTranscriber.detectSpeech`);
  - Whisper CPU fallback: `whisper.device: auto` + `cpu-model` (small) in `WhisperCppTranscriber.startServer`;
  - YOLO CPU backend: `visiontrack.model.backends: [CUDA, CPU]`;
  - screen encoding with `libopenh264` (`ScreenCapture`) and `Ffmpeg.concat` — candidate: `h264_nvenc`
    (present in the bundled ffmpeg); AAC encoding in `Ffmpeg.mux`;
  - slide thumbnails and comparison in Java (`SlideChangeDetector`, `SlideExtractor`), video decoding in
    `VideoReader` (candidate: CUDA hardware decoding).
- YOLO (`OnnxYoloDetector` bean) stays loaded on the GPU while Whisper runs; the spec says not both.
  Fine on 12 GB; on a weak GPU Whisper falls back to the CPU. Consider lazy loading / unloading YOLO.
- Track offsets come from process start times; the screen starts ~1 s later than the mic
  (e.g. 317.0 s vs 317.85 s). Good enough for slides vs transcript; refine if lip-sync matters.
- Slide detection is verified on generated presentations only; the user still has to try a real one.
  Thresholds: `slide-stable-seconds`, `slide-min-changed-share`.
- Speech was verified with Windows TTS voices only; ask the user for a real bilingual recording.
- `.idea/vcs.xml` is untracked on purpose (created by IntelliJ); leave it out of commits.

## 7. Dev environment notes (this PC)
- Windows 11, Java 21, RTX 5070 (sm_120, 12 GB, driver 610). whisper.cpp's CUDA 12.4 build runs via PTX JIT
  (first start ~25 s, then cached). ONNX Runtime YOLO also runs on CUDA.
- Maven is not on PATH: `"/c/Program Files/JetBrains/IntelliJ IDEA 2026.2.0.1/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"`.
- Run for checks: `java -jar target/visiontrack-object-detection-aws-1.0-SNAPSHOT.jar --server.port=18080
  --visiontrack.open-browser=false` (port 8080 may be busy). Stop it by the PID listening on the port.
- CPU fallback test: start the app with `CUDA_VISIBLE_DEVICES=-1`.
- Test speech: Windows voices "Microsoft Irina Desktop" (ru) and "Microsoft David Desktop" (en) via
  `System.Speech`; build a 16 kHz mono WAV with the bundled ffmpeg.
- A meeting can be processed without recording: create a folder with `meeting.json` (status `RECORDED`)
  and `audio/mic.wav`; the app picks it up on start (`MeetingJobService.resumeUnfinished`).
- Always delete test meetings in `Documents\VisionTrack` afterwards; recordings capture the user's real screen.
- In the Bash tool, `cat <<'EOF'` heredocs containing quotes sometimes break; writing a Python patch script
  to the scratchpad with the Write tool and running it is reliable.
