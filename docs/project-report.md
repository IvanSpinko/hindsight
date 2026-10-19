# Hindsight (VisionTrack): full project report

State as of 2026-10-19, `main` after pull request #15 (`c115b37`). This document describes everything the project
does today, how it does it, what it depends on, what is known to be missing or broken, and what comes next.
Companion documents: `README.md` (plain overview), `docs/api.md` (REST API), `docs/cloud-pipeline.md` (AWS),
`docs/meeting-notes-spec.md` (requirements of the meetings module, version 2.0).

The app is called Hindsight for users; the code, the `visiontrack.*` settings and the data folders keep the
original name VisionTrack.

---

## 1. What VisionTrack is

A local Windows application (Java 21 + Spring Boot, opened in the browser or as a desktop app with a tray icon)
with three modes and a Settings tab, shown as tabs on one page:

| Mode | What the user does | What comes out |
|---|---|---|
| **Video analysis** | uploads a video | which objects appear (99 classes), how many of each, when each was first and last seen, plus a copy of the video with boxes drawn in |
| **Live camera** | turns on a webcam in the browser | objects recognised live, with boxes over the picture |
| **Meetings** | records an online meeting (screen, own microphone, the other participants) or uploads an existing recording | a transcript and structured notes: summary, decisions, action items with owners and due dates, topics with times, open questions; optional export to Notion |
| **Settings** | chooses folders, devices, recording quality, notes language, Notion | settings saved per user, GPU name and free video memory |

Everything heavy runs **on the NVIDIA GPU of the user's PC, offline**: no cloud, no API keys, no accounts.
Without a CUDA-capable NVIDIA GPU the app still starts, but the heavy features answer with a clear error
("... needs an NVIDIA graphics card with CUDA (driver 528 or newer)").

---

## 2. Requirements and environment

### 2.1 To run
- Windows 10/11 x64. Meetings are Windows-only (DirectShow, Desktop Duplication, WASAPI).
- NVIDIA GPU with CUDA and driver 528+; **12 GB VRAM recommended** for meetings (the notes model needs ~7.7 GB
  free). Video analysis alone runs on smaller cards.
- Java 21 (bundled in the desktop build).
- Disk: ~0.5 GB for the YOLO model, ~10 GB for the meeting programs and models (`tools\`, `models\`), plus
  recordings (screen ~0.4-3.6 GB per hour, audio ~115 MB per hour per track).

### 2.2 Development PC (all measurements in this report)
- AMD Ryzen 7 9700X, 32 GB RAM, Windows 11 (build 26100).
- NVIDIA GeForce RTX 5070, 12 GB, compute capability 12.0 (sm_120), driver 610. About 3.1 GB of VRAM is
  permanently used by Windows and other programs.
- Maven is not on PATH; the one bundled with IntelliJ IDEA is used:
  `C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.0.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd`.

---

## 3. Build, install, run

| Task | Command (from the project root) |
|---|---|
| Build + all tests | `mvn package` (136 tests; some need the NVIDIA GPU because NVENC is used) |
| Install the meeting programs and models | `powershell -ExecutionPolicy Bypass -File packaging\get-models.ps1` |
| Build only the system sound helper | `powershell -ExecutionPolicy Bypass -File packaging\build-loopback.ps1` |
| Run | `java -jar target\visiontrack-object-detection-aws-1.0-SNAPSHOT.jar`, open http://localhost:8080 |
| Run on another port, no browser | add `--server.port=18080 --visiontrack.open-browser=false` |
| Desktop app (exe + bundled Java, meeting tools and models) | `powershell -ExecutionPolicy Bypass -File packaging\build-desktop.ps1` → `dist\Hindsight\` and `dist\Hindsight.zip` (~15 GB; `-NoMeetings` for the ~2.6 GB video-only app) |

`get-models.ps1` downloads and installs:
- whisper.cpp build `b5454` (CUDA 12.4) → `tools\whisper` (server, VAD tool, DLLs, ~1.1 GB);
- `ggml-large-v3-turbo-q8_0.bin` and `ggml-silero-v5.1.2.bin` → `models\whisper` (~0.9 GB);
- NVIDIA Parakeet TDT 0.6B v3, ONNX export by istupakov (fp32) → `models\parakeet-tdt-0.6b-v3` (~2.5 GB);
- llama.cpp build `b11624` (CUDA 12.4) → `tools\llama` (server and DLLs, ~1.2 GB);
- `gemma-4-12b-it-qat-q4_0.gguf` → `models\llm` (7.0 GB);
- builds `tools\audio-loopback\audio-loopback.exe` with the C# compiler that ships with Windows.
All of these folders are ignored by git. Without them the app still runs; meetings are recorded, only the
transcript or the notes are skipped with a warning.

Run profiles:
- default (`application.yml`): port 8080, listens on 127.0.0.1, opens the browser.
- `desktop` (`application-desktop.yml`, used by the exe): listens on 127.0.0.1 only, port 47821, tray icon,
  log file `%LOCALAPPDATA%\VisionTrack\visiontrack.log`, uploads, analyses and exports in
  `%LOCALAPPDATA%\VisionTrack\`, optional overrides in `%LOCALAPPDATA%\VisionTrack\application.yml`.
- Starting the app a second time on the same port only opens the browser (the first instance keeps running).
- The browser window is Edge (or Chrome) in "app" mode (`--app=URL`), so it looks like a desktop window.

---

## 4. Architecture

```
Browser page (static/index.html, four tabs, plain JavaScript)
        │ REST/JSON, multipart uploads
Spring Boot 3.5 application (one JVM)
 ├─ web        AnalysisController, LiveDetectionController, MeetingController, SettingsController,
 │             GpuErrorHandler, LocalRequestsOnly, BrowserLauncher (tray)
 ├─ job        AnalysisJobService (queue, 1 worker), FileJobRepository (one JSON file per analysis)
 ├─ analysis   VideoAnalyzer (decode → detect → track → result)
 ├─ detection  ObjectDetectorProvider → OnnxYoloDetector (ONNX Runtime + CUDA), pre/post-processing
 ├─ tracking   ObjectTracker (IoU)
 ├─ video      VideoReader / VideoWriter (FFmpeg via JavaCV)
 ├─ render     AnnotatedVideoRenderer
 ├─ storage    LocalVideoStorage (interface VideoStorage)
 ├─ config     VisionTrackProperties (application.yml, validated), SettingsService (settings.json), GpuStatus
 └─ meeting    recording, import, speech recognition, notes, export (see section 7)
                    │ child processes
       ffmpeg.exe (JavaCV) · audio-loopback.exe · whisper-server.exe · whisper-vad-speech-segments.exe · llama-server.exe
```

Design rules that hold everywhere:
- Heavy native work runs in **separate processes** (ffmpeg, whisper.cpp, llama.cpp) or behind one provider
  (ONNX Runtime). A crash or a stuck process does not take the app down, and the VRAM is freed by stopping it.
- **One job at a time** per area (one video analysis worker, one meeting processing thread), so two models never
  compete for the GPU.
- Folders are the database for meetings (`meeting.json` per folder); video analyses are one JSON file each
  (`data/analyses`, in the desktop app `%LOCALAPPDATA%\VisionTrack\analyses`).
- All project files are English; the UI is English.

---

## 5. Mode 1: video analysis

### 5.1 Flow
```
POST /api/analyses (multipart "file")
  → extension check (mp4, mov, m4v, avi, mkv, webm, wmv, mpg, mpeg; ≤ 2 GB)
  → detector check (503 "needs an NVIDIA graphics card" without CUDA)
  → LocalVideoStorage saves the upload (data/uploads, or %LOCALAPPDATA%\VisionTrack\uploads in the desktop app)
  → job QUEUED (queue of 20, 1 worker; "queue is full" → 503)
  → worker: VideoAnalyzer.analyze (≈75% of the progress bar), then AnnotatedVideoRenderer (≈25%)
  → job COMPLETED with the result, or FAILED with the error text
  → the upload is deleted afterwards (delete-after-processing: true)
GET /api/analyses/{id}        status, progress, result
GET /api/analyses             all jobs, newest first
GET /api/analyses/{id}/video  the annotated video (MP4)
```

### 5.2 Detection
- Model: **YOLO-World X**, exported to ONNX at 960×960 with a fixed vocabulary of **99 classes**
  (`models/classes.txt`: person, bicycle, car, ...). Export scripts: `export_yoloworld.py` (Ultralytics,
  `set_classes`, `imgsz=960`, static shape), `export_model.py` (plain YOLO11x, also in `models/` but unused).
- `ImagePreprocessor`: letterbox (keep aspect ratio, pad), RGB → CHW float tensor.
- `OnnxYoloDetector`: ONNX Runtime GPU 1.22 with the **CUDA execution provider only**; graph optimisation level ALL.
  Native DLLs (VC++ runtime, CUDA 12, cuDNN 9) are pre-loaded from `visiontrack.model.native-preload` /
  `native-dir` so it works without installing the CUDA Toolkit (the desktop build ships them in `app\cuda`).
- `YoloOutputDecoder`: reads both output layouts (`[1, 4+classes, N]` and transposed), maps boxes back to the
  source image, confidence threshold 0.35, per-class NMS at IoU 0.45, at most 300 detections per frame.
- `ObjectDetectorProvider` loads the detector **on first use** (not at startup) and can **unload** it to free
  VRAM; it is never unloaded while a video analysis or a live frame uses it. If it cannot be loaded, the reason
  (no CUDA, missing model) is returned to every caller; the rest of the app keeps working.

### 5.3 Tracking and counting
- Frames are decoded by FFmpeg (`VideoReader`), scaled so the long side is ≤ 1280 px, and **sampled at 5 fps**.
- `ObjectTracker`: greedy IoU matching (best overlaps first, threshold 0.25) per class; a track may be unseen for
  up to 1.5 s and still continue; a track needs 2 hits before it counts (filters one-frame mistakes).
- One track = one counted object. The result has `totalObjects`, `counts` per label with the first time seen,
  and `objects` with first/last seen, visible seconds, best confidence and a box.

### 5.4 Annotated video
- `AnnotatedVideoRenderer` decodes the video again and draws boxes and labels (colour per label, size relative to
  the frame, boxes interpolated between sampled frames and held briefly around sightings).
- `VideoWriter` encodes it with **NVENC H.264** (no B-frames, yuv420p) into MP4.

---

## 6. Mode 2: live camera

- The browser asks for the webcam (`getUserMedia`, 1280×720 ideal, camera selector if there are several).
- One frame at a time: downscaled to ≤ 960 px, JPEG quality 0.8, `POST /api/live/detect`; the next frame is
  sent when the answer arrives, so the rate adapts to the GPU. Boxes and an object list are drawn over the video,
  with fps and milliseconds per frame.
- Without CUDA the server answers 503 and the page stops the camera and shows the reason.
- Nothing is stored; frames exist only for one request.

---

## 7. Mode 3: meetings

### 7.1 Recording
Start (Meetings tab → "Start recording"), with a one-time consent prompt ("make sure everyone agrees to being
recorded", remembered in the browser's localStorage). Devices are found with ffmpeg (DXGI outputs = monitors,
DirectShow audio devices = microphones) and the helper (`audio-loopback.exe --info` = default output device).

Three independent capture processes, each writing **5-minute Matroska parts** (crash-safe: a part already written
stays playable; Matroska survives a killed writer, unlike MP4/WAV):

| Track | Source | Encoding | Parts |
|---|---|---|---|
| `screen` | ffmpeg `ddagrab` (Desktop Duplication API) on the chosen monitor, **30 fps**, mouse pointer drawn; fallback `gdigrab` | **NVENC H.264** straight from video memory, constant quality (`-rc vbr -cq 28 -b:v 0 -maxrate 8M`), no B-frames, IDR keyframe every 2 s and at every part boundary | `video/screen-NNN.mkv` |
| `mic` | ffmpeg `dshow` microphone | 16 kHz mono PCM | `audio/mic-NNN.mkv` |
| `system` | `audio-loopback.exe` (WASAPI loopback of the default output device) piped into ffmpeg | float PCM at the device rate → 16 kHz mono PCM | `audio/system-NNN.mkv` |

- Each track's start time is measured, so all tracks share one timeline (offsets in `meeting.json`).
- A watchdog checks every 2 s; a capture that dies (device unplugged, display mode change) adds a warning.
- Every capture has a safety limit (`-t`, 360 minutes) so an orphan stops by itself.
- The process IDs are stored; after a crash the next start kills leftover `ffmpeg*` / `audio-loopback*`
  processes, joins their parts and marks the meeting "Recovered".
- If the system sound cannot be captured (helper missing, device busy, unsupported format), recording continues
  without it and the meeting shows "The other participants are not recorded: ...".
- A screen that cannot be recorded at all because NVENC is missing (no NVIDIA GPU) gives "Screen recording needs
  an NVIDIA graphics card ...".

Stop → parts are joined losslessly (`ffmpeg -f concat -c copy`) into `video/screen.mp4`, `audio/mic.wav`,
`audio/system.wav`; then `recording.mp4` = screen video (copied) + mic and system sound **mixed** (`amix`,
`normalize=0`, offsets applied, AAC 96 kbit/s). The separate audio tracks stay for speech recognition.
The meeting then goes to processing.

### 7.2 System sound helper (`native/audio-loopback/AudioLoopback.cs`)
- C# 5, compiled with `%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe` (part of Windows), no NuGet, no
  installed SDK. WASAPI is used through hand-written COM interop (IMMDeviceEnumerator, IMMDevice, IPropertyStore,
  IAudioClient, IAudioCaptureClient), shared-mode loopback, 1 s buffer, polled every 10 ms.
- `--info` prints `<rate> <channels> <device name>`; capture mode writes interleaved 32-bit float to stdout
  (16-bit PCM devices are converted) until killed, stdout closes, or `--seconds` pass.
- **Windows sends no loopback data while nothing plays**, so after 0.2 s without data the helper writes silence
  by the clock; the track always covers real time and stays in step with the microphone.
- Stopping = destroying the helper; ffmpeg then sees the end of its input and closes the last part properly.

### 7.3 Uploading an existing recording
- Meetings tab → "Upload a recording" (button or drag & drop, progress shown) or `POST /api/meetings/import`.
- Accepted: audio mp3, m4a, wav, ogg, oga, opus, flac, aac, wma; video mp4, mkv, webm, mov, avi, m4v, wmv;
  up to 2 GB. Anything else → 400 with the list.
- The file is saved as `upload/source.<ext>` in a new meeting folder (title = file name, date = upload time)
  and queued. The first processing step, "Preparing the recording" (`RecordingImport`):
  - ffprobe finds the streams; album covers (mjpeg/png in audio files) are not treated as video;
  - no sound → the meeting fails with "The uploaded file has no sound";
  - the sound → `audio/mixed.wav` (16 kHz mono) for speech recognition;
  - video: H.264 is copied into `recording.mp4`, other codecs (VP9, HEVC, MPEG-4, ...) are re-encoded with NVENC;
    the original upload is deleted; audio: the file is kept as `recording.<ext>` for listening.
- All voices are on one track, so the transcript speaker is "Participants"; a task the model gives to "Me" in
  such a recording gets no owner (nobody is known to be "me").

### 7.4 Processing (one meeting at a time, resumed after a restart)
Status `RECORDED → PROCESSING (step, percent) → READY` or `FAILED`; progress is saved in `meeting.json` in 5%
steps and polled by the page every 2 s.

1. **Preparing the recording** (uploads only, see 7.3).
2. **Free the GPU**: object detection is unloaded unless a video analysis or the live camera is using it.
3. **Recognising speech** with the engine set in `meetings.speech-engine`. **Parakeet** (default,
   `ParakeetTranscriber`): NVIDIA Parakeet TDT 0.6B v3 in ONNX Runtime on CUDA inside the app (`ParakeetModel`:
   `nemo128.onnx` mel features, the encoder, then greedy token-and-duration decoding with `decoder_joint`), loaded
   for the step and freed after. It uses the same Silero VAD and `SpeechChunks` pieces as Whisper, writes its own
   punctuation, and its tokens are cut into one segment per sentence (or at a pause of 1.5 s) with 80 ms timing.
   It does not report a language, so each segment is labelled from its letters (Cyrillic → `ru`, otherwise the
   first other allowed language, `en` by default). Parakeet writes much Russian as Ukrainian, so pieces whose text
   is mostly Cyrillic are handed over: after Parakeet is freed, a `whisper-server` recognises just those pieces
   (`WhisperCppTranscriber.transcribePieces`) and the transcript names both models. Without the Parakeet files
   the step falls back to Whisper. Measured on a 74-minute upload (RTX, CUDA): Parakeet 50 s for everything,
   Whisper turbo 2 min 23 s; on clean English both wrote the same text.
   **Whisper** (`WhisperCppTranscriber`), all speech tracks (`mic`, `system` or `mixed`) with **one**
   `whisper-server` process (model loaded once, ~0.9 GB VRAM, CUDA required):
   - Silero VAD (`whisper-vad-speech-segments.exe`, on the CPU) finds speech; pauses of 0.3 s separate segments;
   - `SpeechChunks` groups them into pieces of ≤ 30 s (a pause ≥ 2 s always splits, ≥ 0.8 s splits after 10 s);
     silence is never sent (avoids Whisper inventing text);
   - each piece is sent with `language=auto`; if Whisper detects a language outside `[en, ru]`, the piece is redone
     with the likeliest allowed language, so a meeting switching between Russian and English is handled per piece;
   - pieces Whisper itself marks as probably not speech (no-speech > 0.6 and log-prob < -1) and sound labels
     ("*music playing*", "[Music]", "(applause)", music notes) are dropped; words split across pieces are joined;
   - segments of all tracks are merged in time order;
   - **echo filter**: without headphones the microphone hears the speakers; a mic segment whose words were ≥ 60%
     said on the system track within ±2 s is dropped, the system copy keeps the right speaker;
   - output `transcript.json` (segments with start/end, source, language, text) and `transcript.md` (paragraphs per
     speaker, `[HH:MM:SS] Me / Other participants / Participants`, language shown when the meeting has several).
   - Measured: one hour of speech in ~1 minute on the RTX 5070.
4. **Writing notes** (`LocalNotesGenerator` + `LlamaServer`):
   - checks: model files present (else a warning, transcript kept); `llama-server --list-devices` shows a CUDA device
     (else "Writing notes needs an NVIDIA graphics card"); **free VRAM ≥ model file size + 1 GB** (else a warning
     with free/needed GB and "close programs that use the graphics card and press Process again");
   - starts `llama-server` with `-c 16384 -np 1 -ngl all -fa on --reasoning off --no-webui` on a random local port,
     waits for `/health` (model load ~25 s the first time);
   - the prompt (`NotesPrompt`): rules (never invent; a decision is something agreed, not a plan to decide later;
     owners and due dates only when said, relative days resolved from the meeting date; misheard words fixed only
     when certain; participants are named people, not speaker labels; short and concrete), the meeting title,
     date with weekday, length, and **the language to write in**, computed from the transcript (models fall back to
     English when asked to choose);
   - the answer is **constrained by a JSON schema** (llama.cpp grammar via `response_format: json_schema`),
     temperature 0.2, ≤ 4096 tokens;
   - long meetings: 10-minute parts, partial notes, then a merge request; a part that does not fit the 16k context
     (counted with `/tokenize`) is halved, and partial notes are merged hierarchically if they don't fit together;
   - `MeetingNotes.cleaned()` drops guesses: unknown type, malformed times/dates, empty or duplicate entries,
     tasks without text, speaker labels as participants;
   - output `notes.json` (the schema below) and `notes.md` (title, date, length, type, project, participants,
     tags, Summary, Decisions, Action items as checkboxes with owner/due/time, Timeline, Open questions, a footer
     with the model name, a reminder to check names and dates, and a link to the transcript);
   - the server is stopped afterwards, freeing its VRAM.
   - Measured: a 3-minute meeting ~15 s; a 42-second Russian clip from audio to notes 22 s; peak VRAM 8.5 GB of 12.
5. **READY**: `meeting.json` gets the transcription summary (model, backend, languages, segments) and the notes
   summary (model, number of decisions and action items). Notes-step problems are warnings (they start with
   "Notes: " and are replaced when processing runs again); only a missing GPU makes the meeting FAILED.

Notes schema (`notes.json`):
```json
{ "title": "...", "type": "call | lecture | interview | other", "project": "...", "tags": [], "participants": [],
  "summary": "3-6 sentences", "decisions": [],
  "actionItems": [{ "task": "...", "owner": "...", "due": "YYYY-MM-DD", "sourceTime": "HH:MM:SS" }],
  "timeline": [{ "time": "HH:MM:SS", "topic": "...", "details": "..." }], "openQuestions": [] }
```

### 7.5 Model choice for the notes (2026-10-14)
Compared on the same Russian/English test meeting:

| | Gemma 4 12B QAT Q4_0 (chosen) | Qwen3 14B Q4_K_M (deleted) |
|---|---|---|
| Time incl. app start | 21 s | 56 s |
| VRAM (of 12 GB, ~3.1 GB used by Windows) | ~7.7 GB | ~8.7 GB (11.8 GB peak in total) |
| Facts and relative dates | correct | invented two due dates, wrong "by Monday" |
| Timeline | 5 topics | 15 entries, one per remark |

Both are Apache 2.0. Before the language was named in the request, Gemma wrote English notes for a Russian meeting.

### 7.6 Meeting folder and record
`Documents\VisionTrack\Meetings\YYYY-MM-DD_HH-MM_<title>\`:
```
meeting.json        the only record (status, times, tracks with offsets and durations, capture PIDs while
                    recording, warnings, error, processing step, transcription and notes summaries)
recording.mp4       screen + mixed sound (or the uploaded video); recording.<ext> for an uploaded audio file
audio\mic.wav, audio\system.wav   (recorded) or audio\mixed.wav (uploaded): speech recognition input
transcript.json/.md
notes.json/.md
logs\               screen.log, mic.log, system.log, whisper.log, llama.log (no transcript text)
```
`MeetingStore` finds meetings by scanning these folders, so the user may move or delete them freely; fields of
older versions (e.g. the removed slide count) are ignored when reading.

### 7.7 Meetings REST API (like every API: requests from this computer only; others get 403)
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/meetings/devices` (`?refresh=true`) | monitors, microphones, output device ("Other participants") |
| POST | `/api/meetings/start` | `{title, monitor, microphone}` |
| POST | `/api/meetings/{id}/stop` | stop, join, mix, start processing |
| POST | `/api/meetings/import` | upload a recording (multipart `file`) |
| GET | `/api/meetings`, `/api/meetings/{id}` | list / one meeting with its folder |
| POST | `/api/meetings/{id}/process` | process again (e.g. after freeing VRAM) |
| GET | `/api/meetings/{id}/notes`, `/transcript` | notes.md / transcript.md as text |
| GET | `/api/meetings/{id}/recording` | the recording to watch or listen to (range requests for seeking) |
| POST | `/api/meetings/{id}/open-folder` | open the folder in Explorer |
| PATCH | `/api/meetings/{id}` | `{title, startedAt}`: rename, change the date (the end moves with it) |
| GET, POST | `/api/meetings/notion`, `/api/meetings/{id}/notion` | Notion status; send the notes |

The whole API, including the settings endpoints, is in `docs/api.md`.

### 7.8 Meetings page
Title, screen, microphone, the output device recorded as "Other participants" (with "headphones recommended"),
Start/Stop with a REC timer (the tray icon gets a red dot while recording), the upload card, and the list of
meetings with status badge, sources, warnings, progress bar with the current step, and for READY meetings the
speech/notes summary with links **Notes**, **Transcript**, **Recording**, plus **Rename or change date**,
**Send to Notion**, **Process again**, **Open folder** and **Delete**.

### 7.9 Settings tab
Saved in `%LOCALAPPDATA%\VisionTrack\settings.json` (never logged, ignored by git) and used from the next recording
or processing on; a value that is not set falls back to `application.yml`.
- Meetings: folder (meetings already recorded stay in the old one), default screen and microphone with a level
  meter (Web Audio in the browser), screen fps (1-60) and quality (NVENC `-cq` 33 / 28 / 23), speech engine,
  notes language (as spoken, or English), deleting the speech WAV files after processing.
- Notion: integration token (write-only), page for the "Meetings" database, automatic sending.
- Graphics card: name, driver, total and free video memory from `nvidia-smi`.

---

## 8. GPU and video memory

| Component | Runs on | VRAM | When |
|---|---|---|---|
| YOLO-World (ONNX Runtime) | CUDA | ~3 GB | video analysis, live camera; loaded on first use, unloaded during meeting processing |
| Screen encoding, annotated video, upload re-encoding | NVENC | small | while recording / rendering / preparing |
| Parakeet TDT 0.6B v3 (ONNX Runtime, fp32) | CUDA | ~3 GB | speech recognition step (default), then freed |
| Whisper large-v3-turbo q8_0 (whisper.cpp) | CUDA | ~0.9 GB | speech recognition step (`speech-engine: whisper`), then stopped |
| Gemma 4 12B (llama.cpp) | CUDA | ~7.7 GB | notes step, then stopped |

The models never run at the same time: YOLO is unloaded, Whisper is stopped before Gemma starts, and the free
memory is checked before Gemma starts. What still runs on the CPU: Silero VAD (whisper.cpp b5454 aborts with
`-ug` on CUDA: "pre-allocated tensor ... cannot run the operation"), AAC audio encoding, video decoding in
`VideoReader`, colour conversion of rendered frames, the gdigrab fallback. Without CUDA: YOLO features answer 503,
meeting processing fails with "needs an NVIDIA graphics card", recording still works if NVENC is available.

---

## 9. Configuration reference (`application.yml`, prefix `visiontrack`)

| Key | Default | Meaning |
|---|---|---|
| `open-browser`, `tray-icon` | true, false (true in desktop) | open the page on start; tray icon |
| `model.path`, `model.classes-path` | `models/yoloworldx-960.onnx`, `models/classes.txt` | detector |
| `model.confidence-threshold`, `model.nms-threshold` | 0.35, 0.45 | detection filtering |
| `model.native-preload`, `model.native-dir` | VC++/CUDA/cuDNN DLL paths | pre-loaded native libraries |
| `analysis.sample-fps` | 5 | analysed frames per second of video |
| `analysis.max-frame-dimension` | 1280 | decode size (long side) |
| `analysis.track-iou-threshold`, `track-max-gap-seconds`, `track-min-hits` | 0.25, 1.5, 2 | tracking |
| `analysis.annotated-video` | true | render the video with boxes |
| `storage.directory`, `delete-after-processing`, `allowed-extensions` | `data/uploads`, true, 9 video types | uploads |
| `jobs.concurrency`, `jobs.queue-capacity` | 1, 20 | analysis queue |
| `meetings.directory` | `~/Documents/VisionTrack/Meetings` | meeting folders |
| `meetings.screen-fps` | 30 | screen frame rate (0.1-60) |
| `meetings.segment-seconds`, `max-recording-minutes` | 300, 360 | part length, safety limit |
| `meetings.ffmpeg-dir` | (bundled) | use another ffmpeg |
| `meetings.system-audio-helper` | `tools/audio-loopback/audio-loopback.exe` | system sound |
| `meetings.speech-engine` | `parakeet` | `parakeet` or `whisper` |
| `meetings.parakeet.directory` | `models/parakeet-tdt-0.6b-v3` | Parakeet ONNX files |
| `meetings.whisper.directory`, `model`, `vad-model`, `languages` | `tools/whisper`, large-v3-turbo q8_0, Silero v5.1.2, `[en, ru]` | speech |
| `meetings.notes.directory`, `model` | `tools/llama`, `models/llm/gemma-4-12b-it-qat-q4_0.gguf` | notes model |
| `meetings.notes.chunk-minutes`, `context-tokens`, `max-tokens` | 10, 16384, 4096 | notes chunking and limits |

Spring settings: multipart up to 2 GB, problem-details error responses, `/actuator/health` and `/actuator/info`.
Every key can be overridden on the command line (`--visiontrack.meetings.screen-fps=15`) or by environment
variables (`VISIONTRACK_MEETINGS_SCREEN_FPS=15`).

---

## 10. Files and folders

Repository:
```
src/main/java/org/example/visiontrack/   application code (packages in section 4)
src/main/resources/                      application.yml, application-desktop.yml, static/index.html, tray icon
src/test/java/                           136 tests (plus 15 pytest tests in infra/lambda/tests)
native/audio-loopback/                   system sound helper source (C#)
packaging/                               build-desktop.ps1, get-models.ps1, build-loopback.ps1, README.txt (for users), icon
docs/                                    API, cloud pipeline, spec and this report
infra/                                   Terraform and the Lambda (Python) of the cloud pipeline
export_yoloworld.py, export_model.py     model export (Python, Ultralytics)
models/classes.txt                       the 99 detection classes
```
Ignored by git: `models/*.onnx`, `models/whisper`, `models/llm`, `models/parakeet-tdt-0.6b-v3`, `tools/`, `weights/`, `data/`, `dist/`,
`target/`, `.venv-model/`, `settings.json`, `.env`, `*.key`, `hs_err_pid*.log`.

Outside the repository at runtime: `Documents\VisionTrack\Meetings\` (meetings), `%LOCALAPPDATA%\VisionTrack\`
(`settings.json`; for the desktop app also the log, uploads, analyses and exports), `~\.javacpp\cache\`
(ffmpeg/ffprobe and natives extracted by JavaCV on first use).

---

## 11. Dependencies and licences

| Component | Version | Licence / note |
|---|---|---|
| Java, Spring Boot (web, validation, actuator) | 21, 3.5.6 | GPL+CE (JDK), Apache 2.0 |
| ONNX Runtime GPU | 1.22.0 | MIT |
| JavaCV / FFmpeg (bundled ffmpeg.exe, ffprobe.exe) | 1.5.11 / 7.1 | Apache 2.0 / LGPL build of bytedeco (NVENC, OpenH264, libvpx, LAME, Opus; no x264) |
| YOLO-World X, YOLO11x weights (Ultralytics) | exported 2026-10-13 | **AGPL-3.0**: sharing the app publicly means sharing its source under AGPL (or an Ultralytics licence) |
| whisper.cpp | b5454 | MIT |
| Whisper large-v3-turbo (ggml q8_0) | | MIT |
| NVIDIA Parakeet TDT 0.6B v3 (ONNX export by istupakov) | | CC-BY-4.0 (credit NVIDIA when sharing) |
| Silero VAD v5.1.2 (ggml) | | MIT |
| llama.cpp | b11624 | MIT |
| Gemma 4 12B instruct QAT Q4_0 | | Apache 2.0 |
| CUDA 12 runtime, cuBLAS, cuDNN 9 (redistributed DLLs) | | NVIDIA redistribution terms |
| audio-loopback (own code) | | uses only Windows APIs |

No dependency was added for the meetings module except native tools run as processes; the cloud AI SDK that was
tried on 2026-10-14 was removed together with the decision to use local models only.

---

## 12. Tests (136, `mvn package`; 15 more for the Lambda with pytest)

| Area | What is checked |
|---|---|
| Detection | IoU, letterbox mapping, NMS, both YOLO output layouts, CHW preprocessing, label fallback |
| Tracking, rendering, analysis | track linking and gaps, box interpolation/holding, colours, time formatting |
| GPU-only | the error message, 503 problem detail, app starts without the detector |
| Capture | ffmpeg arguments (ddagrab/gdigrab, NVENC, mic, system sound), real ffmpeg runs writing 1-second parts and joining them (screen, mic, piped system sound stopped through its source) |
| Speech | VAD output parsing, chunk planning, Whisper response parsing, hallucination and sound-label filtering, word joining, WAV slicing, transcript Markdown |
| System sound | helper answer parsing, mixing arguments, echo filter, llama device parsing, VRAM check message |
| Notes | JSON parsing and cleaning, Markdown export, chunking, the generator against a fake model (parts, merge, context splitting, language, speaker labels, "Me" in uploads, truncated/invalid answers) |
| Upload | real mp3, H.264 mp4, VP9 webm and a silent file made with ffmpeg; album covers; accepted types; speaker names |
| Store | meeting folders, names, reading, renaming and moving the date |
| Parakeet | model files, decoding, punctuation, language by script, Whisper fallback |
| Notion | page blocks and limits, database creation, replacing a page, rejected bodies, retries, settings |
| Settings | saving and clearing meeting choices, keeping unknown keys, token kept or removed, page links, `nvidia-smi` parsing, screen quality arguments |
| Analyses | saved and loaded after a restart, an interrupted one loaded as failed, deleted ones gone, broken files skipped |
| Security | API refuses requests from other computers |

Acceptance was done in the running app for every stage (recording, upload, transcript, notes, VRAM, no-CUDA mode
with `CUDA_VISIBLE_DEVICES=-1`) with Windows TTS voices; a real Zoom/Teams call has not been tested yet.

---

## 13. Known issues and limitations

1. The full desktop build is ~15 GB, mostly the notes model; the CUDA DLLs are stored three times
   (`app\cuda`, `tools\whisper`, `tools\llama`).
2. Remote use is not supported: the server binds to 127.0.0.1 and every API answers requests from this computer
   only (403 otherwise), so the app cannot be shared on a local network.
3. Rebuilding `target\*.jar` while the app runs from it breaks the running app.
4. Silero VAD runs on the CPU (whisper.cpp bug with `-ug`); seconds per hour of audio.
5. System sound follows the default output device chosen at the start; switching speakers/headphones during a call
   is not followed. Devices with formats other than 32-bit float or 16-bit PCM are not supported.
6. No speaker separation inside a track: all other participants are "Other participants", everyone in an upload is
   "Participants". Names come only from what is said.
7. Notes quality limits of a 12B local model: vague deadlines ("by the end of next week") may become a date, an
   action item can be missed on one run and found on another; the notes footer asks to check names and dates.
8. Uploaded recordings get the upload time as their date until it is changed ("Rename or change date…").
   Changing the meetings folder in Settings does not move the meetings already recorded.
9. Track offsets come from process start times; the screen starts ~1 s after the microphone (fine for watching).
10. Disk use: screen up to ~3.6 GB per hour with constant motion (less with lower fps or "Smaller files" in
    Settings); the speech WAV files are kept unless Settings says to delete them.
11. YOLO-World/YOLO11 weights are AGPL-3.0 (Ultralytics): matters if the app is published or shared beyond personal use.
12. Real calls (Zoom, Teams, Telegram) have not been tested yet; only TTS voices, a speaker and hand-written
    transcripts.
13. No continuous integration: tests run locally, and some of them need the NVIDIA GPU.

---

## 14. History

| Commit | What |
|---|---|
| `2297088` (main, tag `v1.0-video`) | video analysis and live camera |
| `fd9081a` | meetings stage 1: record screen and microphone |
| `62e1999` | stage 2: slide detection and one combined recording (slides removed later) |
| `65387dc` | stage 3: Whisper on CUDA, Russian + English |
| `62a7800` | GPU only: clear "NVIDIA GPU required" error, NVENC |
| `dfe31db` | stage 4: notes from speech with a local model (Gemma), slides and the cloud AI client removed |
| `a141c08` | stage 5: system sound, echo filter, free VRAM check |
| `e188937` | stage 6: upload of recordings, screen at 30 fps |
| `777b19e`, `a6dfaee` (PR #1, #2) | AWS data pipeline: export, S3, Lambda, Glue, Athena, Terraform |
| `97800a5` … `f60143a` (PR #3-#10) | meetings merged, UI redesign, desktop cloud export, shutdown crash fix, faster upload preparation |
| `dfafeb1` (PR #11) | stage 7: Notion export; the app is called Hindsight |
| `4ddebbc` (PR #12) | NVIDIA Parakeet as the default speech recognition |
| `61ef207` (PR #13) | comments and README tidied |
| `ef7a8ad` (PR #14) | desktop build ships the meeting tools and models |
| `c115b37` (PR #15) | stage 8: Settings tab, meeting rename, analyses kept across restarts |

---

## 15. What I would do next

Ordered by value for the user; each step follows the usual loop: plan per file → code → tests → check in the
running app → commit → push.

Done since the first version of this plan: stage 7 (Notion export), stage 8 (Settings tab) and most of stage 9
(the desktop build ships everything meetings need).

### Still open from stages 8 and 9
- A level meter for the system sound and a list of the installed models in Settings.
- A smaller download: remove Linux natives from the jar, one shared copy of the CUDA DLLs (~1.5 GB), a 7z archive
  or downloading the models on first start.

### Fixes worth doing before or alongside
- Test with real calls (Zoom/Teams/Telegram, Russian and English, with and without headphones) and tune
  `EchoFilter` and the notes prompt on them.
- A small evaluation set for the notes (a few transcripts with expected decisions/tasks/dates) so prompt or model
  changes can be measured instead of judged by eye.
- Continuous integration: GitHub Actions for the Lambda tests and `terraform validate`, and a Maven profile that
  skips the GPU tests.

### Later ideas
- Speaker separation (diarization) for "Other participants" and uploads, so notes can say who said what.
- Follow the default output device when it changes during a call.
- Silero VAD on the GPU through ONNX Runtime CUDA.
- Search across all meetings ("what did we decide about the release?") with local embeddings, linking answers to
  the moment in the recording: a natural next portfolio piece on top of the existing transcripts.
- A tagged release and a short demo video for the portfolio.
