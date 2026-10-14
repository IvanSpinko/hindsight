VisionTrack - finds objects in videos and shows when each one appeared.

GETTING STARTED
  1. Unpack the archive into any folder (for example, Documents).
  2. Run VisionTrack.exe.
     The first start takes 10-30 seconds while the model loads. Then the app window opens.
  3. Desktop shortcut: right-click VisionTrack.exe -> Show more options
     -> Send to -> Desktop (create shortcut).

  If Windows shows "Windows protected your PC", click
  "More info" -> "Run anyway". This happens because the app is not signed.

REQUIREMENTS
  Windows 10/11 (64-bit) and Microsoft Edge (included with Windows).
  No need to install Java - it is bundled.
  An NVIDIA graphics card (GTX 900 / RTX or newer) with an up-to-date driver - analysis runs on the GPU,
  no need to install CUDA, everything required is bundled. Without NVIDIA it runs on the CPU, but much slower.
  Which one is used is shown in "Technical details" after a video is processed: "on CUDA" or "on CPU".
  If it says "on CPU" although you have an NVIDIA card, update the driver from nvidia.com.

CLOSING AND BACKGROUND
  Closing the window does not quit the app - it stays in the tray (the icon next to the clock).
  To open it again: use the shortcut or double-click the icon. To quit completely: right-click the icon -> Quit.

IF SOMETHING DOES NOT WORK
  Send the log file: %LOCALAPPDATA%\VisionTrack\visiontrack.log
  (paste this path into the File Explorer address bar).
