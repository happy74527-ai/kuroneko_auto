# d3 決策伺服器（含黒猫のウィズ題庫）。由專案根目錄的 docker-compose.yml 建置與啟動。
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    HF_HOME=/cache/hf \
    QBANK_DATA_DIR=/data

WORKDIR /app
# RTX 50 系列（Blackwell）需要 CUDA 12.8 版 PyTorch；pip 套件自帶 CUDA 執行環境，主機只需要顯示卡驅動
RUN pip install --no-cache-dir torch==2.11.0 torchvision==0.26.0 --index-url https://download.pytorch.org/whl/cu128
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY server.py qbank.py qbank_io.py ./

EXPOSE 8765
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8765"]
