# 在 pc-server/.venv 建立獨立的 Python 環境，不影響全域 Python。
# RTX 50 系列（Blackwell）需要 CUDA 12.8 版的 PyTorch。
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
python -m venv .venv
.\.venv\Scripts\python -m pip install --upgrade pip
.\.venv\Scripts\pip install torch==2.11.0 torchvision==0.26.0 --index-url https://download.pytorch.org/whl/cu128
.\.venv\Scripts\pip install -r requirements.txt
