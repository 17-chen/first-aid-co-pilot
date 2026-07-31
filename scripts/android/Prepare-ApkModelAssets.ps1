param(
  [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path,
  [string]$ModelAssetsRoot = "",
  [int]$GemmaChunkSizeMb = 1024,
  [switch]$Clean,
  [switch]$Force,
  [switch]$SkipGemma,
  [switch]$SkipAsr,
  [switch]$SkipTts,
  [switch]$SkipPose,
  [switch]$SkipNlu,
  [switch]$SkipNluExport
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
  Write-Host "[apk-model-assets] $Message"
}

function Write-Warn([string]$Message) {
  Write-Warning "[apk-model-assets] $Message"
}

function New-DirectoryIfMissing([string]$Path) {
  if (!(Test-Path -LiteralPath $Path)) {
    New-Item -ItemType Directory -Force -Path $Path | Out-Null
  }
}

function Copy-RequiredFile([string]$Source, [string]$Destination) {
  if (!(Test-Path -LiteralPath $Source)) {
    throw "Missing required file: $Source"
  }
  New-DirectoryIfMissing (Split-Path -Parent $Destination)
  if ((Test-Path -LiteralPath $Destination) -and !$Force) {
    return
  }
  Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

function Copy-OptionalFile([string]$Source, [string]$Destination) {
  if (!(Test-Path -LiteralPath $Source)) {
    return $false
  }
  New-DirectoryIfMissing (Split-Path -Parent $Destination)
  if (!(Test-Path -LiteralPath $Destination) -or $Force) {
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
  }
  return $true
}

function Copy-DirectoryContent([string]$Source, [string]$Destination) {
  if (!(Test-Path -LiteralPath $Source)) {
    throw "Missing required directory: $Source"
  }
  New-DirectoryIfMissing $Destination
  Get-ChildItem -LiteralPath $Source -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $Destination -Recurse -Force
  }
}

function Get-RelativePath([string]$Base, [string]$Path) {
  $baseUri = [Uri]((Resolve-Path -LiteralPath $Base).Path.TrimEnd('\') + '\')
  $pathUri = [Uri](Resolve-Path -LiteralPath $Path).Path
  return [Uri]::UnescapeDataString($baseUri.MakeRelativeUri($pathUri).ToString()).Replace('/', '\')
}

function Split-GemmaModel([string]$Source, [string]$DestinationDir, [int64]$ChunkSizeBytes) {
  if (!(Test-Path -LiteralPath $Source)) {
    throw "Missing Gemma model: $Source"
  }
  New-DirectoryIfMissing $DestinationDir
  $sourceInfo = Get-Item -LiteralPath $Source
  $manifestPath = Join-Path $DestinationDir "gemma-4-E2B-it.litertlm.parts.json"
  if ((Test-Path -LiteralPath $manifestPath) -and !$Force) {
    Write-Step "Gemma shard manifest already exists: $manifestPath"
    return
  }

  Get-ChildItem -LiteralPath $DestinationDir -Filter "gemma-4-E2B-it.litertlm.part*" -File -ErrorAction SilentlyContinue |
    Remove-Item -Force

  Write-Step "splitting Gemma model ($($sourceInfo.Length) bytes)"
  $bufferSize = 8 * 1024 * 1024
  $buffer = [byte[]]::new($bufferSize)
  $inputStream = [System.IO.File]::OpenRead($Source)
  $parts = New-Object System.Collections.Generic.List[object]
  try {
    $partIndex = 0
    while ($inputStream.Position -lt $inputStream.Length) {
      $partName = "gemma-4-E2B-it.litertlm.part{0:d3}" -f $partIndex
      $partPath = Join-Path $DestinationDir $partName
      $output = [System.IO.File]::Create($partPath)
      $written = [int64]0
      try {
        while ($written -lt $ChunkSizeBytes -and $inputStream.Position -lt $inputStream.Length) {
          $remainingInPart = $ChunkSizeBytes - $written
          $readLength = [Math]::Min($buffer.Length, [int]$remainingInPart)
          $read = $inputStream.Read($buffer, 0, $readLength)
          if ($read -le 0) { break }
          $output.Write($buffer, 0, $read)
          $written += $read
        }
      } finally {
        $output.Dispose()
      }
      $partInfo = Get-Item -LiteralPath $partPath
      $partHash = (Get-FileHash -LiteralPath $partPath -Algorithm SHA256).Hash.ToLowerInvariant()
      $parts.Add([ordered]@{
        file = $partName
        asset = "models/gemma/$partName"
        sizeBytes = [int64]$partInfo.Length
        sha256 = $partHash
      })
      Write-Step "created $partName ($($partInfo.Length) bytes)"
      $partIndex += 1
    }
  } finally {
    $inputStream.Dispose()
  }

  $modelHash = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash.ToLowerInvariant()
  $manifest = [ordered]@{
    version = 1
    fileName = "gemma-4-E2B-it.litertlm"
    target = "gemma/gemma-4-E2B-it.litertlm"
    totalSizeBytes = [int64]$sourceInfo.Length
    sha256 = $modelHash
    chunkSizeBytes = [int64]$ChunkSizeBytes
    parts = $parts
  }
  $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
}

if ([string]::IsNullOrWhiteSpace($ModelAssetsRoot)) {
  $ModelAssetsRoot = Join-Path $RepoRoot "android\app\model-assets"
}

$modelsRoot = Join-Path $RepoRoot "models"
$assetsModelsRoot = Join-Path $ModelAssetsRoot "models"
$bundleManifestPath = Join-Path $assetsModelsRoot "apk_model_bundle.json"

if ($Clean -and (Test-Path -LiteralPath $ModelAssetsRoot)) {
  Write-Step "cleaning $ModelAssetsRoot"
  Remove-Item -LiteralPath $ModelAssetsRoot -Recurse -Force
}

New-DirectoryIfMissing $ModelAssetsRoot
New-DirectoryIfMissing $assetsModelsRoot

if (!$SkipGemma) {
  $gemmaSource = Join-Path $modelsRoot "gemma\gemma-4-E2B-it-litert-lm\gemma-4-E2B-it.litertlm"
  $gemmaDestDir = Join-Path $assetsModelsRoot "gemma"
  Split-GemmaModel -Source $gemmaSource -DestinationDir $gemmaDestDir -ChunkSizeBytes ([int64]$GemmaChunkSizeMb * 1024 * 1024)
}

if (!$SkipAsr) {
  $asrSource = Join-Path $modelsRoot "speech\stt-stream\sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"
  $asrDest = Join-Path $assetsModelsRoot "speech\stt-stream\sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"
  Write-Step "copying streaming ASR minimal files"
  Copy-RequiredFile (Join-Path $asrSource "encoder-epoch-99-avg-1.int8.onnx") (Join-Path $asrDest "encoder-epoch-99-avg-1.int8.onnx")
  $decoderSource = Join-Path $asrSource "decoder-epoch-99-avg-1.onnx"
  if (!(Test-Path -LiteralPath $decoderSource)) {
    $decoderSource = Join-Path $asrSource "decoder-epoch-99-avg-1.int8.onnx"
  }
  Copy-RequiredFile $decoderSource (Join-Path $asrDest (Split-Path -Leaf $decoderSource))
  Copy-RequiredFile (Join-Path $asrSource "joiner-epoch-99-avg-1.int8.onnx") (Join-Path $asrDest "joiner-epoch-99-avg-1.int8.onnx")
  Copy-RequiredFile (Join-Path $asrSource "tokens.txt") (Join-Path $asrDest "tokens.txt")
}

if (!$SkipTts) {
  $ttsSource = Join-Path $modelsRoot "speech\tts"
  $ttsDest = Join-Path $assetsModelsRoot "speech\tts"
  Write-Step "copying TTS minimal files"
  $ttsModel = Join-Path $ttsSource "model.int8.onnx"
  if (!(Test-Path -LiteralPath $ttsModel)) {
    $ttsModel = Join-Path $ttsSource "model.onnx"
  }
  Copy-RequiredFile $ttsModel (Join-Path $ttsDest (Split-Path -Leaf $ttsModel))
  Copy-RequiredFile (Join-Path $ttsSource "lexicon.txt") (Join-Path $ttsDest "lexicon.txt")
  Copy-RequiredFile (Join-Path $ttsSource "tokens.txt") (Join-Path $ttsDest "tokens.txt")
  Copy-DirectoryContent (Join-Path $ttsSource "dict") (Join-Path $ttsDest "dict")
  foreach ($fst in @("date.fst", "number.fst", "phone.fst", "new_heteronym.fst")) {
    Copy-OptionalFile (Join-Path $ttsSource $fst) (Join-Path $ttsDest $fst) | Out-Null
  }
}

if (!$SkipPose) {
  $poseDest = Join-Path $ModelAssetsRoot "pose_landmarker_lite.task"
  $poseCandidates = @(
    (Join-Path $modelsRoot "vision\pose_landmarker_lite.task"),
    (Join-Path $RepoRoot "artifacts\models\vision\pose_landmarker_lite.task"),
    $poseDest,
    (Join-Path $RepoRoot "android\app\src\main\assets\pose_landmarker_lite.task")
  )
  $poseCopied = $false
  foreach ($candidate in $poseCandidates) {
    if (Copy-OptionalFile $candidate $poseDest) {
      $poseCopied = $true
      break
    }
  }
  if (!$poseCopied) {
    Write-Step "downloading MediaPipe pose landmarker lite"
    try {
      Invoke-WebRequest `
        -Uri "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task" `
        -OutFile $poseDest
      $poseCopied = $true
    } catch {
      Write-Warn "Pose model download failed; camera preview will still work, but real CPR vision will be unavailable. $($_.Exception.Message)"
    }
  }
}

if (!$SkipNlu) {
  $nluOutput = Join-Path $RepoRoot "artifacts\models\nlu\breathing_zh_text_embedder.tflite"
  if (!(Test-Path -LiteralPath $nluOutput) -and !$SkipNluExport) {
    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($python) {
      Write-Step "attempting breathing NLU TextEmbedder export"
      try {
        & $python.Source (Join-Path $RepoRoot "scripts\export_breathing_nlu_text_embedder.py") --output $nluOutput --skip-smoke
        if ($LASTEXITCODE -ne 0) {
          Write-Warn "Breathing NLU export exited with code $LASTEXITCODE"
        }
      } catch {
        Write-Warn "Breathing NLU export failed; rule-based fallback remains available. $($_.Exception.Message)"
      }
    } else {
      Write-Warn "python not found; skipping breathing NLU export"
    }
  }
  $nluDest = Join-Path $assetsModelsRoot "nlu\breathing_zh_text_embedder.tflite"
  if (!(Copy-OptionalFile $nluOutput $nluDest)) {
    $altNlu = Join-Path $modelsRoot "nlu\breathing_zh_text_embedder.tflite"
    if (!(Copy-OptionalFile $altNlu $nluDest)) {
      Write-Warn "Breathing NLU TFLite model is missing; app will use rule-based fallback."
    }
  }
}

$entries = New-Object System.Collections.Generic.List[object]
foreach ($file in Get-ChildItem -LiteralPath $assetsModelsRoot -Recurse -File) {
  $relative = Get-RelativePath $assetsModelsRoot $file.FullName
  if ($relative -like "gemma\*.part*" -or $relative -eq "gemma\gemma-4-E2B-it.litertlm.parts.json" -or $relative -eq "apk_model_bundle.json") {
    continue
  }
  $entryAsset = "models/" + $relative.Replace('\', '/')
  $entryTarget = $relative.Replace('\', '/')
  $entries.Add([ordered]@{
    asset = $entryAsset
    target = $entryTarget
    sizeBytes = [int64]$file.Length
    sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
  })
}

$bundleManifest = [ordered]@{
  version = 1
  generatedAtUtc = [DateTime]::UtcNow.ToString("o")
  deployVersion = "offline-apk-v1"
  entries = $entries
}
$bundleManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $bundleManifestPath -Encoding UTF8

$totalBytes = (Get-ChildItem -LiteralPath $ModelAssetsRoot -Recurse -File | Measure-Object -Property Length -Sum).Sum
Write-Step "model assets ready: $ModelAssetsRoot"
Write-Step "asset bytes: $totalBytes"
