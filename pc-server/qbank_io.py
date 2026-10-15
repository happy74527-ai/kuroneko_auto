"""題庫匯出／匯入：data/qbank.jsonl.gz（放進 git，接手的人不用重新爬）。

每行一筆 JSON：
  {"t": "q", "question": ..., "options": [...], "answer": ..., "genre": ..., "source": ..., "added": ...}
  {"t": "crawled", "keyword": ..., "at": ...}     # 背景撈取查過的關鍵字，匯入後可以接著撈

用法：
  .venv\\Scripts\\python qbank_io.py export   # data/qbank.sqlite → data/qbank.jsonl.gz
  .venv\\Scripts\\python qbank_io.py import   # data/qbank.jsonl.gz → data/qbank.sqlite（已存在的題目略過）
伺服器啟動時若 qbank.sqlite 是空的，會自動匯入（見 qbank.QBank）。
"""
import gzip
import json
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.environ.get("QBANK_DATA_DIR", os.path.join(os.path.dirname(HERE), "data"))
DB_PATH = os.environ.get("QBANK_PATH", os.path.join(DATA_DIR, "qbank.sqlite"))
DATA_PATH = os.path.join(DATA_DIR, "qbank.jsonl.gz")

SCHEMA = [
    "CREATE TABLE IF NOT EXISTS q (question TEXT PRIMARY KEY, options TEXT, answer TEXT, "
    "genre TEXT, source TEXT, added REAL)",
    "CREATE TABLE IF NOT EXISTS crawled (keyword TEXT PRIMARY KEY, at REAL)",
]


def export(db_path: str = DB_PATH, out_path: str = DATA_PATH) -> tuple[int, int]:
    db = sqlite3.connect(db_path)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    nq = nk = 0
    # 依題目排序、固定 gzip 時間戳，內容沒變時輸出檔也一樣，git 不會出現無意義的變更
    with open(out_path, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as gz:
        for question, options, answer, genre, source, added in db.execute(
            "SELECT question, options, answer, genre, source, added FROM q ORDER BY question"
        ):
            row = {"t": "q", "question": question, "options": options.split("\t"), "answer": answer,
                   "genre": genre, "source": source, "added": added}
            gz.write((json.dumps(row, ensure_ascii=False) + "\n").encode("utf-8"))
            nq += 1
        for keyword, at in db.execute("SELECT keyword, at FROM crawled ORDER BY keyword"):
            gz.write((json.dumps({"t": "crawled", "keyword": keyword, "at": at}, ensure_ascii=False) + "\n").encode("utf-8"))
            nk += 1
    return nq, nk


def import_into(db: sqlite3.Connection, in_path: str = DATA_PATH) -> tuple[int, int]:
    """匯入到已開啟的資料庫；已存在的題目／關鍵字略過。回傳實際新增的（題目數, 關鍵字數）"""
    for sql in SCHEMA:
        db.execute(sql)
    nq = nk = 0
    with gzip.open(in_path, "rt", encoding="utf-8") as f:
        for line in f:
            row = json.loads(line)
            if row["t"] == "q":
                cur = db.execute(
                    "INSERT OR IGNORE INTO q VALUES (?,?,?,?,?,?)",
                    (row["question"], "\t".join(row["options"]), row["answer"], row["genre"],
                     row.get("source", "import"), row.get("added", 0)),
                )
                nq += cur.rowcount
            elif row["t"] == "crawled":
                nk += db.execute("INSERT OR IGNORE INTO crawled VALUES (?, ?)", (row["keyword"], row["at"])).rowcount
    db.commit()
    return nq, nk


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "export":
        q, k = export()
        print(f"exported {q} questions, {k} crawled keywords -> {DATA_PATH} ({os.path.getsize(DATA_PATH) / 1e6:.1f} MB)")
    elif cmd == "import":
        q, k = import_into(sqlite3.connect(DB_PATH))
        print(f"imported {q} questions, {k} crawled keywords -> {DB_PATH}")
    else:
        print(__doc__)
