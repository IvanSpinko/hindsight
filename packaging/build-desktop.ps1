# Builds the portable desktop app: dist\VisionTrack\VisionTrack.exe (+ bundled Java) and dist\VisionTrack.zip
# Usage (from the project root):  powershell -ExecutionPolicy Bypass -File packaging\build-desktop.ps1
param(
    [string]$Model = "yoloworldx-960.onnx",
    [string]$Version = "1.0.0",
    [int]$Port = 47821,
    [string]$CudaBin = "$env:CUDA_PATH\bin",
    [string]$CudnnBin = (Get-ChildItem "C:\Program Files\NVIDIA\CUDNN\v9*\bin\12*\x64" -Directory -ErrorAction SilentlyContinue |
        Sort-Object FullName | Select-Object -Last 1 -ExpandProperty FullName),
    [switch]$CpuOnly,
    [switch]$NoZip
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
if (-not (Test-Path "models\$Model")) { throw "Model not found: models\$Model" }

Write-Host "1/4 Building the jar..."
& $mvn -q clean package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

Write-Host "2/4 Preparing files..."
$staging = "target\jpackage-input"
New-Item -ItemType Directory -Force "$staging\models" | Out-Null
Copy-Item "target\visiontrack-object-detection-aws-1.0-SNAPSHOT.jar" "$staging\visiontrack.jar"
Copy-Item "models\$Model", "models\classes.txt" "$staging\models\"

Write-Host "3/4 Packaging VisionTrack.exe (takes a minute)..."
if (Test-Path "dist\VisionTrack") { Remove-Item -Recurse -Force "dist\VisionTrack" }
jpackage --type app-image `
    --name VisionTrack `
    --app-version $Version `
    --description "Finds objects in videos" `
    --icon "packaging\visiontrack.ico" `
    --input $staging `
    --main-jar visiontrack.jar `
    --dest dist `
    --java-options "-Dspring.profiles.active=desktop" `
    --java-options "-Djava.awt.headless=false" `
    --java-options "-Dserver.port=$Port" `
    --java-options ('-Dvisiontrack.model.path=$APPDIR\models\' + $Model) `
    --java-options '-Dvisiontrack.model.classes-path=$APPDIR\models\classes.txt' `
    --java-options '-Dvisiontrack.model.native-dir=$APPDIR\cuda'
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }
# The JDK bundles an older VC++ runtime (14.36) that the exe loads before our code runs, and
# onnxruntime needs 14.4x+. Ship the newer System32 copies instead (app-local deployment is allowed).
foreach ($dll in "msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll") {
    Copy-Item "$env:SystemRoot\System32\$dll" "dist\VisionTrack\runtime\bin\$dll" -Force
}
# onnxruntime_gpu needs the CUDA 12 runtime and cuDNN 9, which only come with the CUDA Toolkit, not
# with the NVIDIA driver. Ship them in app\cuda; the app pre-loads them (visiontrack.model.native-dir)
# so it runs on any NVIDIA GPU with a recent driver. cudnn_adv (RNNs) is not used by YOLO.
if (-not $CpuOnly) {
    $cuda = "cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll", "cufft64_11.dll" | ForEach-Object { Join-Path $CudaBin $_ }
    $cudnn = Get-ChildItem "$CudnnBin\cudnn*64_9.dll" -ErrorAction SilentlyContinue | Where-Object Name -ne "cudnn_adv64_9.dll"
    $missing = $cuda | Where-Object { -not (Test-Path $_) }
    if ($missing -or -not $cudnn) {
        throw "CUDA 12 / cuDNN 9 DLLs not found (missing: $missing; cuDNN: $CudnnBin). Pass -CudaBin/-CudnnBin or build with -CpuOnly"
    }
    Write-Host "     Adding CUDA $CudaBin and cuDNN $CudnnBin DLLs..."
    $cudaDir = "dist\VisionTrack\app\cuda"
    New-Item -ItemType Directory -Force $cudaDir | Out-Null
    Copy-Item $cuda $cudaDir
    $cudnn | Copy-Item -Destination $cudaDir
}
Copy-Item "packaging\README.txt" "dist\VisionTrack\README.txt"

if (-not $NoZip) {
    Write-Host "4/4 Creating dist\VisionTrack.zip..."
    if (Test-Path "dist\VisionTrack.zip") { Remove-Item "dist\VisionTrack.zip" }
    tar.exe -a -c -f "dist\VisionTrack.zip" -C dist VisionTrack
}
Write-Host "Done: dist\VisionTrack\VisionTrack.exe"
