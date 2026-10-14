# VisionTrack – video object analysis

Upload a video; VisionTrack detects objects with a YOLO model (ONNX Runtime), tracks them across
frames so each physical object is counted once, and reports **how many objects of each type**
appeared and the **timecode when each one was first (and last) seen**.

## Run locally

```bash
mvn package
java -jar target/visiontrack-object-detection-aws-1.0-SNAPSHOT.jar
```

Open http://localhost:8080, drop in a video and wait for the results. Settings live in
`src/main/resources/application.yml` (model, thresholds, sampling rate, upload limits).

## REST API

| Method | Path                  | Description                                                    |
|--------|-----------------------|----------------------------------------------------------------|
| POST   | `/api/analyses`       | multipart field `file` → `202 Accepted`, job JSON + `Location` |
| GET    | `/api/analyses/{id}`  | status (`QUEUED`, `PROCESSING`, `COMPLETED`, `FAILED`), progress, result |
| GET    | `/api/analyses`       | all jobs, newest first                                         |
| GET    | `/actuator/health`    | health check for load balancers                                |

### Meetings (Windows, local requests only)

| Method | Path                              | Description                                               |
|--------|-----------------------------------|-----------------------------------------------------------|
| GET    | `/api/meetings/devices`           | monitors and microphones (`?refresh=true` probes again)   |
| POST   | `/api/meetings/start`             | `{"title", "monitor", "microphone"}` → starts recording   |
| POST   | `/api/meetings/{id}/stop`         | stops, builds `recording.mp4` (screen + sound) and starts processing |
| GET    | `/api/meetings`, `/api/meetings/{id}` | recorded meetings (read from their folders)           |
| POST   | `/api/meetings/{id}/process`      | runs the processing again                                 |
| GET    | `/api/meetings/{id}/slides`       | slides with times; images at `/api/meetings/{id}/slides/slide-NN.png` |
| POST   | `/api/meetings/{id}/open-folder`  | opens the meeting folder in Explorer                      |

Recordings go to `Documents\VisionTrack\Meetings\<date>_<time>_<title>\`. The screen (ffmpeg `ddagrab`,
falling back to `gdigrab`, 1 fps) and the microphone (`dshow`, 16 kHz mono) are captured by the ffmpeg
bundled with JavaCV in separate processes, written in 5-minute parts, so a crash keeps what was recorded;
unfinished meetings are joined on the next start. After "Stop" the meeting is processed in the background
(`RECORDED → PROCESSING → READY`): `SlideChangeDetector` keeps every slide that stays on screen for 2 s,
ignoring participant webcams, transitions and bullet-by-bullet builds, and saves `slides/slide-NN.png`.
Then the microphone track is transcribed with whisper.cpp (`transcript.md`, `transcript.json`): Silero VAD
finds the speech, each piece of up to 30 s gets its own language (Russian or English), large-v3-turbo runs
on CUDA and small on the CPU when CUDA is unavailable. Install it once with
`powershell -ExecutionPolicy Bypass -File packaging\get-whisper.ps1` (downloads ~2 GB into `tools\whisper`
and `models\whisper`); without it meetings are still recorded and get slides, only the transcript is skipped.

```bash
curl -F "file=@street.mp4" http://localhost:8080/api/analyses
curl http://localhost:8080/api/analyses/<id>
```

Completed result (shortened):

```json
{
  "totalObjects": 8,
  "counts":  [{ "label": "person", "count": 4, "firstSeen": "00:00:02.000" }],
  "objects": [{ "id": 1, "label": "person", "firstSeen": "00:00:02.000", "lastSeen": "00:00:04.800",
                "visibleSeconds": 2.8, "confidence": 0.903, "box": { "x": 403, "y": 266, "width": 133, "height": 336 } }]
}
```

## How it works

```
upload ─► VideoStorage ─► job queue ─► VideoAnalyzer
                                         ├─ VideoReader (FFmpeg)       decode, sample N fps
                                         ├─ OnnxYoloDetector (YOLO)    letterbox → infer → NMS
                                         └─ ObjectTracker (IoU)        one track = one counted object
```

| Package       | Responsibility                                               |
|---------------|--------------------------------------------------------------|
| `web`         | REST controller, error responses                             |
| `job`         | job lifecycle, background execution, `JobRepository`         |
| `analysis`    | orchestrates a video run, builds the result                  |
| `detection`   | ONNX Runtime YOLO detector (CUDA → DirectML → CPU)          |
| `tracking`    | links detections across frames                               |
| `video`       | FFmpeg decoding and encoding                                 |
| `storage`     | where uploads are kept (`VideoStorage`)                      |
| `meeting`     | meeting recording: ffmpeg captures, segments, `meeting.json` |

### Tuning the count

* `analysis.sample-fps` – frames analysed per second of video (higher = more accurate, slower).
* `analysis.track-max-gap-seconds` – how long an object may be hidden and still be the same object.
* `analysis.track-min-hits` – detections needed before something counts (filters one-frame mistakes).
* Overlapping synonyms in `classes.txt` (e.g. `shoe`/`sneaker`, `cup`/`mug`, `hat`/`cap`) can make
  YOLO-World report the same thing twice – keep the class list free of near-duplicates.

## Deploying to AWS

```bash
docker build --build-arg MODEL=yoloworldx-960.onnx -t visiontrack .
docker run -p 8080:8080 visiontrack
```

Every setting can be overridden with environment variables, e.g. `VISIONTRACK_ANALYSIS_SAMPLE_FPS=10`.
The image runs inference on CPU; for a GPU instance (g4dn/g5) base the runtime stage on an
`nvidia/cuda:12.x-cudnn-runtime` image with a JRE and run it with the NVIDIA container runtime.

The extension points for a scalable setup are already interfaces:

* `VideoStorage` → S3 implementation (upload straight to S3 with presigned URLs).
* `JobRepository` → DynamoDB implementation, so jobs survive restarts and are shared between instances.
* The in-process queue → SQS, with workers running `VideoAnalyzer`.
