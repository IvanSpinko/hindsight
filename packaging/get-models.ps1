# Downloads what meeting processing runs on the GPU: whisper.cpp (speech recognition) into tools\whisper,
# llama.cpp (notes) into tools\llama, and their models into models\whisper and models\llm; builds the system
# sound helper into tools\audio-loopback.
# All four folders are ignored by git (about 1.1 + 1.2 GB of programs, 1.1 + 7 GB of models).
# Usage (from the project root):  powershell -ExecutionPolicy Bypass -File packaging\get-models.ps1
param(
    # whisper.cpp and llama.cpp release builds that have the Windows CUDA binaries attached
    [string]$WhisperBuild = "b5454",
    [string]$LlamaBuild = "b11624",
    [switch]$Force
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is very slow with the progress bar
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# Downloads zip files, unpacks them together and copies the files matching $keep into $target
function Install-Release([string[]]$urls, [string]$target, [string[]]$keep, [string]$subfolder) {
    $unpacked = Join-Path $env:TEMP ("visiontrack-" + (Split-Path -Leaf $target))
    if (Test-Path $unpacked) { Remove-Item -Recurse -Force $unpacked }
    foreach ($url in $urls) {
        $zip = Join-Path $env:TEMP (Split-Path -Leaf $url)
        Write-Host "Downloading $(Split-Path -Leaf $url)..."
        Invoke-WebRequest $url -OutFile $zip
        Expand-Archive $zip $unpacked -Force
        Remove-Item -Force $zip
    }
    New-Item -ItemType Directory -Force $target | Out-Null
    foreach ($pattern in $keep) { Copy-Item (Join-Path $unpacked "$subfolder$pattern") $target -Force }
    Remove-Item -Recurse -Force $unpacked
}

function Get-Model([string]$folder, [string]$name, [string]$url) {
    New-Item -ItemType Directory -Force $folder | Out-Null
    $target = Join-Path $folder $name
    if ($Force -or -not (Test-Path $target)) {
        Write-Host "Downloading $name..."
        Invoke-WebRequest $url -OutFile $target
    }
}

# Speech recognition: the server, the VAD tool, their DLLs and the CUDA runtime they need
if ($Force -or -not (Test-Path "tools\whisper\whisper-server.exe")) {
    Install-Release @("https://github.com/ggml-org/whisper.cpp/releases/download/$WhisperBuild/whisper-bin-win-cuda-12.4.0-x64.zip") `
        "tools\whisper" @("whisper-server.exe", "whisper-vad-speech-segments.exe", "whisper.dll", "ggml*.dll",
                          "cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll") "Release\"
}
Get-Model "models\whisper" "ggml-large-v3-turbo-q8_0.bin" "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin"
Get-Model "models\whisper" "ggml-silero-v5.1.2.bin" "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin"

# Notes: llama-server with its DLLs (the CUDA 12.4 build runs on any GPU the 528+ driver supports) and the CUDA runtime
if ($Force -or -not (Test-Path "tools\llama\llama-server.exe")) {
    $base = "https://github.com/ggml-org/llama.cpp/releases/download/$LlamaBuild"
    Install-Release @("$base/llama-$LlamaBuild-bin-win-cuda-12.4-x64.zip", "$base/cudart-llama-bin-win-cuda-12.4-x64.zip") `
        "tools\llama" @("llama-server.exe", "llama-server-impl.dll", "llama-common.dll", "llama.dll", "mtmd.dll",
                        "ggml*.dll", "libomp.dll", "cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll") ""
}
# Gemma 4 12B, quantisation-aware 4-bit (Apache 2.0): ~7 GB, writes Russian and English notes well on a 12 GB GPU
Get-Model "models\llm" "gemma-4-12b-it-qat-q4_0.gguf" "https://huggingface.co/google/gemma-4-12B-it-qat-q4_0-gguf/resolve/main/gemma-4-12b-it-qat-q4_0.gguf"

# System sound capture (the other participants of a call): built locally, nothing to download
& (Join-Path $PSScriptRoot "build-loopback.ps1")

Write-Host "Done: tools\whisper, tools\llama, tools\audio-loopback, models\whisper, models\llm"
