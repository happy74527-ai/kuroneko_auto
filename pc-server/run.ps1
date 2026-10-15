# 啟動 d3 決策伺服器（含黒猫のウィズ題庫）。設定在專案根目錄的 .env（參考 .env.example）。
# 模型快取放在 pc-server/.hf-cache，不寫進使用者全域的 HuggingFace 快取。
Set-Location $PSScriptRoot
. (Join-Path $PSScriptRoot "..\scripts\load-env.ps1")
$env:HF_HOME = "$PSScriptRoot\.hf-cache"
if (-not $env:QBANK_CRAWL) { $env:QBANK_CRAWL = "1" }
$hostAddr = if ($env:D3_HOST) { $env:D3_HOST } else { "0.0.0.0" }
$port = if ($env:D3_PORT) { $env:D3_PORT } else { "8765" }
.\.venv\Scripts\python -m uvicorn server:app --host $hostAddr --port $port
