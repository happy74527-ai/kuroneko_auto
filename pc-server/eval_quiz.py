"""用一組中／日／英常識選擇題測 d3 伺服器的準確率與延遲。

用法：.venv\\Scripts\\python eval_quiz.py [http://127.0.0.1:8765/v1/systemone]
送出的格式與手機 App（SystemOneDecider）完全相同。
"""
import json
import sys
import time
import urllib.request

URL = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8765/v1/systemone"
INSTRUCTIONS = (
    "The state is quiz text read from a phone screen (Chinese, Japanese or English). "
    "Which option is the correct answer to the quiz question?"
)

# (語言, 題目, 選項, 正解索引)
QUIZ = [
    ("zh", "台灣最高的山是哪一座？", ["雪山", "玉山", "合歡山", "阿里山"], 1),
    ("zh", "《紅樓夢》的作者是誰？", ["施耐庵", "羅貫中", "曹雪芹", "吳承恩"], 2),
    ("zh", "人體中最大的器官是什麼？", ["肝臟", "皮膚", "大腸", "肺"], 1),
    ("zh", "光在真空中的速度約為每秒多少公里？", ["3 萬", "30 萬", "300 萬", "3000"], 1),
    ("zh", "哪一個國家的首都是坎培拉？", ["紐西蘭", "加拿大", "澳洲", "南非"], 2),
    ("zh", "元素週期表中，符號 Fe 代表哪一種元素？", ["氟", "鐵", "鉛", "鈣"], 1),
    ("zh", "第一屆現代奧運在哪一個城市舉辦？", ["巴黎", "倫敦", "雅典", "羅馬"], 2),
    ("zh", "「三顧茅廬」是誰去拜訪諸葛亮？", ["曹操", "孫權", "劉備", "關羽"], 2),
    ("zh", "太陽系中體積最大的行星是？", ["土星", "木星", "海王星", "地球"], 1),
    ("zh", "台北 101 原本的名稱是什麼？", ["台北金融大樓", "台北國際金融中心", "台北世貿大樓", "台北商業大樓"], 1),
    ("ja", "日本で一番長い川はどれですか？", ["利根川", "信濃川", "石狩川", "北上川"], 1),
    ("ja", "「源氏物語」の作者は誰ですか？", ["清少納言", "紫式部", "和泉式部", "小野小町"], 1),
    ("ja", "富士山の標高は約何メートルですか？", ["2776", "3776", "4776", "3176"], 1),
    ("ja", "水の化学式はどれですか？", ["CO2", "H2O", "O2", "NaCl"], 1),
    ("ja", "江戸幕府を開いたのは誰ですか？", ["織田信長", "豊臣秀吉", "徳川家康", "足利尊氏"], 2),
    ("ja", "日本の国鳥はどれですか？", ["タンチョウ", "キジ", "トキ", "ハト"], 1),
    ("ja", "サッカーの1チームのフィールド上の選手は何人ですか？", ["9人", "10人", "11人", "12人"], 2),
    ("ja", "「モナ・リザ」を描いたのは誰ですか？", ["ミケランジェロ", "ラファエロ", "レオナルド・ダ・ヴィンチ", "ボッティチェリ"], 2),
    ("ja", "日本で一番面積が大きい都道府県は？", ["岩手県", "北海道", "長野県", "福島県"], 1),
    ("ja", "1年で一番日が長いのはいつですか？", ["春分", "夏至", "秋分", "冬至"], 1),
    ("en", "What is the largest ocean on Earth?", ["Atlantic", "Indian", "Arctic", "Pacific"], 3),
    ("en", "Who wrote 'Romeo and Juliet'?", ["Charles Dickens", "William Shakespeare", "Jane Austen", "Mark Twain"], 1),
    ("en", "What is the chemical symbol for gold?", ["Ag", "Gd", "Au", "Go"], 2),
    ("en", "How many sides does a hexagon have?", ["5", "6", "7", "8"], 1),
    ("en", "Which planet is known as the Red Planet?", ["Venus", "Jupiter", "Mars", "Mercury"], 2),
    ("en", "In which year did World War II end?", ["1943", "1944", "1945", "1946"], 2),
    ("en", "What is the capital of Canada?", ["Toronto", "Vancouver", "Montreal", "Ottawa"], 3),
    ("en", "Which gas do plants absorb from the atmosphere?", ["Oxygen", "Nitrogen", "Carbon dioxide", "Hydrogen"], 2),
    ("en", "Who painted 'The Starry Night'?", ["Claude Monet", "Vincent van Gogh", "Pablo Picasso", "Salvador Dalí"], 1),
    ("en", "What is the hardest natural substance?", ["Gold", "Iron", "Diamond", "Quartz"], 2),
]


def ask(question, options):
    body = {
        "state": question,
        "questions": {
            "answer": {
                "type": "choice",
                "instructions": INSTRUCTIONS,
                "criteria": {chr(65 + i): o for i, o in enumerate(options)},
            }
        },
    }
    req = urllib.request.Request(URL, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def main():
    ask(*QUIZ[0][1:3])  # 預熱
    stats = {}
    for lang, q, opts, correct in QUIZ:
        t = time.perf_counter()
        res = ask(q, opts)
        ms = (time.perf_counter() - t) * 1000
        ans = res["answers"]["answer"]
        idx = ord(str(ans.get("choice", "?"))[0].upper()) - 65
        ok = idx == correct
        s = stats.setdefault(lang, [0, 0, []])
        s[0] += ok
        s[1] += 1
        s[2].append(ms)
        mark = "✓" if ok else "✗"
        print(f"{mark} [{lang}] {q[:28]:<28} → {opts[idx] if 0 <= idx < len(opts) else ans}  "
              f"conf={ans.get('confidence', float('nan')):.2f}  {ms:.0f}ms")
    print()
    for lang, (ok, n, ms) in stats.items():
        print(f"{lang}: {ok}/{n} 正確，平均延遲 {sum(ms) / len(ms):.0f}ms")
    print("第一題完整回應：", json.dumps(ask(*QUIZ[0][1:3]), ensure_ascii=False)[:400])


if __name__ == "__main__":
    main()
