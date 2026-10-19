# Hindsight

**A Windows app that watches videos and listens to meetings for you, and keeps everything on your own computer.**

Hindsight answers two everyday questions:

- *"What was in this video, and when?"* Give it a video and it lists the things it saw (people, cars, dogs,
  laptops and about a hundred other kinds of objects), counts them and shows the moment each one appears.
- *"What did we agree on in that call?"* Record an online meeting, or upload a recording, and it writes down
  everything that was said and turns it into short notes: a summary, the decisions and a to-do list with who
  does what and by when.

All the AI runs on your own graphics card. There is no account, no subscription and no internet connection
needed, and nothing leaves your computer unless you choose to send it.

## What it can do

### 1. Find objects in a video

Drop a video file into the app. A few moments later you get:

- a list of everything it recognised, with how many of each (a person who walks through the frame is counted
  once, not once per frame);
- a timeline showing when each kind of object is on screen; click it to jump to that moment;
- a copy of the video with a labelled box drawn around every object.

Past results stay in the list, also after the app is closed and opened again.

### 2. Live camera

Turn on your webcam and the app names what it sees, live, with boxes over the picture.

### 3. Meeting notes

Start a recording before a call on Zoom, Teams, Google Meet or any other app. Hindsight records:

- your screen, so you can watch the meeting again later;
- your microphone (you, labelled "Me");
- the sound of the call (everyone else, labelled "Other participants").

You can also upload an audio or video recording you already have.

When the meeting ends, the app turns the speech into text (a timed transcript) and writes notes in the same
language as the meeting:

- a short summary;
- what was decided;
- action items: the task, who owns it and the due date, if anyone said one;
- the main topics with their times, and the questions that were left open.

The notes can be sent to Notion with one click, or automatically. An hour-long call is transcribed in about a
minute; the notes take a few minutes more.

> Always ask the other people in a call before you record it.

### Settings

The **Settings** tab lets you choose where meetings are saved, which screen and microphone to record (with a
volume meter to test the microphone), the video quality, the language of the notes and your Notion connection.
It also shows your graphics card and how much of its memory is free.

## Privacy

- Video, audio, transcripts and notes stay in folders on your computer
  (meetings: `Documents\VisionTrack\Meetings`, one folder per meeting).
- The speech recognition and the notes are written by AI models that run locally, not by an online service.
- Only two things can ever leave the computer, and both are off until you turn them on: notes you send to
  Notion, and object counts (numbers only, never video) sent to the optional AWS data pipeline described below.

## What you need

- Windows 10 or 11 (64-bit).
- An NVIDIA graphics card with an up-to-date driver. It is required: the app does all its work on it.
  Video analysis runs on most recent cards; meeting notes need 12 GB of video memory (for example an
  RTX 3060 12 GB, RTX 4070 or better).
- About 15 GB of free disk space for the app and its AI models, plus room for your recordings.

Without a suitable NVIDIA card the app still opens, but it tells you which features need one.

## Getting started

### Using the ready-made app

1. Unpack `Hindsight.zip` into any folder.
2. Run `Hindsight.exe`. The app opens in its own window; closing the window keeps it running in the tray
   (next to the clock), right-click the tray icon to quit.

Everything it needs, including Java and the AI models, is inside the folder. If Windows warns that it
"protected your PC", click *More info* → *Run anyway* (the app is not signed).

### Building it yourself (developers)

You need JDK 21, Maven, Python, the NVIDIA CUDA 12 runtime and cuDNN 9.

```bash
pip install ultralytics
python export_yoloworld.py
powershell -ExecutionPolicy Bypass -File packaging\get-models.ps1
mvn package
java -jar target/visiontrack-object-detection-aws-1.0-SNAPSHOT.jar
```

1. `export_yoloworld.py` downloads the object detection model and saves it in `models/`. The list of objects
   it looks for is in `models/classes.txt` and can be edited.
2. `get-models.ps1` downloads the speech and notes models (about 13 GB). Video analysis works without them.
3. Open http://localhost:8080.

To build the ready-made app run `packaging\build-desktop.ps1`; it creates `dist\Hindsight\Hindsight.exe` and
`dist\Hindsight.zip`. Default settings are in `src/main/resources/application.yml`.

The project started under the name VisionTrack, so the code, the settings keys and the data folders still use
that name.

## How it works

**Video:** the video is read frame by frame (5 frames per second of video). An object detection model finds the
objects in each frame, and a tracker links the same object from frame to frame, so it is counted only once.

```
video ─► read frames ─► find objects in each frame ─► follow each object over time ─► counts, timeline, boxed video
```

**Meetings:** screen, microphone and call sound are recorded as separate files, saved in 5-minute parts so a crash
loses almost nothing. After the call:

```
recording ─► find the parts with speech ─► speech to text ─► notes by a local AI model ─► notes.md (+ Notion)
```

The AI models take turns on the graphics card, one at a time, and are unloaded when they finish, so even a 12 GB
card has room for each of them.

### Built with

| Part | Technology |
|---|---|
| App and web page | Java 21, Spring Boot 3.5, plain HTML and JavaScript |
| Object detection | YOLO-World (Ultralytics), run with ONNX Runtime on CUDA |
| Video and screen recording | FFmpeg (JavaCV), encoded on the graphics card (NVENC) |
| Speech to text | NVIDIA Parakeet (ONNX Runtime), Whisper (whisper.cpp) as a backup, Silero VAD to find speech |
| Meeting notes | Gemma 4 12B, run with llama.cpp on CUDA |
| Call sound | a small C# helper using Windows audio loopback |
| Cloud data pipeline | AWS S3, Lambda (Python), Glue, Athena, set up with Terraform |

The code is in `src/main/java/org/example/visiontrack`, one package per area: `analysis`, `detection`,
`tracking` and `video` for videos; `meeting` for recording, speech and notes; `export` for the data pipeline;
`web` for the API; `config` for settings.

## Optional: cloud data pipeline (AWS)

For analysing results over time, the app can send the detections (which object, where in the frame, when; no
images) to Amazon Web Services. There they are checked, cleaned and stored so they can be queried with SQL, for
example "how many people were detected per day". This is off by default.
How it is built, set up and paid for: [docs/cloud-pipeline.md](docs/cloud-pipeline.md).

## More documentation

- [docs/api.md](docs/api.md): the REST API the web page uses.
- [docs/cloud-pipeline.md](docs/cloud-pipeline.md): the AWS pipeline.
- [docs/project-report.md](docs/project-report.md): the full technical report (architecture, configuration,
  tests, known issues and plans).

## Known limitations

- Windows and an NVIDIA graphics card only.
- Everyone on the other side of a call is "Other participants"; the notes cannot yet tell those voices apart.
- The notes come from a local AI model and can get a name or a date wrong, so check them before you rely on them.
- The ready-made app is large (about 15 GB), mostly because of the AI models.
- The object detection model (Ultralytics YOLO) is licensed under AGPL-3.0, which matters if the app is shared
  publicly.
