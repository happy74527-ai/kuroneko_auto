"""PP-OCRv6 small 的 OCR 服務，給手機上的答題浮窗呼叫（取代裝置端 ML Kit）。

POST /v1/ocr   body: 圖片（JPEG/PNG 原始位元組）
回傳 {"lines": [{"text": "...", "box": [l, t, r, b], "score": 0.99}, ...], "ms": 42.0}

同一個模型就能讀中文（簡繁）、日文、英文，不用先判斷語言。
"""
import io
import os
import time

import numpy as np
import onnxruntime as ort
from fastapi import FastAPI, HTTPException, Request
from PIL import Image

try:
    ort.preload_dlls()  # 載入 pip 安裝的 CUDA / cuDNN（onnxruntime-gpu[cuda,cudnn]）
except Exception:
    pass

from paddleocr import TextDetection, TextRecognition  # noqa: E402

DEVICE = "gpu" if "CUDAExecutionProvider" in ort.get_available_providers() else "cpu"
det = TextDetection(model_name="PP-OCRv6_small_det", engine="onnxruntime", device=DEVICE,
                    limit_side_len=960, limit_type="max")
rec = TextRecognition(model_name="PP-OCRv6_small_rec", engine="onnxruntime", device=DEVICE)
print(f"PP-OCRv6 small ready on {DEVICE}")

app = FastAPI()


@app.get("/health")
def health():
    return {"device": DEVICE}


API_TOKEN = os.environ.get("API_TOKEN", "")


@app.post("/v1/ocr")
async def ocr(request: Request):
    # 對外開放時要設 API_TOKEN（與 d3 伺服器共用），手機 App 的 API Key 填一樣的值
    if API_TOKEN and request.headers.get("authorization") != f"Bearer {API_TOKEN}":
        raise HTTPException(status_code=401, detail="invalid token")
    data = await request.body()
    img = np.array(Image.open(io.BytesIO(data)).convert("RGB"))[:, :, ::-1].copy()  # BGR
    start = time.perf_counter()
    polys = det.predict(img)[0]["dt_polys"]
    boxes, crops = [], []
    for poly in polys:
        xs, ys = poly[:, 0], poly[:, 1]
        l, t, r, b = int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())
        if r - l < 4 or b - t < 4:
            continue
        boxes.append([l, t, r, b])
        crops.append(img[t:b, l:r].copy())
    lines = []
    if crops:
        for box, res in zip(boxes, rec.predict(crops)):
            if res["rec_text"].strip():
                lines.append({"text": res["rec_text"], "box": box, "score": float(res["rec_score"])})
    return {"lines": lines, "ms": round((time.perf_counter() - start) * 1000, 1)}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=os.environ.get("OCR_HOST", "0.0.0.0"), port=int(os.environ.get("OCR_PORT", "8767")))
