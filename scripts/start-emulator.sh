#!/usr/bin/env bash
# 啟動模擬器 quizphone14（視窗會出現在桌面），等它開機完成。
#   bash scripts/start-emulator.sh
# -dns-server：有些網路環境下模擬器的 DNS 會失敗（Play 商店登入「無法與 Google 伺服器通訊」）
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/android-env.sh"
emulator -avd quizphone14 -no-snapshot-save -no-boot-anim -dns-server 8.8.8.8,1.1.1.1 > "$ROOT/.tools/emulator.log" 2>&1 &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
echo "模擬器已開機"
