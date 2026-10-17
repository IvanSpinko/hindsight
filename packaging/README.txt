VisionTrack - finds objects in videos and shows when each one appeared.

GETTING STARTED
  1. Unpack the archive into any folder (for example, Documents).
  2. Run VisionTrack.exe.
     The app window opens after a few seconds. The object detection model loads the first time
     you analyse a video or start the camera, so that first run takes a little longer.
  3. Desktop shortcut: right-click VisionTrack.exe -> Show more options
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

MEETINGS IN THIS VERSION
  Meetings are recorded (screen and your microphone), but this version does not include speech
  recognition, the notes model or the helper that records the other participants. So a meeting has no
  transcript, no notes and no voices of the other participants. A later version will include them.

CLOSING AND BACKGROUND
  Closing the window does not quit the app - it stays in the tray (the icon next to the clock).
  To open it again: use the shortcut or double-click the icon. To quit completely: right-click the icon -> Quit.

IF SOMETHING DOES NOT WORK
  Send the log file: %LOCALAPPDATA%\VisionTrack\visiontrack.log
  (paste this path into the File Explorer address bar).
