"""d3 決策模型的 System One 相容伺服器，給手機上的答題浮窗呼叫。

POST /v1/systemone  {"state": "...", "questions": {...}, "images": ["<base64>", ...]}

單一選擇題（手機 App 送的格式）會先查黒猫のウィズ題庫（qbank.py）：
  本機題庫命中 → 直接用題庫答案
  沒命中 → 同時問 d3、上網站查；d3 信心 >= REMOTE_SKIP_CONF 就用 d3，否則等網站結果（最多 REMOTE_WAIT 秒）
回應多一個 "source" 欄位：qbank / qbank-web / d3。

題庫管理：
  GET  /v1/qbank/stats          題庫題數、背景撈題庫的進度
  POST /v1/qbank/crawl/start    開始背景撈題庫（每次查詢間隔 interval 秒）
  POST /v1/qbank/crawl/stop
  POST /v1/qbank/learn          {"question", "options", "answer"} 手動或自動學到的正確答案
"""
import base64
import io
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import torch
from fastapi import FastAPI, Header, HTTPException
from PIL import Image
from pydantic import BaseModel
from transformers import AutoModel

import qbank_io
from qbank import QBank

MODEL_ID = os.environ.get("D3_MODEL", "vllm-sr/d3-mini")
API_TOKEN = os.environ.get("API_TOKEN", "")
# 題庫 SQLite 放在專案的 data/（和 git 裡的 data/qbank.jsonl.gz 同一個資料夾；docker 版掛載成 /data）
QBANK_PATH = os.environ.get("QBANK_PATH", qbank_io.DB_PATH)
# 0 = 答題時不連網站，只查本機題庫（題庫撈完之後用）
QBANK_ONLINE = os.environ.get("QBANK_ONLINE", "1") != "0"
# 1 = 伺服器啟動就在背景接著撈題庫（已查過的關鍵字有記錄，會從上次的進度繼續）
QBANK_CRAWL = os.environ.get("QBANK_CRAWL", "0") == "1"
# d3 有這麼高的信心就不等網站
REMOTE_SKIP_CONF = 0.9
# 從收到請求開始，最多等網站多久（秒）；黑貓維茲要在 5 秒內作答
REMOTE_WAIT = 1.5

print(f"loading {MODEL_ID} ...")
model = AutoModel.from_pretrained(MODEL_ID, trust_remote_code=True, dtype=torch.bfloat16).to("cuda").eval()
gpu_lock = threading.Lock()
bank = QBank(QBANK_PATH)
print(f"qbank: {len(bank)} questions ({QBANK_PATH}), online={QBANK_ONLINE}, crawl={QBANK_CRAWL}")
if QBANK_CRAWL:
    bank.start_crawl()
pool = ThreadPoolExecutor(max_workers=4)

app = FastAPI()


class SystemOneRequest(BaseModel):
    state: str | None = None
    questions: dict
    images: list[str] | None = None
    model: str | None = None
    # 測試用：full（預設）＝題庫＋參考資料＋線上；no_hint＝題庫但不附參考；d3＝只用 d3；
    # local＝不連網站（評測時用）
    bank_mode: str = "full"
    online: bool = True


class LearnRequest(BaseModel):
    question: str
    options: list[str]
    answer: str
    genre: str = ""


def to_jsonable(x):
    if isinstance(x, dict):
        return {k: to_jsonable(v) for k, v in x.items()}
    if isinstance(x, (list, tuple)):
        return [to_jsonable(v) for v in x]
    if hasattr(x, "tolist"):
        return x.tolist()
    return x


def check_token(authorization):
    if API_TOKEN and authorization != f"Bearer {API_TOKEN}":
        raise HTTPException(status_code=401, detail="invalid token")


def single_choice(req: SystemOneRequest):
    """手機 App 送的格式：只有一題 choice、選項代號 → 選項文字。其他格式不走題庫"""
    if req.images or not req.state or len(req.questions) != 1:
        return None
    (name, q), = req.questions.items()
    criteria = q.get("criteria")
    if q.get("type") != "choice" or not isinstance(criteria, dict) or len(criteria) < 2:
        return None
    return name, list(criteria.keys()), [str(v) for v in criteria.values()]


def bank_answer(name, keys, hit, source):
    probs = {k: (1.0 if i == hit.index else 0.0) for i, k in enumerate(keys)}
    return {
        "answers": {name: {"type": "choice", "choice": keys[hit.index], "probabilities": probs, "confidence": 1.0}},
        "source": source,
        "bank_question": hit.question,
        "bank_answer": hit.answer,
    }


def run_d3(req: SystemOneRequest):
    kwargs = {}
    if req.images:
        kwargs["images"] = [
            Image.open(io.BytesIO(base64.b64decode(b.split(",")[-1]))).convert("RGB") for b in req.images
        ]
    with gpu_lock, torch.inference_mode():
        return to_jsonable(model.system_one(state=req.state, questions=req.questions, **kwargs)["answers"])


@app.get("/health")
def health():
    return {"model": MODEL_ID, "qbank": len(bank)}


@app.post("/v1/systemone")
def system_one(req: SystemOneRequest, authorization: str | None = Header(default=None)):
    check_token(authorization)
    start = time.perf_counter()
    choice = single_choice(req)

    def done(body):
        body["latency_ms"] = round((time.perf_counter() - start) * 1000, 1)
        return body

    if choice is None:
        return done({"answers": run_d3(req), "source": "d3"})

    name, keys, options = choice
    if req.bank_mode == "d3":
        return done({"answers": run_d3(req), "source": "d3"})
    hit = bank.lookup(req.state, options)
    if hit:
        return done(bank_answer(name, keys, hit, "qbank"))

    # 網站查詢和 d3 同時進行；網站查回來的題目不管有沒有用到都會存進本機題庫
    online = QBANK_ONLINE and req.online
    remote = pool.submit(bank.remote_lookup, req.state, options, REMOTE_WAIT) if online else None

    # 題庫沒有完全相同的題目：把意思相近的題目和正解附在題目後面給 d3 參考，最後仍由 d3 判斷
    hints = bank.related(req.state, options) if req.bank_mode == "full" else []
    d3_req = req
    if hints:
        ref = "\n".join(f"・{q} → {a}" for q, a in hints)
        d3_req = req.model_copy(update={"state": f"{req.state}\n\n（参考：似た過去問と正解）\n{ref}"})
    answers = run_d3(d3_req)
    conf = float(answers.get(name, {}).get("confidence", 0.0))
    if remote and conf < REMOTE_SKIP_CONF:
        try:
            hit = remote.result(timeout=max(0.0, REMOTE_WAIT - (time.perf_counter() - start)))
        except Exception:
            hit = None
        if hit:
            return done(bank_answer(name, keys, hit, "qbank-web"))
    return done({"answers": answers, "source": "d3+hint" if hints else "d3", "hints": hints})


@app.get("/v1/qbank/stats")
def qbank_stats():
    crawling = bool(bank.crawl_thread and bank.crawl_thread.is_alive())
    return {"questions": len(bank), "crawling": crawling, **bank.crawl_stats}


@app.post("/v1/qbank/crawl/start")
def crawl_start(interval: float = 3.0, authorization: str | None = Header(default=None)):
    check_token(authorization)
    return {"started": bank.start_crawl(max(1.0, interval))}


@app.post("/v1/qbank/crawl/stop")
def crawl_stop(authorization: str | None = Header(default=None)):
    check_token(authorization)
    bank.crawl_stop.set()
    return {"stopping": True}


@app.post("/v1/qbank/learn")
def qbank_learn(req: LearnRequest, authorization: str | None = Header(default=None)):
    check_token(authorization)
    added = bank.add_many([(req.question, req.options, req.answer, req.genre)], source="learn")
    return {"added": added, "questions": len(bank)}
