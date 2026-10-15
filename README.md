# kuroneko_auto — Android 自動答題浮窗

Android 浮窗 App：持續擷取手機螢幕，讀出題目和選項，交給題庫和決策模型（d3）選出答案，並自動點擊。
主要目標是單人答題 RPG **魔法使いと黒猫のウィズ（黑貓維茲）**，日文版與中文版都能用，也會依屬性相剋自動選題目類型。

> 架構是「手機擷取＋PC 推論」：OCR 與決策模型跑在 PC 的 NVIDIA 顯示卡上（docker compose），手機要連得到這台 PC（同一個區網、Tailscale，或對外開放）。

**目錄**：[目前進度](#目前進度) · [架構](#架構) · [使用教學（從零開始）](#使用教學從零開始) · [常見問題](#常見問題) · [設定參考](#設定參考) · [題庫](#題庫) · [測試](#測試) · [開發筆記](#開發筆記踩過的坑)

---

## 目前進度

### 已完成

| 功能 | 說明 |
|---|---|
| 浮窗 App | 可拖曳的浮窗；顯示答案、每個選項的機率、答案來源（題庫／計算／d3）、耗時；建議答案旁顯示圓點 |
| 螢幕擷取與換題偵測 | 原始解析度擷取；畫面指紋偵測換題；背景一直有動畫（燭火、技能水泡）時最多 1.2 秒強制處理一次 |
| OCR | PC 上的 **PP-OCRv6 small**（中／日／英同一個模型，GPU 約 0.1 秒）；也可改用手機端 ML Kit（較不準） |
| 題目解析 | 題目＝問句或含疑問詞；選項＝題目下方排列整齊的 4 個區塊（直排或 2x2）；處理換行、被擠到下一行的「?」、OCR 字形誤讀 |
| 決策 | **本機題庫 → d3＋相近題目參考 → 網站即時查詢**；算數題直接計算 |
| 題庫 | 約 6.6 萬題黑貓維茲（日文版）4 択題，附在 `data/qbank.jsonl.gz`，第一次啟動自動匯入 |
| 自動點擊 | 無障礙服務點擊；信心低也點機率最高的（可關）；確認對話框、選單畫面不點 |
| 黑貓維茲自動選類型 | 讀取我方隊伍、敵人、四個面板的屬性顏色，依「我方有的顏色 → 剋制敵人 → 我方人數」選面板 |
| 部署 | **docker compose**（GPU）＋ `.env` 設定；也可用 PowerShell 腳本＋Python 虛擬環境原生執行 |
| 測試工具 | 模擬題目頁、模擬器自動測試腳本、三語常識題評測、日文版實題評測 |

### 實測結果（Android 14 模擬器，RTX 5070 Ti）

- 黑貓維茲日文版實際出過的 22 題：**只用 d3 19/22 → 題庫＋參考 21/22**；題庫命中 13 題、答案全部正確
- 黑貓維茲中文版新手關卡：從選類型到通關全自動
- 模擬題目頁（中／日／英／算數）：11/11，題目出現到點擊約 0.5–1 秒
- 每題 OCR 約 0.1–0.4 秒，判斷約 0.05–0.5 秒（d3 沒把握時最多再等網站 1.5 秒）

### 還沒實作／已知限制

| 項目 | 現況 | 建議做法 |
|---|---|---|
| **自動學習正確答案** | **未實作**。伺服器已有 `POST /v1/qbank/learn`，但 App 不會讀取遊戲揭曉的正確答案 | 作答後擷取「正解／不正解」畫面，讀出正確選項，送到 `/v1/qbank/learn` |
| 題庫覆蓋率 | 實際題目約一半能直接命中；網站題庫的背景撈取已接近飽和 | 自動學習；或在 d3 低信心時改問 Gemini 等大模型 |
| 冷門題 | 題庫沒有、d3 也不知道的題目只能猜（例：プロデューサー巻き） | 低信心時改問雲端大模型（需 API Key） |
| 不靠 PC 運作 | 未做 | PP-OCRv6 small（7.7M，有 ONNX）可搬到手機；決策改用雲端 API |
| 實機測試 | 只在模擬器上測過 | 黑貓維茲的顏色取樣是依 1080x2400 量的，其他比例的手機可能要校正 |
| HTTPS | 伺服器是 HTTP；對外開放靠 `API_TOKEN` | 放在 HTTPS 反向代理（Caddy、Cloudflare Tunnel）後面 |
| 技能施放 | 未做（角色身上冒水泡＝技能可施放） | — |
| 其他題型 | 只支援 4 択；並べ替え、スライド、穴埋め等不支援 | — |
| 看圖題 | 只送文字給模型 | d3 支援圖片輸入 |
| 正式版 App | 只有 debug APK；含開發用的 `OcrProbeReceiver` | 移除測試入口、設定簽章 |
| 畫面方向 | 只支援直向 | — |

---

## 架構

```
手機（答題浮窗 App）                                 PC（docker compose，NVIDIA GPU）
──────────────────────                               ─────────────────────────────────
螢幕擷取（150ms）→ 換題偵測                           ocr   :8767  PP-OCRv6 small
  → 裁切題目範圍、縮到 720 寬 ── JPEG ───────────────→
  ←────────────── 文字＋位置 ─────────────────────────
  → 黑貓維茲選類型畫面？→ 依屬性點面板
  → 解析題目／選項 → 算數題直接算
  → 其他 ─────── 題目＋選項 ─────────────────────────→ d3    :8765
                                                        1. 本機題庫（data/qbank.sqlite，幾毫秒）
                                                        2. d3-mini＋相近題目當參考
                                                        3. d3 沒把握 → 網站題庫（≤1.5 秒）
  ←────────────── 答案、機率、來源 ───────────────────     背景：持續撈網站題庫
  → 浮窗顯示 → 無障礙服務點擊
```

| 路徑 | 內容 |
|---|---|
| `quiz-overlay-app/` | Android App（Kotlin，Android 11 以上） |
| `pc-server/` | d3 決策伺服器、題庫（`qbank.py`）、匯出匯入（`qbank_io.py`）、評測腳本、Dockerfile |
| `ocr-eval/` | PP-OCRv6 OCR 伺服器（`ocr_server.py`）、OCR 比較腳本、Dockerfile |
| `data/` | `qbank.jsonl.gz`：題庫資料（git）；`qbank.sqlite`：執行中的題庫（不進 git） |
| `docker-compose.yml`、`.env.example` | PC 端部署與設定 |
| `android-builder/` | 用 Docker 編譯 APK（主機不需要 JDK／Android SDK） |
| `scripts/` | `.env` 讀取、Android SDK／模擬器安裝與啟動 |
| `mock-quiz/` | 模擬題目頁與模擬器自動測試腳本 |

---

## 使用教學（從零開始）

### 0. 需要準備

| 項目 | 需求 |
|---|---|
| PC | Windows 10/11 64 位元、記憶體 16GB 以上（建議 32GB）、硬碟空間 40GB 以上 |
| 顯示卡 | NVIDIA，顯存 12GB 以上（建議 16GB）；驅動要支援 CUDA 12.8（[下載最新驅動](https://www.nvidia.com/drivers)） |
| 手機 | Android 11 以上；要能連到這台 PC |
| 軟體 | [Git](https://git-scm.com/download/win)（含 Git Bash）、[Docker Desktop](https://www.docker.com/products/docker-desktop/)（使用 WSL2 後端） |

### 1. 安裝 Docker Desktop 並確認能用顯示卡

1. 安裝 Docker Desktop，安裝時勾選「Use WSL 2」；裝完重新開機。
2. 打開 Docker Desktop，等左下角顯示「Engine running」。
3. 在 PowerShell 測試容器裡看得到顯示卡：

   ```powershell
   docker run --rm --gpus all nvidia/cuda:12.8.1-base-ubuntu24.04 nvidia-smi
   ```

   看到顯示卡型號就成功。失敗的話先更新 NVIDIA 驅動、更新 WSL（`wsl --update`）。

### 2. 取得專案

```powershell
git clone https://github.com/happy74527-ai/kuroneko_auto.git
cd kuroneko_auto
```

（私人 repo：第一次 clone 會跳出 GitHub 登入視窗。）

### 3. 設定 `.env`

```powershell
Copy-Item .env.example .env
notepad .env
```

只在自家區網使用可以全部用預設值。要從外面連進來時，**一定要把 `API_TOKEN` 設成一串夠長的隨機密碼**（例如在 PowerShell 執行 `-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 32 | % {[char]$_})` 產生）。各欄位說明見[設定參考](#設定參考)。

### 4. 啟動 PC 端伺服器

```powershell
docker compose up -d --build
```

- 第一次建置約 10–20 分鐘（下載 PyTorch、CUDA 套件）。
- 第一次啟動 d3 會再下載模型約 9GB，並從 `data/qbank.jsonl.gz` 匯入題庫（約 30 秒）。看進度：`docker compose logs -f d3`，出現 `Uvicorn running` 就好了。
- 確認兩個服務都正常（瀏覽器打開也可以）：

  ```powershell
  curl http://localhost:8765/health   # {"model":"vllm-sr/d3-mini","qbank":66xxx}
  curl http://localhost:8767/health   # {"device":"gpu"}
  ```

- 之後開機後只要 `docker compose up -d`；停止用 `docker compose down`。

### 5. 讓手機連得到 PC

**同一個 Wi-Fi（最簡單）**

1. 查 PC 的區網 IP：PowerShell 執行 `ipconfig`，找「IPv4 位址」，例如 `192.168.1.23`。
2. 開放 Windows 防火牆（系統管理員 PowerShell，執行一次）：

   ```powershell
   New-NetFirewallRule -DisplayName "kuroneko_auto" -Direction Inbound -Protocol TCP -LocalPort 8765,8767 -Action Allow
   ```

3. 手機瀏覽器打開 `http://192.168.1.23:8765/health`，看得到文字就代表通了。

**不在同一個網路（外出時）**

- 推薦 [Tailscale](https://tailscale.com/)：PC 和手機都安裝並登入同一個帳號，用 PC 的 Tailscale IP（`100.x.x.x`）取代上面的區網 IP。不需要開路由器的埠。
- 或在路由器做 port forwarding（8765、8767）：`.env` 一定要設 `API_TOKEN`，並建議加 HTTPS 反向代理。

### 6. 編譯 App

```powershell
.\android-builder\build.ps1
```

產出 `quiz-overlay-app\app\build\outputs\apk\debug\app-debug.apk`。編譯在 Docker 裡進行，不需要安裝 Android Studio。

### 7. 把 App 裝到手機

**方法 A：直接傳檔案**

1. 把 `app-debug.apk` 傳到手機（LINE、Google Drive、USB 都可以）。
2. 在手機上點開 → 允許「安裝不明來源的應用程式」→ 安裝。

**方法 B：USB＋adb**

1. 手機：設定 → 關於手機 → 連點「版本號碼」7 次開啟開發人員選項 → 開啟「USB 偵錯」。
2. 用 USB 接上 PC，手機上允許偵錯，然後：

   ```bash
   adb install -r quiz-overlay-app/app/build/outputs/apk/debug/app-debug.apk
   ```

   （沒有 adb 的話先執行 `bash scripts/setup-android-sdk.sh`，再 `source scripts/android-env.sh`。）

### 8. 手機上的權限（第一次）

打開「答題浮窗」App：

1. **步驟 1「授予浮窗權限」** → 在清單裡找到「答題浮窗」→ 開啟「允許顯示在其他應用程式上層」。
2. **步驟 2「開啟無障礙服務」**（要自動點擊才需要）→「已下載的應用程式」→「答題浮窗」→ 開啟。
   - Android 13 以上若顯示「受限制的設定」：到 設定 → 應用程式 → 答題浮窗 → 右上角「⋮」→「允許受限制的設定」，再回來開啟。
3. 第一次會詢問通知權限，請允許（螢幕擷取的前景服務需要顯示通知）。

### 9. App 設定

| 欄位 | 填什麼 |
|---|---|
| 決策後端 | 「System One API（PC 上的 d3）」 |
| System One 端點 URL | `http://<PC IP>:8765/v1/systemone` |
| API Key | `.env` 的 `API_TOKEN`；沒設就留空 |
| 模型名稱 | 留空 |
| 選項數量 | 4 |
| OCR | 「PC 上的 PP-OCRv6」 |
| PP-OCRv6 服務 URL | `http://<PC IP>:8767/v1/ocr` |
| 自動點擊 | 要自動作答就勾選 |
| 信心低於門檻時仍點最高的 | 建議勾選（限時答題猜一個比不答好） |
| 黑貓維茲：自動選屬性類型 | 玩黑貓維茲時勾選 |
| 信心門檻 | 0.6（低於這個值，浮窗會標示「猜的」） |

設定好後按 **「3. 測試決策後端」**，下方顯示「回答：B 東京」就代表手機到 d3 的連線正常。

### 10. 開始自動答題

1. 按設定頁最上方 **「▶ 啟動浮窗」** → 同意螢幕錄製（Android 14 按「Start now」；Android 15 以上請選「整個螢幕」再按「Start」）。
2. App 退到背景、浮窗出現在左上角。切到遊戲、進入關卡。
3. 按浮窗上的 **「▶ 開始」**。之後會自動選類型、答題直到關卡結束。
4. 浮窗顯示的內容：
   - `答案 B：○○【題庫】`：題庫裡有這題，答案確定
   - `答案 B：○○（信心 93%・參考題庫）`：d3 判斷，參考了題庫的相近題目
   - `答案 B：○○（信心 41%，猜的）`：d3 沒把握
   - `A 2%  B 91%  C 5%  D 2%`：每個選項的機率
   - `OCR 120ms｜判斷 80ms｜已點擊`
5. 按「⏸ 暫停」暫停、「✕」關閉。浮窗可以拖曳。

---

## 常見問題

| 狀況 | 原因與解法 |
|---|---|
| 「測試決策後端」失敗 | 手機連不到 PC：確認同一個 Wi-Fi、PC IP 正確、防火牆有開、`docker compose ps` 兩個服務都在跑；有設 `API_TOKEN` 的話 App 的 API Key 要一致 |
| 浮窗顯示「⚠ 無障礙服務未啟用」 | 到無障礙設定重新開啟「答題浮窗」；重新安裝 App 後也要重新開 |
| 浮窗一直「等待題目…」 | 確認螢幕錄製選的是整個螢幕；看浮窗有沒有顯示 OCR 時間，沒有的話按一次暫停再開始 |
| 答得很慢 | OCR 伺服器沒用到 GPU（`/health` 要顯示 `"device":"gpu"`）；或 d3 沒把握在等網站（`.env` 可設 `QBANK_ONLINE=0`） |
| 黑貓維茲沒有自動選類型 | 只在 1080x2400 版面驗證過；用 `adb logcat -s QuizOverlay` 看 `category:` 開頭的訊息 |
| 誤點到選單 | 回報畫面截圖；選類型要求 2x2 排列、確認對話框（「よろしいですか」「挑戦しますか」）不會被當成題目 |
| d3 啟動時顯存不足 | 關掉其他用 GPU 的程式；或 `.env` 改 `D3_MODEL=vllm-sr/d3-lite`（較小、較不準） |
| docker 看不到顯示卡 | 更新 NVIDIA 驅動、`wsl --update`、Docker Desktop 設定裡確認使用 WSL2 |

---

## 設定參考

`.env`（從 `.env.example` 複製；不進 git）：

| 變數 | 預設 | 說明 |
|---|---|---|
| `API_TOKEN` | 空 | d3 和 OCR 共用的存取密碼；對外開放必設，App 的 API Key 填同一個值 |
| `D3_HOST` / `D3_PORT` | `0.0.0.0` / `8765` | d3 對外的位址／埠號（只想本機用可以設 `127.0.0.1`） |
| `D3_MODEL` | `vllm-sr/d3-mini` | 或 `vllm-sr/d3-lite`（顯存不足時） |
| `QBANK_ONLINE` | `1` | 本機題庫沒有時，答題當下也上網站查；`0` 只查本機 |
| `QBANK_CRAWL` | `1` | 背景持續撈網站題庫（每 3 秒一次） |
| `OCR_HOST` / `OCR_PORT` | `0.0.0.0` / `8767` | OCR 對外的位址／埠號 |

d3 伺服器 API：`POST /v1/systemone`（System One 格式；測試用參數 `bank_mode`：`full`／`no_hint`／`d3`、`online`）、`GET /health`、`GET /v1/qbank/stats`、`POST /v1/qbank/crawl/start`、`POST /v1/qbank/crawl/stop`、`POST /v1/qbank/learn`。

### 不用 Docker、原生執行（開發用）

```powershell
.\pc-server\setup.ps1    # 建立 pc-server\.venv（PyTorch CUDA 12.8）
.\ocr-eval\setup.ps1     # 建立 ocr-eval\.venv-gpu（沒有 NVIDIA 顯示卡：-Cpu）
.\pc-server\run.ps1      # 讀取 .env，啟動 d3
.\ocr-eval\run.ps1       # 讀取 .env，啟動 OCR
```

和 docker 版共用 `data/qbank.sqlite` 與模型快取，**兩種方式不要同時開**（埠號會衝突）。

### 模擬器（開發測試用）

```bash
bash scripts/setup-android-sdk.sh   # JDK 17、Android SDK、Android 14 模擬器（6GB RAM），裝在 .tools/
bash scripts/start-emulator.sh      # 視窗會出現在桌面
```

模擬器裡連 PC 用 `10.0.2.2`（例：`http://10.0.2.2:8765/v1/systemone`）。黑貓維茲日文版只能用 Android 14 映像檔（見開發筆記）。

---

## 題庫

- 來源：[黒猫のウィズ問題集](https://kuronekonowiz.main.jp/)（約 10 萬題，網站註明歡迎自由使用）。**資料屬於該網站，請勿公開散布；repo 請維持私人（private）。**
- `data/qbank.jsonl.gz`：第一次啟動時若 `data/qbank.sqlite` 不存在或是空的，會自動匯入。
- 更新附帶的資料：`pc-server\.venv\Scripts\python pc-server\qbank_io.py export`（或 `docker compose exec d3 python qbank_io.py export`），再提交 `data/qbank.jsonl.gz`。
- 背景撈取查過的關鍵字有記錄，可中斷續撈；網站每次搜尋最多回 100 題，滿 100 題時會再按類型分開查。
- 比對規則（寧可查不到也不能配錯，題庫答案會直接作答）：題目相似度 ≥92%，或 ≥75% 且四個選項相同；數字、序數、否定、比較、方位等關鍵字必須一致（「一番」≠「二番目」）。比對前會校正 OCR 常見誤讀（大小假名、「ー」與「一」）。
- 沒有完全相同的題目時，相近題目＋正解會附在題目後面給 d3 參考（`source=d3+hint`），不直接作答。

## 測試

```bash
python -I mock-quiz/serve.py &                        # 模擬題目頁 :8766
bash mock-quiz/run_test.sh <截圖資料夾>                # 模擬器上跑 11 題（中／日／英／算數／換行問號／題庫）
bash mock-quiz/start_overlay.sh jp.colopl.quizwiz      # 重新啟動浮窗並切到黑貓維茲日文版
python pc-server/eval_jp.py                            # 日文實題：d3 / 題庫 / 題庫＋參考 三種模式比較（需 d3 伺服器）
python pc-server/eval_quiz.py                          # 30 題中日英常識題
```

`ocr-eval/eval_ppocr.py` 需要遊戲截圖（`ocr-eval/images/`，有版權疑慮未提交）。

---

## 開發筆記（踩過的坑）

- **OCR 錯字**：讓系統直接輸出低解析度畫面會有鋸齒（囓→喝）；要原始解析度擷取、自己平滑縮放。ML Kit 對日文和藝術字很弱，PP-OCRv6 好很多。
- **換題偵測**：計時器、計時條、燭火一直在動，要只看題目範圍，並有逾時強制處理。
- **無障礙服務**：`am force-stop` 或重新安裝 APK 會讓系統關掉它；測試腳本會重新啟用並確認綁定。
- **Android 15 edge-to-edge**：設定頁最上方會被標題列蓋住，已改成自己處理 insets。
- **螢幕錄製授權**：Android 14 只有「Start now」；Android 15 預設「A single app」，要改選整個螢幕。
- **黑貓維茲日文版**：Android 15 模擬器的 ARM 轉譯會讓它閃退，Android 16 映像檔系統不穩，要用 Android 14；模擬器至少給 6GB RAM，不然會越跑越卡。
- **選類型**：日文版「アニメゲーム」是藝術字，OCR 讀不出來，所以面板靠位置＋圓形圖示顏色辨認；兩版上方黑邊高度不同，顏色條和怪物位置從面板位置往上推算；火屬性圖示的橘色火焰色相約 20–30 度也算紅。
- **網站題庫**：回應是 chunked 編碼，要自己解碼（否則 3% 的資料會混進亂碼）；結果 0.8 秒就送完，但網站會拖 3 秒才關連線，讀到「N問見つかりました」的 N 題就提早斷線。
- **埠號**：8000 常被 VS Code 佔用，所以 d3 用 8765。
