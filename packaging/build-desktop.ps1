# Builds the portable desktop app: dist\Hindsight\Hindsight.exe (+ bundled Java) and dist\Hindsight.zip,
# with everything meetings need (speech and notes models, ~11 GB; run packaging\get-models.ps1 first).
# -NoMeetings builds the small video-only app.
# Usage (from the project root):  powershell -ExecutionPolicy Bypass -File packaging\build-desktop.ps1
param(
    [string]$Model = "yoloworldx-960.onnx",
    [string]$Version = "1.0.0",
    [int]$Port = 47821,
    [string]$CudaBin = "$env:CUDA_PATH\bin",
    [string]$CudnnBin = (Get-ChildItem "C:\Program Files\NVIDIA\CUDNN\v9*\bin\12*\x64" -Directory -ErrorAction SilentlyContinue |
        Sort-Object FullName | Select-Object -Last 1 -ExpandProperty FullName),
    [switch]$NoZip,
    [switch]$NoMeetings
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$mvn = (Get-Command mvn -ErrorAction SilentlyContinue).Source
if (-not $mvn) {
    $mvn = Get-ChildItem "C:\Program Files\JetBrains" -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $mvn) { throw "Maven not found: install it or add it to PATH" }
# The Oracle installer puts only java.exe on PATH (Common Files\Oracle\Java\javapath), not jpackage
$jpackage = (Get-Command jpackage -ErrorAction SilentlyContinue).Source
if (-not $jpackage -and $env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jpackage.exe")) {
    $jpackage = "$env:JAVA_HOME\bin\jpackage.exe"
}
if (-not $jpackage) { throw "jpackage not found: install a JDK 21 and set JAVA_HOME or add its bin to PATH" }
if (-not (Test-Path "models\$Model")) { throw "Model not found: models\$Model" }

# Meeting tools and models, copied into the app as they are laid out in the project
$meetingFiles = "tools\whisper", "tools\llama", "tools\audio-loopback\audio-loopback.exe",
    "models\whisper\ggml-large-v3-turbo-q8_0.bin", "models\whisper\ggml-silero-v5.1.2.bin",
    "models\parakeet-tdt-0.6b-v3", "models\llm\gemma-4-12b-it-qat-q4_0.gguf"
if (-not $NoMeetings) {
    $missing = $meetingFiles | Where-Object { -not (Test-Path $_) }
    if ($missing) { throw "Meeting files not found: $missing. Run packaging\get-models.ps1, or pass -NoMeetings" }
}

Write-Host "1/4 Building the jar..."
# -Ddesktop leaves out the Linux FFmpeg natives (the linux-natives profile in pom.xml)
& $mvn -q clean package -DskipTests -Ddesktop
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

Write-Host "2/4 Preparing files..."
$staging = "target\jpackage-input"
New-Item -ItemType Directory -Force "$staging\models" | Out-Null
Copy-Item "target\visiontrack-object-detection-aws-1.0-SNAPSHOT.jar" "$staging\visiontrack.jar"
Copy-Item "models\$Model", "models\classes.txt" "$staging\models\"

# onnxruntime_gpu ships Linux libraries and a 350 MB debug .pdb as well: keep only the Windows libraries.
# Spring Boot needs nested jars stored without compression, hence --no-compress.
$jar = Join-Path (Split-Path -Parent $jpackage) "jar.exe"
$ort = & $jar --list --file "$staging\visiontrack.jar" | Where-Object { $_ -like "BOOT-INF/lib/onnxruntime_gpu-*.jar" }
if (-not $ort) { throw "onnxruntime_gpu not found in the jar" }
$appJar = (Resolve-Path "$staging\visiontrack.jar").Path
$ortWork = "target\ort-slim"
New-Item -ItemType Directory -Force "$ortWork\content" | Out-Null
# jar --extract (JDK 21) writes into the current folder
Push-Location $ortWork
try {
    & $jar --extract --file $appJar $ort
    if ($LASTEXITCODE -ne 0) { throw "Cannot extract $ort from the jar" }
    Push-Location content
    try { & $jar --extract --file "..\$ort" } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "Cannot unpack $ort" }
    Remove-Item -Recurse -Force "content\ai\onnxruntime\native\linux-x64"
    Get-ChildItem content -Recurse -Filter *.pdb | Remove-Item -Force
    & $jar --create --file $ort --no-manifest -C content .
    if ($LASTEXITCODE -ne 0) { throw "Cannot repack $ort" }
    & $jar --update --file $appJar --no-compress $ort
    if ($LASTEXITCODE -ne 0) { throw "Cannot put the slimmed $ort back into the jar" }
} finally { Pop-Location }

Write-Host "3/4 Packaging Hindsight.exe (takes a minute)..."
if (Test-Path "dist\Hindsight") { Remove-Item -Recurse -Force "dist\Hindsight" }
$javaOptions = @(
    "-Dspring.profiles.active=desktop",
    "-Djava.awt.headless=false",
    "-Dserver.port=$Port",
    ('-Dvisiontrack.model.path=$APPDIR\models\' + $Model),
    '-Dvisiontrack.model.classes-path=$APPDIR\models\classes.txt',
    '-Dvisiontrack.model.native-dir=$APPDIR\cuda'
)
if (-not $NoMeetings) {
    $javaOptions += @(
        '-Dvisiontrack.meetings.system-audio-helper=$APPDIR\tools\audio-loopback\audio-loopback.exe',
        '-Dvisiontrack.meetings.whisper.directory=$APPDIR\tools\whisper',
        '-Dvisiontrack.meetings.whisper.model=$APPDIR\models\whisper\ggml-large-v3-turbo-q8_0.bin',
        '-Dvisiontrack.meetings.whisper.vad-model=$APPDIR\models\whisper\ggml-silero-v5.1.2.bin',
        '-Dvisiontrack.meetings.whisper.cuda-dir=$APPDIR\cuda',
        '-Dvisiontrack.meetings.parakeet.directory=$APPDIR\models\parakeet-tdt-0.6b-v3',
        '-Dvisiontrack.meetings.notes.directory=$APPDIR\tools\llama',
        '-Dvisiontrack.meetings.notes.model=$APPDIR\models\llm\gemma-4-12b-it-qat-q4_0.gguf',
        '-Dvisiontrack.meetings.notes.cuda-dir=$APPDIR\cuda'
    )
}
$jpackageArgs = @("--type", "app-image", "--name", "Hindsight", "--app-version", $Version,
    "--description", "Hindsight - see what happened, remember what was said", "--icon", "packaging\visiontrack.ico",
    "--input", $staging, "--main-jar", "visiontrack.jar", "--dest", "dist")
foreach ($option in $javaOptions) { $jpackageArgs += @("--java-options", $option) }
& $jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }
# onnxruntime needs a newer VC++ runtime than the one bundled with the JDK: ship the System32 copies
foreach ($dll in "msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll") {
    Copy-Item "$env:SystemRoot\System32\$dll" "dist\Hindsight\runtime\bin\$dll" -Force
}
# CUDA 12 and cuDNN 9 come only with the CUDA Toolkit, so ship them in app\cuda (cudnn_adv is not needed)
$cuda = "cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll", "cufft64_11.dll" | ForEach-Object { Join-Path $CudaBin $_ }
$cudnn = Get-ChildItem "$CudnnBin\cudnn*64_9.dll" -ErrorAction SilentlyContinue | Where-Object Name -ne "cudnn_adv64_9.dll"
$missing = $cuda | Where-Object { -not (Test-Path $_) }
if ($missing -or -not $cudnn) {
    throw "CUDA 12 / cuDNN 9 DLLs not found (missing: $missing; cuDNN: $CudnnBin). Pass -CudaBin/-CudnnBin"
}
Write-Host "     Adding CUDA $CudaBin and cuDNN $CudnnBin DLLs..."
$cudaDir = "dist\Hindsight\app\cuda"
New-Item -ItemType Directory -Force $cudaDir | Out-Null
Copy-Item $cuda $cudaDir
$cudnn | Copy-Item -Destination $cudaDir
Copy-Item "packaging\README.txt" "dist\Hindsight\README.txt"

if (-not $NoMeetings) {
    Write-Host "     Adding the meeting tools and models (~11 GB)..."
    foreach ($item in $meetingFiles) {
        $target = Join-Path "dist\Hindsight\app" (Split-Path -Parent $item)
        New-Item -ItemType Directory -Force $target | Out-Null
        Copy-Item -Recurse -Force $item $target
    }
    # whisper.cpp and llama.cpp each come with their own CUDA runtime (~575 MB apiece); app\cuda has a newer
    # CUDA 12 they work with, and the app puts it on their PATH (meetings.*.cuda-dir above)
    Get-ChildItem "dist\Hindsight\app\tools\whisper", "dist\Hindsight\app\tools\llama" -Include "cublas*.dll", "cudart*.dll" -Recurse |
        Remove-Item -Force
}

if (-not $NoZip) {
    Write-Host "4/4 Creating dist\Hindsight.zip..."
    if (Test-Path "dist\Hindsight.zip") { Remove-Item "dist\Hindsight.zip" }
    tar.exe -a -c -f "dist\Hindsight.zip" -C dist Hindsight
}
Write-Host "Done: dist\Hindsight\Hindsight.exe"
