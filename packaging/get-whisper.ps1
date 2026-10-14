# Downloads speech recognition for meetings: whisper.cpp (CUDA build) into tools\whisper and the models
# into models\whisper. Both folders are ignored by git (about 1.1 GB + 1.1 GB).
# Usage (from the project root):  powershell -ExecutionPolicy Bypass -File packaging\get-whisper.ps1
param(
    # whisper.cpp release build that has the Windows binaries attached
    [string]$Build = "b5454",
    [switch]$Force
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is very slow with the progress bar
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$tools = "tools\whisper"
$models = "models\whisper"
New-Item -ItemType Directory -Force $tools, $models | Out-Null

if ($Force -or -not (Test-Path "$tools\whisper-server.exe")) {
    $zip = Join-Path $env:TEMP "whisper-bin-win-cuda-x64.zip"
    $url = "https://github.com/ggml-org/whisper.cpp/releases/download/$Build/whisper-bin-win-cuda-12.4.0-x64.zip"
    Write-Host "Downloading whisper.cpp $Build (about 700 MB)..."
    Invoke-WebRequest $url -OutFile $zip
    $unpacked = Join-Path $env:TEMP "whisper-bin-win-cuda-x64"
    if (Test-Path $unpacked) { Remove-Item -Recurse -Force $unpacked }
    Expand-Archive $zip $unpacked
    # Only what the app runs: the server, the VAD tool, their DLLs and the CUDA runtime they need
    $keep = "whisper-server.exe", "whisper-vad-speech-segments.exe", "whisper.dll", "ggml*.dll",
            "cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll"
    foreach ($pattern in $keep) { Copy-Item "$unpacked\Release\$pattern" $tools -Force }
    Remove-Item -Recurse -Force $unpacked, $zip
}

$files = @{
    "ggml-large-v3-turbo-q8_0.bin" = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin"
    "ggml-small-q8_0.bin"          = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin"
    "ggml-silero-v5.1.2.bin"       = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin"
}
foreach ($name in $files.Keys) {
    $target = Join-Path $models $name
    if ($Force -or -not (Test-Path $target)) {
        Write-Host "Downloading $name..."
        Invoke-WebRequest $files[$name] -OutFile $target
    }
}
Write-Host "Done: $tools and $models"
