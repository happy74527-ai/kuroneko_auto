#!/usr/bin/env bash
# 重新啟動答題浮窗（整個螢幕擷取）並按下「開始」，然後切到指定 App（預設知識王）。

source "$(dirname "${BASH_SOURCE[0]}")/../scripts/android-env.sh"
P=com.hank.quizoverlay
TARGET="${1:-com.braveknight.quiz01}"
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
adb shell monkey -p "$TARGET" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
adb shell input tap 150 146  # 浮窗預設位置的「▶ 開始」
