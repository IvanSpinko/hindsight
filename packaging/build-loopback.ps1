# Builds tools\audio-loopback\audio-loopback.exe (system sound capture for meetings) from native\audio-loopback
# with the C# compiler that ships with Windows (.NET Framework 4.x); nothing needs to be installed.
# Usage (from the project root):  powershell -ExecutionPolicy Bypass -File packaging\build-loopback.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $csc)) { throw "The .NET Framework 4 C# compiler was not found: $csc" }
New-Item -ItemType Directory -Force "tools\audio-loopback" | Out-Null
& $csc /nologo /optimize+ /platform:x64 /target:exe /out:tools\audio-loopback\audio-loopback.exe native\audio-loopback\AudioLoopback.cs
if ($LASTEXITCODE -ne 0) { throw "audio-loopback.exe did not compile" }
Write-Host "Built tools\audio-loopback\audio-loopback.exe"
