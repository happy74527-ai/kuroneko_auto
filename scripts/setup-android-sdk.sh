#!/usr/bin/env bash
# 安裝專案內的 JDK 17、Android SDK、模擬器，並建立測試用的模擬器 quizphone14（Git Bash 執行）。
#   bash scripts/setup-android-sdk.sh
# 全部裝在 .tools/（約 10GB），不動系統的 Java / Android 設定。
#
# 為什麼是 Android 14：
#   黑貓維茲日文版（Unity，只有 ARM64）在 Android 15 映像檔的 ARM 轉譯層會一啟動就閃退；
#   Android 16 映像檔在這台電腦上 system_server 會不斷重啟。Android 14 兩版遊戲都能跑。
# 需要 Windows 的 Hyper-V / Windows Hypervisor Platform（WHPX）。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
T="$ROOT/.tools"
mkdir -p "$T"
cd "$T"

if [ ! -d jdk17 ]; then
  curl -sSL -o jdk17.zip "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
  unzip -q jdk17.zip && rm jdk17.zip && mv jdk-17* jdk17
fi
if [ ! -d android-sdk/cmdline-tools/latest ]; then
  curl -sSL -o ct.zip https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip
  mkdir -p android-sdk/cmdline-tools
  unzip -q ct.zip -d android-sdk/cmdline-tools && rm ct.zip
  mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest
fi

source "$ROOT/scripts/android-env.sh"
mkdir -p "$ANDROID_AVD_HOME"
SDKM="$(cygpath -w "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat")"
AVDM="$(cygpath -w "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager.bat")"
IMAGE="system-images;android-34;google_apis_playstore;x86_64"

yes | cmd //c "$SDKM" --licenses > /dev/null 2>&1 || true
cmd //c "$SDKM" "platform-tools" "emulator" "$IMAGE"

if [ ! -d "$ANDROID_AVD_HOME/quizphone14.avd" ]; then
  echo no | cmd //c "$AVDM" create avd -n quizphone14 -k "$IMAGE" -d pixel_7 > /dev/null
fi
# 日文版遊戲加上 ARM 轉譯很吃資源：4GB 會用到交換空間、越跑越卡，所以給 6GB、6 核心
CFG="$ANDROID_AVD_HOME/quizphone14.avd/config.ini"
set_cfg() {
  if grep -qE "^$1 *=" "$CFG"; then sed -i -E "s/^$1 *=.*/$1 = $2/" "$CFG"; else echo "$1 = $2" >> "$CFG"; fi
}
set_cfg hw.ramSize 6144
set_cfg vm.heapSize 512M
set_cfg hw.cpu.ncore 6
set_cfg hw.gpu.enabled yes
set_cfg hw.gpu.mode host
set_cfg hw.keyboard yes

"$ANDROID_HOME/emulator/emulator.exe" -accel-check | tail -1
echo "完成。啟動模擬器：bash scripts/start-emulator.sh"
