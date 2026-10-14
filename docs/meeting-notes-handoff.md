# Handoff: start here in the next session

Written 2026-10-14 at commit `5a28de7` (branch `meeting-notes`). The user paused the work after stage 6 and asked
for this note. **How everything works is in `docs/project-report.md`** (read sections 7, 13 and 15 at least);
the requirements are in `docs/meeting-notes-spec.md` (version 2.0). This file is only: what to do first, the
rules agreed with the user, the next tasks with concrete steps, and the traps met so far.

## 0. First five minutes
1. `git status` / `git log --oneline -5` on `meeting-notes`; expect `5a28de7` or later and only `.idea/vcs.xml`
   untracked (leave it out of commits).
2. Ask the user what to continue with; the default plan is section 3 (fixes first, then stage 7 Notion).
3. Check the tools are still installed: `tools/whisper`, `tools/llama`, `tools/audio-loopback`, `models/whisper`,
   `models/llm` (all gitignored). Missing → `powershell -ExecutionPolicy Bypass -File packaging\get-models.ps1`.
4. Build with `"/c/Program Files/JetBrains/IntelliJ IDEA 2026.2.0.1/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" -q package`
   (84 tests, all green at `5a28de7`; they need the GPU).
5. Before rebuilding, make sure the user is not running the app from `target/*.jar` (see section 4).

## 1. Status

| Stage | Status | Commit |
|---|---|---|
| 0 git, tag `v1.0-video` (video analysis release on `main`) | done | `2297088` |
| 1 record screen + microphone | done | `fd9081a` |
| 2 one `recording.mp4` (its slide detector was removed in 4) | done | `62e1999` |
| 3 Whisper on CUDA, Russian + English | done | `65387dc` |
| GPU only, clear "NVIDIA GPU required" error | done | `62a7800` |
| 4 notes from speech with a local model (Gemma 4 12B) | done | `dfe31db` |
| 5 system sound (WASAPI loopback), echo filter, free VRAM check | done | `a141c08` |
| 6 upload existing recordings, screen at 30 fps | done | `e188937` |
| full project report | done | `5a28de7` |
| 7 Notion export | next | |
| 8 Settings tab | todo | |
| 9 build for a friend | todo | |

Repository https://github.com/IvanSpinko/visiontrack-object-detection-aws (remote `origin`). Merge
`meeting-notes` into `main` only when the user asks (pull request).

## 2. Rules agreed with the user (keep them)
- Answer in **Russian**; every file in the repo in **English**, no Cyrillic. Before each commit check the staged
  files for U+0400-U+04FF and for control characters (see section 4, Python escaping).
- **No attribution** in commits or PRs: no `Co-Authored-By`, no "Generated with Claude Code" (the user rewrote
  history once to remove them).
- One stage at a time: short plan per file → code → `mvn package` → acceptance in the running app → commit → push.
- **GPU only**, no CPU fallbacks in new code. Without CUDA: a clear "... needs an NVIDIA graphics card with CUDA
  (driver 528 or newer)" message (`GpuRequiredException`).
- **Video memory is shared by several models (user's explicit concern):** keep them strictly sequential.
  Meeting processing unloads YOLO, runs Whisper (~0.9 GB) and stops it, checks free VRAM, then runs Gemma
  (~7.7 GB) and stops it. Any new model (e.g. diarization, embeddings) must follow the same pattern: own process
  or provider, started for one step, stopped after, free-VRAM check before a big load.
- **Scope 2.0:** notes come from speech only (microphone "Me" + system sound "Other participants", or one mixed
  track "Participants" for uploads). The screen is recorded (30 fps, with the pointer) only for people to rewatch;
  no screen analysis. Local models only: no paid APIs, no API keys. Notes as `notes.md` + `notes.json` now,
  Notion later.

## 3. Next tasks, in the order I would propose them

### 3.1 Quick fixes (one commit, ~1 hour)
- **JVM crash on every shutdown.** 14 `hs_err_pid*.log` in the project root: `OrtSession.close()` crashes in
  `onnxruntime.dll` from the Spring shutdown hook (`ObjectDetectorProvider.close`). Try: skip closing the CUDA
  session during JVM shutdown (let the process exit), keep `unloadIfUnused` closing it at runtime (that path works).
  Verify by starting and stopping the app several times; then delete the old `hs_err_pid*.log` files.
- **Bind the default profile to 127.0.0.1** (`server.address`), so video analysis and the live camera are not
  reachable from the LAN; the desktop profile already does this.
- **Docker/AWS path is outdated** (CPU inference was removed): ask the user whether to drop it from README/Dockerfile
  or move the image to an NVIDIA CUDA base with `--gpus all`. Also remove the `-CpuOnly` switch from
  `packaging/build-desktop.ps1`.
- `packaging/README.txt` still says "the first start takes 10-30 seconds while the model loads"; YOLO now loads on
  first use.

### 3.2 Stage 7: Notion export
- First check the current Notion API (WebFetch developers.notion.com): `Notion-Version` header value, databases vs
  data sources, block and rich-text limits, rate limits, file uploads.
- Settings: `%LOCALAPPDATA%\VisionTrack\settings.json` with the integration token and the target database or page
  (edited by hand until stage 8). Never log or commit it; `settings.json` is already in `.gitignore`. A small
  `SettingsService` (an earlier version for the Claude key was written and deleted; same idea: read on each use,
  `@JsonIgnoreProperties(ignoreUnknown = true)`, log no content on parse errors, `@Autowired` on the public
  constructor if there are two).
- `NotionExporter implements NotesExporter` (`NotesExporter` and `MarkdownExporter` exist): database row (Name,
  Date, Duration, Type, Project, Tags, Participants, export status) + page body: Summary → Decisions → Action items
  as to-do blocks (owner, due) → Timeline → Transcript in a toggle. Split ≤ 100 blocks per request, ≤ 2000
  characters per rich text; ~3 requests/s with retry and backoff; HTTP with Spring `RestClient` (no new heavy deps).
- `Meeting` gets an export status (and the Notion page URL); UI: "Send to Notion" / "Retry", link to the page;
  optional automatic export after processing. A "Create databases" button and an optional "Tasks" database with a
  relation to the meeting are nice-to-have.
- Tests: block building and limit splitting, request sequence against a stub HTTP server
  (`com.sun.net.httpserver.HttpServer` worked well for the deleted Claude client test).
- Acceptance needs the user's Notion integration token and a database shared with the integration.

### 3.3 Stage 8: Settings tab
Devices with level meters (mic, system sound) and the default monitor; Notion token and target; notes language
(auto / Russian / English, today auto from the transcript); screen fps/quality; "delete audio after processing";
meetings folder; show GPU name, free VRAM, installed models; edit a meeting's title and date (uploads get the
upload time as their date).

### 3.4 Stage 9: build for a friend
`build-desktop.ps1`: include `tools\` and `models\` (or download on first start with progress), set
`visiontrack.meetings.whisper.*`, `notes.*`, `system-audio-helper` to `$APPDIR` paths, drop Linux natives from the
jar, 7z archive, one shared copy of the CUDA DLLs (today duplicated in `tools\whisper`, `tools\llama`, `app\cuda`).
Update `packaging/README.txt`: NVIDIA driver 528+, 12 GB VRAM recommended, consent, upload, where meetings live.
Mention the AGPL-3.0 licence of the Ultralytics YOLO weights to the user before anything is shared publicly.

### 3.5 Worth doing whenever there is a chance
- Ask the user for a real call (Zoom/Teams/Telegram, Russian + English, with and without headphones); tune
  `EchoFilter` (≥ 60% words within ±2 s) and the notes prompt on it.
- A small evaluation set for notes (3-5 transcripts with expected decisions/tasks/dates) to compare prompt or
  model changes by numbers.
- Later: speaker separation, following output-device changes during a call, Silero VAD on CUDA via ONNX Runtime,
  search across meetings, merge to `main` with a release, demo video and benchmarks in the README.

## 4. Traps met so far
- **Python patch scripts in heredocs mangle backslashes**: `\a` became a bell character (twice), `\b` a
  backspace, Java regex `\\s` turned into an invalid `\s`, a Java text-block line ending in `\` did not match.
  Use the Edit tool for anything with backslashes; after any scripted edit scan for control characters:
  `python -c "import sys; ..."` counting bytes < 32 other than tab/LF/CR.
- `Path.write_text` on Windows writes CRLF; files in this repo are LF in the working copy (except `*.ps1`, CRLF by
  `.gitattributes`). Write bytes, or fix with `sed -i 's/\r$//'`.
- **Never rebuild `target/*.jar` while the user's app runs from it** (a replaced Spring Boot jar breaks the running
  app). Run checks on port 18080; the user's own run is on 8080 (often from IntelliJ). Stop test runs by the PID on
  18080 (`netstat -ano | grep ":18080 .*LISTENING"`).
- **Always delete test meetings** in `Documents\VisionTrack\Meetings` afterwards; a recording captures the user's
  real screen and microphone. Playing test speech uses the user's Bluetooth speaker (JBL Charge 5): tell them.
- A meeting can be processed without recording: a folder with `meeting.json` (`"status":"RECORDED"`) and
  `audio/mic.wav`; to test only the notes, a `transcript.json` and a mic track whose file does not exist. The app
  picks it up on start. `meeting.json` written by the app is pretty-printed (`"status" : "READY"`), so regex resets
  need spaces; rewriting it with Python's `json` is safer. "Process again" = `POST /api/meetings/{id}/process`.
- Test speech: Windows voices "Microsoft Irina Desktop" (ru), "Microsoft David Desktop" (en) via `System.Speech`
  in PowerShell; write the text to a UTF-8 file first; convert with the bundled ffmpeg
  (`~/.javacpp/cache/ffmpeg-7.1-1.5.11-windows-x86_64.jar/org/bytedeco/ffmpeg/windows-x86_64/ffmpeg.exe`).
- whisper.cpp `-ug` for the VAD crashes (CUDA); llama-server does not write `CUDA0` to its log in this build — use
  `--list-devices` (it also prints free MiB). Gemma writes English when asked to "use the meeting's language":
  name the language explicitly.
- NVENC needs `-bf 0` (otherwise the first timestamps of a part shift, and it refuses short GOPs) and
  `-forced-idr 1` (otherwise the segment muxer cannot cut parts).
- `AnthropicClient`-style SDKs, Kotlin stdlib etc. were removed with the Claude client; keep dependencies minimal.
- No-GPU check: start the app with `CUDA_VISIBLE_DEVICES=-1` (NVENC still works then; a PC without NVIDIA cannot
  be simulated here).
