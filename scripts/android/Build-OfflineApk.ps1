param(
  [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path,
  [string]$InstallRoot = "D:\android-dev",
  [string]$Gradle = "",
  [string]$BuildRoot = "",
  [switch]$SkipModelAssets,
  [switch]$CleanModelAssets,
  [switch]$SkipNluExport,
  [switch]$SkipPose,
  [switch]$Arm64Only
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
  Write-Host "[offline-apk] $Message"
}

function Format-Size([int64]$Bytes) {
  if ($Bytes -ge 1GB) { return "{0:N2} GiB" -f ($Bytes / 1GB) }
  if ($Bytes -ge 1MB) { return "{0:N2} MiB" -f ($Bytes / 1MB) }
  return "$Bytes B"
}

$androidDir = Join-Path $RepoRoot "android"

if ([string]::IsNullOrWhiteSpace($BuildRoot)) {
  $eDrive = Get-PSDrive E -ErrorAction SilentlyContinue
  if ($eDrive -and $eDrive.Free -gt 20GB) {
    $BuildRoot = "E:\firstaid-offline-apk-build"
  }
}

$apkPath = if ([string]::IsNullOrWhiteSpace($BuildRoot)) {
  Join-Path $androidDir "app\build\outputs\apk\debug\app-debug.apk"
} else {
  Join-Path $BuildRoot "app\outputs\apk\debug\app-debug.apk"
}

if (!$SkipModelAssets) {
  $prepareArgs = @{
    RepoRoot = $RepoRoot
  }
  if ($CleanModelAssets) { $prepareArgs.Clean = $true }
  if ($SkipNluExport) { $prepareArgs.SkipNluExport = $true }
  if ($SkipPose) { $prepareArgs.SkipPose = $true }
  Write-Step "preparing bundled model assets"
  & (Join-Path $PSScriptRoot "Prepare-ApkModelAssets.ps1") @prepareArgs
}

Write-Step "ensuring sherpa-onnx AAR exists"
& (Join-Path $PSScriptRoot "Setup-OnDeviceModels.ps1") -RepoRoot $RepoRoot -SkipPush

Write-Step "activating Android toolchain"
. (Join-Path $RepoRoot "scripts\useAndroidDevEnv.ps1") -InstallRoot $InstallRoot

if ([string]::IsNullOrWhiteSpace($Gradle)) {
  $Gradle = Join-Path $InstallRoot "gradle\gradle-8.13\bin\gradle.bat"
}
if (!(Test-Path -LiteralPath $Gradle)) {
  $foundGradle = Get-Command gradle -ErrorAction SilentlyContinue
  if (!$foundGradle) {
    throw "Gradle not found. Pass -Gradle or install the Android dev environment."
  }
  $Gradle = $foundGradle.Source
}

if ($Arm64Only) {
  Write-Warning "Arm64Only is accepted for operator intent, but ABI filters are defined in android/app/build.gradle.kts. Edit that file if x86_64 must be removed."
}

Write-Step "building debug APK"
Push-Location $androidDir
try {
  $gradleArgs = @(":app:assembleDebug")
  if (![string]::IsNullOrWhiteSpace($BuildRoot)) {
    Write-Step "using external Gradle build root: $BuildRoot"
    $gradleArgs += "-PofflineApkBuildRoot=$BuildRoot"
  }
  & $Gradle @gradleArgs
  if ($LASTEXITCODE -ne 0) {
    throw "Gradle assembleDebug failed with code $LASTEXITCODE"
  }
} finally {
  Pop-Location
}

if (!(Test-Path -LiteralPath $apkPath)) {
  throw "APK was not produced: $apkPath"
}

$apk = Get-Item -LiteralPath $apkPath
$limit = [int64]4GB
Write-Step "APK: $($apk.FullName)"
Write-Step "APK size: $(Format-Size $apk.Length) ($($apk.Length) bytes)"
if ($apk.Length -ge $limit) {
  throw "APK is >= 4 GiB and may not install. Rebuild with fewer assets or arm64-only native libs."
}

Write-Step "done"
