# 建立 OCR 伺服器的 Python 環境（不影響全域 Python）。
#   預設：.venv-gpu，onnxruntime-gpu＋pip 版 CUDA/cuDNN（需要 NVIDIA 顯示卡，下載約 1–2GB，可能要等 20 分鐘以上）
#   -Cpu ：.venv，onnxruntime CPU 版（沒有 NVIDIA 顯示卡時用；每次 OCR 約 0.2–0.5 秒，GPU 約 0.1 秒）
param([switch]$Cpu)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$venv = if ($Cpu) { ".venv" } else { ".venv-gpu" }
python -m venv $venv
& ".\$venv\Scripts\python" -m pip install --upgrade pip
& ".\$venv\Scripts\pip" install -r requirements.txt
if ($Cpu) {
    & ".\$venv\Scripts\pip" install onnxruntime==1.31.0
} else {
    & ".\$venv\Scripts\pip" install "onnxruntime-gpu[cuda,cudnn]==1.31.0"
}
# 第一次啟動 run.ps1 時會自動下載 PP-OCRv6 small 模型到 .model-cache
