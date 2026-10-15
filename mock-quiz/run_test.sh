#!/usr/bin/env bash
# 在模擬器上跑一次完整測試：啟動浮窗（整個螢幕）→ 開 mock 題目頁 → 按開始 → 等 8 題答完。
# 需要先執行 python -I mock-quiz/serve.py 與 pc-server/run.ps1。

source "$(dirname "${BASH_SOURCE[0]}")/../scripts/android-env.sh"
P=com.hank.quizoverlay
RESULT="$ROOT/mock-quiz/result.json"
SHOTS="${1:-.}"

tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  local b
  b=$(adb shell cat /sdcard/ui.xml | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | grep -o '\[[0-9,]*\]\[[0-9,]*\]' | head -1)
  [ -z "$b" ] && { echo "找不到按鈕：$1"; return 1; }
  read -r x1 y1 x2 y2 <<<"$(echo "$b" | sed 's/\]\[/ /' | tr -d '[]' | tr ',' ' ')"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

adb shell am force-stop $P
# force-stop 會讓系統關掉這個 App 的無障礙服務，要重新啟用才能自動點擊
# 剛裝完 APK 時系統可能晚一步把無障礙設定清掉，所以寫入後確認真的綁定了，沒有就重試
for _ in 1 2 3 4 5; do
  adb shell settings put secure enabled_accessibility_services $P/.TapService
  adb shell settings put secure accessibility_enabled 1
  sleep 1
  adb shell dumpsys accessibility | grep -q "Bound services:{Service" && break
  adb shell settings put secure enabled_accessibility_services null
done
adb logcat -c
adb shell am start -n $P/.MainActivity >/dev/null; sleep 2
for _ in 1 2 3; do adb shell input swipe 540 500 540 2000 150; done; sleep 1  # 捲回最上面
tap_text "▶ 啟動浮窗"; sleep 2
tap_text "A single app" && { sleep 1.5; tap_text "Entire screen"; }; sleep 1.5
tap_text "Start now" || tap_text "Start"; sleep 3  # Android 14 是 Start now，15 是 Start

rm -f "$RESULT"
adb shell am start -a android.intent.action.VIEW -d "http://10.0.2.2:8766/?r=$RANDOM" com.android.chrome >/dev/null
sleep 4
START=$(date +%s)
tap_text "▶ 開始" || adb shell input tap 150 146  # 浮窗預設位置的開始鈕
for _ in $(seq 1 60); do [ -f "$RESULT" ] && break; sleep 1; done
echo "總耗時：$(($(date +%s) - START)) 秒"
adb exec-out screencap -p >"$SHOTS/test_end.png"

echo "== 作答結果（題目頁記錄）"
cat "$RESULT" 2>/dev/null || echo "（沒有答完）"
echo
echo "== App log"
adb logcat -d -s QuizOverlay:I | grep -E "Q=|tap |ocr |retry" | sed 's/.*QuizOverlay: //'
