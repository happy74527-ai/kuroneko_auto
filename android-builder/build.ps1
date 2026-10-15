# 在 Docker 容器裡編譯 APK，不會動到主機的 Java / Android 環境。
# Gradle 快取放在名為 quiz-gradle-cache 的 Docker volume，重複編譯會比較快。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
docker build -t quiz-android-builder "$PSScriptRoot"
docker run --rm `
  -v "${root}\quiz-overlay-app:/project" `
  -v quiz-gradle-cache:/root/.gradle `
  -v quiz-android-home:/root/.android `
  quiz-android-builder gradle assembleDebug --no-daemon
Write-Host "APK: $root\quiz-overlay-app\app\build\outputs\apk\debug\app-debug.apk"
