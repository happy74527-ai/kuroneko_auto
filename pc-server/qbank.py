"""黒猫のウィズ 題庫：本機 SQLite＋線上補查（kuronekonowiz.main.jp，約 10 萬題 4 択）。

- lookup()：只查本機（記憶體內，幾毫秒）。
- remote_lookup()：上網站查這一題，查回來的整批（最多 100 題）都存進本機。
- 比對：題目相似度＋選項重疊；答案用假名讀音比對（「獅子座」＝「しし座」）。
"""
import os
import re
import socket
import sqlite3
import ssl
import threading
import time
import unicodedata
import urllib.parse
from dataclasses import dataclass

import pykakasi
from rapidfuzz import fuzz, process

SITE = "kuronekonowiz.main.jp"
SEARCH_PATH = "/cgi-bin/search2.cgi"

_kks = pykakasi.kakasi()
_PUNCT = re.compile(r"[\s、。，,．.！!？?・「」『』（）()【】\[\]〈〉《》“”\"'：:；;…~〜～/／-]")


_SMALL_KANA = str.maketrans("ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ", "あいうえおつやゆよわアイウエオツヤユヨワ")


def _is_kata(c: str) -> bool:
    return "ァ" <= c <= "ヶ"


def _is_kanji(c: str) -> bool:
    return "一" <= c <= "龥" or c == "々"


def fix_ocr(s: str) -> str:
    """OCR 常把長音「ー」和漢字「一」互相讀錯：片假名後面的「一」是長音（サッカ一→サッカー），
    漢字前面、不是接在片假名後面的「ー」是漢字一（ー斉→一斉）"""
    s = unicodedata.normalize("NFKC", s)
    out = []
    for i, c in enumerate(s):
        prev = out[-1] if out else ""
        nxt = s[i + 1] if i + 1 < len(s) else ""
        if c == "一" and _is_kata(prev):
            c = "ー"
        elif c in "ー-" and not _is_kata(prev) and _is_kanji(nxt):
            c = "一"
        out.append(c)
    return "".join(out)


def normalize(s: str) -> str:
    """校正 OCR 誤讀、NFKC、去空白和標點、大小假名統一（くまちぃ＝くまちい）、片假名轉平假名"""
    s = _PUNCT.sub("", fix_ocr(s).lower()).translate(_SMALL_KANA)
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in s)


def reading(s: str) -> str:
    """整串轉成平假名讀音，漢字和假名寫法不同也能比對（獅子座＝しし座）"""
    n = normalize(s)
    return "".join(item["hira"] for item in _kks.convert(n)) or n


_KEY = re.compile(
    r"\d+|[一二三四五六七八九十百千万]|番目|最も|最初|最後|以外|ない|でない|ではない|誤り|間違|正しくない|"
    r"不是|非|錯誤|大き|小さ|多い|少な|高い|低い|長い|短い|広い|狭い|古い|新し|重い|軽い|早い|遅い|"
    r"北|南|東|西|上|下|前|後|左|右"
)


def key_tokens(question: str) -> list[str]:
    """意思會因為一兩個字而改變的詞：數字、序數、否定、比較、方位（排序後比較）"""
    return sorted(_KEY.findall(fix_ocr(question)))


@dataclass
class Row:
    question: str
    options: list[str]
    answer: str
    genre: str
    nq: str               # normalize(question)
    opt_read: list[str]   # 各選項讀音
    ans_read: str


@dataclass
class Hit:
    index: int            # 對應到呼叫端第幾個選項
    question: str         # 題庫裡的題目原文
    answer: str
    score: float


# 題目、類型、正答率、選項、正解
_ITEM = re.compile(
    r"\[問題\d+\]&nbsp;&nbsp;(.*?)（([^（）：]+)：\d+%）<br>\[選択肢\](.*?)<br><br>"
    r"\[正解\]&nbsp;&nbsp;<font[^>]*>(.*?)</font>",
    re.S,
)
_TAG = re.compile(r"<[^>]+>")


def decode_http_body(raw: bytes) -> bytes:
    """去掉 HTTP 標頭；網站用分段傳輸（Transfer-Encoding: chunked），每段前面有「長度\\r\\n」，
    沒去掉的話題目、選項會混進「\\r\\n1f40\\r\\n」，還會把日文字切壞。可以處理還沒收完的資料。"""
    head, sep, body = raw.partition(b"\r\n\r\n")
    if not sep:
        return b""
    if b"chunked" not in head.lower():
        return body
    out = bytearray()
    pos = 0
    while True:
        eol = body.find(b"\r\n", pos)
        if eol < 0:
            break
        try:
            size = int(body[pos:eol].split(b";")[0], 16)
        except ValueError:
            break
        if size == 0:
            break
        start = eol + 2
        out += body[start:start + size]
        if start + size + 2 > len(body):
            break  # 這一段還沒收完
        pos = start + size + 2
    return bytes(out)


def parse_results(html: str) -> list[tuple[str, list[str], str, str]]:
    out = []
    for q, genre, opts, ans in _ITEM.findall(html):
        options = [_TAG.sub("", o).replace("&nbsp;", " ").strip() for o in opts.split("<B>/</B>")]
        options = [o for o in options if o]
        if len(options) == 4:
            out.append((_TAG.sub("", q).strip(), options, _TAG.sub("", ans).strip(), genre))
    return out


class QBank:
    def __init__(self, path: str):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS q (question TEXT PRIMARY KEY, options TEXT, answer TEXT, "
            "genre TEXT, source TEXT, added REAL)"
        )
        # 背景撈題庫用過的關鍵字（可中斷、下次接著撈）
        self.db.execute("CREATE TABLE IF NOT EXISTS crawled (keyword TEXT PRIMARY KEY, at REAL)")
        # 新環境（例如剛從 git clone）題庫是空的：從專案附的 data/qbank.jsonl.gz 匯入，不用重新爬
        if self.db.execute("SELECT COUNT(*) FROM q").fetchone()[0] == 0:
            import qbank_io
            if os.path.exists(qbank_io.DATA_PATH):
                nq, nk = qbank_io.import_into(self.db)
                print(f"qbank: imported {nq} questions, {nk} crawled keywords from {qbank_io.DATA_PATH}")
        self.lock = threading.Lock()
        self.crawl_stop = threading.Event()
        self.crawl_thread: threading.Thread | None = None
        self.crawl_stats = {"queries": 0, "added": 0, "last_keyword": ""}
        self.rows: list[Row] = []
        self.nqs: list[str] = []
        self.by_option: dict[str, set[int]] = {}
        for question, options, answer, genre in self.db.execute("SELECT question, options, answer, genre FROM q"):
            self._index(self._make_row(question, options.split("\t"), answer, genre))

    def __len__(self):
        return len(self.rows)

    @staticmethod
    def _make_row(question, options, answer, genre) -> Row:
        return Row(question, options, answer, genre, normalize(question), [reading(o) for o in options], reading(answer))

    def _index(self, row: Row):
        i = len(self.rows)
        self.rows.append(row)
        self.nqs.append(row.nq)
        for r in row.opt_read:
            self.by_option.setdefault(r, set()).add(i)

    def add_many(self, items, source: str) -> int:
        """新增題目，回傳實際新增的數量（題目原文相同的略過）。
        轉讀音比較慢（每題數毫秒），在鎖外面先算好，鎖住的時間只有寫入，答題查詢才不會被背景撈取卡住"""
        prepared = [(item, self._make_row(*item)) for item in items]
        added = 0
        with self.lock:
            for (question, options, answer, genre), row in prepared:
                cur = self.db.execute(
                    "INSERT OR IGNORE INTO q VALUES (?,?,?,?,?,?)",
                    (question, "\t".join(options), answer, genre, source, time.time()),
                )
                if cur.rowcount:
                    self._index(row)
                    added += 1
            self.db.commit()
        return added

    # ---- 比對 ----

    def lookup(self, question: str, options: list[str]) -> Hit | None:
        # 背景撈題庫的執行緒會同時新增題目，比對時鎖住索引（比對只要幾毫秒）
        with self.lock:
            return self._lookup(question, options)

    def _lookup(self, question: str, options: list[str]) -> Hit | None:
        nq = normalize(question)
        our = [reading(o) for o in options]
        cands = set()
        for r in our:
            cands |= self.by_option.get(r, set())
        # 題目換了寫法、選項也不完全一樣時，再用題目相似度找
        for _, _, i in process.extract(nq, self.nqs, scorer=fuzz.ratio, limit=5, score_cutoff=60):
            cands.add(i)
        best = None
        for i in cands:
            row = self.rows[i]
            idx = self._answer_index(row.ans_read, our)
            if idx is None:
                continue
            overlap = sum(1 for r in our if any(fuzz.ratio(r, x) >= 85 for x in row.opt_read))
            q_sim = fuzz.ratio(nq, row.nq)
            # 寧可查不到也不能配錯（題庫答案會蓋掉 d3）：
            # 「一番」和「二番目」、「大きい」和「小さい」只差一兩個字，相似度很高但答案不同，所以關鍵字一定要一致；
            # 題目幾乎一樣，或題目很像而且四個選項完全相同才算
            if key_tokens(question) != key_tokens(row.question):
                continue
            if not (q_sim >= 92 or (q_sim >= 75 and overlap == len(our) == len(row.opt_read))):
                continue
            score = q_sim + 10 * overlap
            if best is None or score > best.score:
                best = Hit(idx, row.question, row.answer, score)
        return best

    def related(self, question: str, options: list[str], k: int = 3) -> list[tuple[str, str]]:
        """沒有完全相同的題目時，找意思相近的題目當作 d3 的參考資料（不直接拿來作答）：
        題目寫法相近的，或答案剛好是我們其中一個選項、題目也有一定相似度的"""
        with self.lock:
            nq = normalize(question)
            our = [reading(o) for o in options]
            scored: dict[int, float] = {}
            for _, s, i in process.extract(nq, self.nqs, scorer=fuzz.ratio, limit=k * 2, score_cutoff=55):
                scored[i] = s
            for r in our:
                for i in self.by_option.get(r, set()):
                    row = self.rows[i]
                    if any(fuzz.ratio(row.ans_read, x) >= 90 for x in our):
                        s = fuzz.partial_ratio(nq, row.nq)
                        if s >= 45:
                            scored[i] = max(scored.get(i, 0), s)
            best = sorted(scored.items(), key=lambda kv: -kv[1])[:k]
            return [(self.rows[i].question, self.rows[i].answer) for i, _ in best]

    @staticmethod
    def _answer_index(ans_read: str, our: list[str]) -> int | None:
        scores = [fuzz.ratio(ans_read, r) for r in our]
        top = max(scores)
        if top < 85 or scores.count(top) > 1:
            return None
        return scores.index(top)

    # ---- 線上查詢 ----

    @staticmethod
    def keywords(question: str, options: list[str]) -> list[str]:
        """網站是用子字串搜尋。題目換了寫法時，專有名詞（片假名、漢字詞）和選項本身最可能命中；
        不知道哪個選項才對，所以四個選項都查（平行送出，總時間約等於查一次）"""
        q = unicodedata.normalize("NFKC", question)
        runs = re.findall(r"[ァ-ヶー]{3,}|[一-龥々]{3,}|[A-Za-z0-9]{4,}", q)
        runs.sort(key=len, reverse=True)
        out = []
        for k in runs[:2] + [o.strip() for o in options if len(o.strip()) >= 2]:
            if k not in out:
                out.append(k)
        return out

    def search_site(self, keyword: str, timeout: float = 3.0, fmt: str = "4択", genre: str = "all") -> list:
        """結果大約 0.8 秒就送完，但網站要再拖 3 秒才關連線；讀到「找到 N 題」的 N 題就提早斷線"""
        body = urllib.parse.urlencode(
            {"keyword": keyword, "Sformat": fmt, "Sgenre": genre, "search": "x"}, encoding="cp932", errors="ignore"
        ).encode()
        req = (
            f"POST {SEARCH_PATH} HTTP/1.1\r\nHost: {SITE}\r\nUser-Agent: Mozilla/5.0\r\n"
            f"Referer: https://{SITE}/wiz_search_top.html\r\n"
            f"Content-Type: application/x-www-form-urlencoded\r\nContent-Length: {len(body)}\r\n"
            "Connection: close\r\n\r\n"
        ).encode() + body
        deadline = time.time() + timeout
        sock = ssl.create_default_context().wrap_socket(
            socket.create_connection((SITE, 443), timeout=timeout), server_hostname=SITE
        )
        buf = b""
        expected = None
        try:
            sock.sendall(req)
            while time.time() < deadline:
                sock.settimeout(max(0.05, deadline - time.time()))
                chunk = sock.recv(65536)
                if not chunk:
                    break
                buf += chunk
                text = decode_http_body(buf).decode("cp932", "replace")
                if expected is None:
                    m = re.search(r"(\d+)問見つかりました", text)
                    if m:
                        expected = int(m.group(1))
                    elif "100問を超えました" in text:
                        expected = 100
                    elif "見つかりませんでした" in text or "0問" in text:
                        return []
                if expected is not None and text.count("</font><hr>") >= expected:
                    break
        except (socket.timeout, OSError):
            pass
        finally:
            sock.close()
        return parse_results(decode_http_body(buf).decode("cp932", "replace"))

    def remote_lookup(self, question: str, options: list[str], timeout: float = 2.5) -> Hit | None:
        """所有關鍵字平行查詢；每查回一批就存進本機並比對一次，命中就提早返回（其他查詢在背景跑完、照樣存檔）"""
        found = threading.Event()
        result: list[Hit] = []

        def worker(kw):
            try:
                items = self.search_site(kw, timeout=timeout)
            except OSError:
                return
            if items:
                self.add_many(items, source=f"site:{kw}")
                hit = self.lookup(question, options)
                if hit and not found.is_set():
                    result.append(hit)
                    found.set()

        kws = self.keywords(question, options)
        with self.lock:
            self.db.executemany("INSERT OR IGNORE INTO crawled VALUES (?, ?)", [(k, time.time()) for k in kws])
            self.db.commit()
        threads = [threading.Thread(target=worker, args=(kw,), daemon=True) for kw in kws]
        for t in threads:
            t.start()
        deadline = time.time() + timeout
        while time.time() < deadline and not found.is_set() and any(t.is_alive() for t in threads):
            found.wait(0.05)
        return result[0] if result else None

    # ---- 背景撈題庫 ----

    SEEDS = ["の", "は", "ある", "どれ", "何", "誰", "次", "日本", "世界", "アニメ", "映画", "漫画", "スポーツ", "歌手"]

    def _next_keywords(self, limit: int) -> list[str]:
        """從已收錄題目裡挑還沒查過的詞：片假名詞、漢字詞、選項本身（越常出現的越先查）"""
        # 已經查過的關鍵字（含「關鍵字|類型」，查過全部類型的才算）
        done = {k for (k,) in self.db.execute("SELECT keyword FROM crawled") if "|" not in k}
        counts: dict[str, int] = {}
        for k in self.SEEDS:
            counts[k] = 10**9
        for row in self.rows:
            text = unicodedata.normalize("NFKC", row.question)
            for k in re.findall(r"[ァ-ヶー]{3,}|[一-龥々]{2,}", text) + [o for o in row.options if len(o) >= 2]:
                counts[k] = counts.get(k, 0) + 1
        cands = [k for k in counts if k not in done]
        cands.sort(key=lambda k: -counts[k])
        return cands[:limit]

    # 網站的類型篩選；一個關鍵字查到滿 100 題（後面還有）時，再按類型分開查，每類又是不同的 100 題
    GENRES = ["芸能", "アニメゲーム", "文系", "理系", "雑学", "スポーツ", "英語", "計算", "ニュース", "漢字"]

    def _crawl_one(self, kw: str, genre: str, interval: float) -> int:
        """查一次並記錄；回傳查到的筆數（100 代表被截斷了）"""
        try:
            items = self.search_site(kw, timeout=6.0, genre=genre)
        except Exception:  # 單次查詢出錯（網路、編碼）只跳過這個關鍵字，不能讓整個撈取停掉
            items = []
        key = kw if genre == "all" else f"{kw}|{genre}"
        added = self.add_many(items, source=f"crawl:{key}") if items else 0
        with self.lock:
            self.db.execute("INSERT OR IGNORE INTO crawled VALUES (?, ?)", (key, time.time()))
            self.db.commit()
        self.crawl_stats["queries"] += 1
        self.crawl_stats["added"] += added
        self.crawl_stats["last_keyword"] = key
        self.crawl_stop.wait(interval)
        return len(items)

    def _revisit_saturated(self, interval: float):
        """早期查過的關鍵字還沒按類型分開查：先重查一次，滿 100 題的再按類型補查（每個關鍵字只做一次）"""
        rows = self.db.execute("SELECT keyword FROM crawled WHERE keyword NOT LIKE '%|%' ORDER BY at").fetchall()
        checked = {k for (k,) in self.db.execute("SELECT keyword FROM crawled WHERE keyword LIKE '%|checked'")}
        for (kw,) in rows:
            if self.crawl_stop.is_set():
                return
            if f"{kw}|checked" in checked:
                continue
            try:
                saturated = len(self.search_site(kw, timeout=6.0)) >= 100
            except Exception:
                saturated = False
            self.crawl_stop.wait(interval)
            if saturated:
                done = {k for (k,) in self.db.execute("SELECT keyword FROM crawled WHERE keyword LIKE ?", (f"{kw}|%",))}
                for g in self.GENRES:
                    if self.crawl_stop.is_set():
                        return
                    if f"{kw}|{g}" not in done:
                        self._crawl_one(kw, g, interval)
            with self.lock:
                self.db.execute("INSERT OR IGNORE INTO crawled VALUES (?, ?)", (f"{kw}|checked", time.time()))
                self.db.commit()

    def crawl(self, interval: float = 3.0):
        """一次查一個關鍵字、每次間隔 interval 秒，盡量不給網站負擔；直到 crawl_stop 被設定"""
        self._revisit_saturated(interval)
        while not self.crawl_stop.is_set():
            batch = self._next_keywords(50)
            if not batch:
                break
            for kw in batch:
                if self.crawl_stop.is_set():
                    break
                saturated = self._crawl_one(kw, "all", interval) >= 100
                with self.lock:
                    self.db.execute("INSERT OR IGNORE INTO crawled VALUES (?, ?)", (f"{kw}|checked", time.time()))
                    self.db.commit()
                if not saturated:
                    continue
                done = {k for (k,) in self.db.execute("SELECT keyword FROM crawled WHERE keyword LIKE ?", (f"{kw}|%",))}
                for g in self.GENRES:
                    if self.crawl_stop.is_set():
                        break
                    if f"{kw}|{g}" not in done:
                        self._crawl_one(kw, g, interval)

    def start_crawl(self, interval: float = 3.0) -> bool:
        if self.crawl_thread and self.crawl_thread.is_alive():
            return False
        self.crawl_stop.clear()
        self.crawl_thread = threading.Thread(target=self.crawl, args=(interval,), daemon=True)
        self.crawl_thread.start()
        return True
