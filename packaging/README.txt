Hindsight - see what happened, remember what was said.
Finds objects in videos and shows when each one appeared, and records meetings on this computer.

GETTING STARTED
  1. Unpack the archive into any folder (for example, Documents).
  2. Run Hindsight.exe.
     The app window opens after a few seconds. The object detection model loads the first time
     you analyse a video or start the camera, so that first run takes a little longer.
  3. Desktop shortcut: right-click Hindsight.exe -> Show more options
     -> Send to -> Desktop (create shortcut).

  If Windows shows "Windows protected your PC", click
  "More info" -> "Run anyway". This happens because the app is not signed.

REQUIREMENTS
  Windows 10/11 (64-bit) and Microsoft Edge (included with Windows).
  No need to install Java - it is bundled.
  An NVIDIA graphics card (GTX 900 / RTX or newer) with driver 528 or newer - it is required: video analysis,
  the live camera, screen recording and speech recognition all run on the GPU. No need to install CUDA,
  everything required is bundled. Without a compatible card the app still opens, but these features show
  "... needs an NVIDIA graphics card with CUDA". If you have an NVIDIA card, update the driver from nvidia.com.
  Meeting notes need 12 GB of video memory (RTX 3060 12 GB, 4070 or better). The app takes about 15 GB of disk.

MEETINGS
  Record a call (your screen, your microphone and the other participants) or upload a recording.
  When it ends, the speech is transcribed and a local AI model writes the notes: summary, decisions
  and action items. Everything runs on this computer; nothing is sent anywhere.
  Processing an hour of meeting takes a few minutes. Ask everyone in the call before you record it.
  Meetings are saved in Documents\VisionTrack\Meetings.

CLOSING AND BACKGROUND
  Closing the window does not quit the app - it stays in the tray (the icon next to the clock).
  To open it again: use the shortcut or double-click the icon. To quit completely: right-click the icon -> Quit.

IF SOMETHING DOES NOT WORK
  Send the log file: %LOCALAPPDATA%\VisionTrack\visiontrack.log
  (paste this path into the File Explorer address bar).
