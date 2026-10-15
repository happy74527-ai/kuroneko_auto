# PP-OCRv6 small OCR 伺服器（GPU）。由專案根目錄的 docker-compose.yml 建置與啟動。
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    PADDLE_PDX_CACHE_HOME=/cache/paddle \
    PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True \
    OCR_HOST=0.0.0.0 OCR_PORT=8767

# paddlex 用到的 OpenCV 需要這兩個系統函式庫
RUN apt-get update && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && pip install --no-cache-dir "onnxruntime-gpu[cuda,cudnn]==1.31.0"
COPY ocr_server.py .

EXPOSE 8767
CMD ["python", "ocr_server.py"]
