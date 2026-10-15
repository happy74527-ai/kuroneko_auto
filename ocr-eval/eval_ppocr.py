"""比較 PP-OCRv6（small / medium）在知識王與 mock 題目畫面上的辨識結果與速度。

用法：.venv\\Scripts\\python eval_ppocr.py [small|medium|tiny ...]
"""
import glob
import os
import sys
import time

from paddleocr import PaddleOCR

HERE = os.path.dirname(os.path.abspath(__file__))

# 知識王截圖的正確文字（題目＋四個選項）
TRUTH = {
    "kq_f_016": "「垂死病中驚坐起」的下一句是? 留取丹心照汗青 笑問客從何處來 商女不知亡國恨 暗風吹雨入寒窗",
    "kq_f_027": "逢甲夜市商圈位於下列哪一個縣市? 台北市 高雄市 台中市 台南市",
    "kq_f_036": "下列何者不是紅樓夢中的人物? 王熙鳳 林黛玉 潘金蓮 薛寶釵",
    "kq_f_045": "「艋舺」這地名是指臺灣現在的哪一個地方? 高雄 萬華 基隆 淡水",
}


def edit_distance(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def run(tier: str):
    ocr = PaddleOCR(
        text_detection_model_name=f"PP-OCRv6_{tier}_det",
        text_recognition_model_name=f"PP-OCRv6_{tier}_rec",
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        engine="onnxruntime",
    )
    images = sorted(glob.glob(os.path.join(HERE, "images", "*")))
    ocr.predict(images[0])  # 暖機
    total_err = total_len = 0
    for path in images:
        name = os.path.splitext(os.path.basename(path))[0]
        t = time.perf_counter()
        res = ocr.predict(path)[0]
        ms = (time.perf_counter() - t) * 1000
        texts = [s for s, score in zip(res["rec_texts"], res["rec_scores"]) if score > 0.5]
        # 只保留題目區（去掉玩家名稱、分數這些很短的雜字）
        joined = " ".join(texts)
        line = f"[{tier}] {name:<12} {ms:6.0f}ms  {joined}"
        if name in TRUTH:
            truth = TRUTH[name].replace(" ", "")
            got = "".join(texts)
            # 只看真值字元有沒有被讀出來：以真值長度的子字串比對最小編輯距離
            err = min(
                edit_distance(truth, got[i:i + len(truth)])
                for i in range(max(1, len(got) - len(truth) + 1))
            )
            total_err += err
            total_len += len(truth)
            line += f"   ← 錯 {err} 字"
        print(line)
    if total_len:
        print(f"[{tier}] 知識王 4 題：字元錯誤率 {total_err}/{total_len} = {total_err / total_len:.1%}")


if __name__ == "__main__":
    for tier in sys.argv[1:] or ["small"]:
        run(tier)
