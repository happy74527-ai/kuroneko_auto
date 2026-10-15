# source 這個檔案即可使用專案內的 JDK 17 / Android SDK / 模擬器（裝在 .tools/，不影響全域環境）。
#   source scripts/android-env.sh
# 由 scripts/setup-android-sdk.sh 安裝。路徑依這個檔案的位置推算，專案放在哪個資料夾都可以。
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
T="$ROOT/.tools"
export JAVA_HOME="$T/jdk17" ANDROID_HOME="$T/android-sdk" ANDROID_SDK_ROOT="$T/android-sdk"
export ANDROID_USER_HOME="$T/android-user" ANDROID_AVD_HOME="$T/android-user/avd"
# Git Bash 的 PATH 不能用「D:/...」這種寫法（冒號會被當成分隔），要轉成 /d/...
export PATH="$(cygpath -u "$ANDROID_HOME/platform-tools"):$(cygpath -u "$ANDROID_HOME/emulator"):$PATH"
# 不要讓 Git Bash 把 adb 參數裡的 /sdcard、/data 這類路徑改寫成 Windows 路徑
export MSYS_NO_PATHCONV=1
