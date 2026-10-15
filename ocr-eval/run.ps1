# 啟動 PP-OCRv6 small OCR 伺服器。設定在專案根目錄的 .env（OCR_HOST、OCR_PORT、API_TOKEN）。
# 模型快取放在 ocr-eval/.model-cache；有 .venv-gpu（onnxruntime-gpu）就用 GPU，否則用 .venv（CPU）。
Set-Location $PSScriptRoot
. (Join-Path $PSScriptRoot "..\scripts\load-env.ps1")
$env:PADDLE_PDX_CACHE_HOME = "$PSScriptRoot\.model-cache"
$env:PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK = "True"
$py = if (Test-Path .\.venv-gpu\Scripts\python.exe) { ".\.venv-gpu\Scripts\python.exe" } else { ".\.venv\Scripts\python.exe" }
& $py -I ocr_server.py
