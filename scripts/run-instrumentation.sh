#!/usr/bin/env bash
# 在已启动的模拟器上直接跑仪器测试，跳过 Gradle 的重新打包与安装。
#
# 背景（实测数据，21 个用例）：
#   ./gradlew :app:connectedDebugAndroidTest          56.6s  冷构建
#   ./gradlew :app:connectedDebugAndroidTest          35.4s  构建命中缓存
#   本脚本（首次会构建并安装，之后只跑）             26.6s  只跑用例
# 用例本身的执行耗时约 26s，Gradle 的构建与安装占掉其余 30s。
# 反复调试单个测试类时用本脚本最划算；正式验收仍用 Gradle 任务。
#
# 用法：
#   scripts/run-instrumentation.sh                      跑全部
#   scripts/run-instrumentation.sh io.github...CaptureShutterGestureTest   跑单类
#   scripts/run-instrumentation.sh 'io.github...#someTest'                 跑单方法
set -euo pipefail

cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
ADB="$ANDROID_HOME/platform-tools/adb"

# Gradle.properties 里固定了 Temurin 17；这里只作兜底，避免 java 缺失导致 adb 不可用
[ -x "$JAVA_HOME/bin/java" ] || export PATH="$JAVA_HOME/bin:$PATH"

# 多设备时必须钉死模拟器：本机常同时连着真机，connectedAndroidTest 会一并跑
DEVICE="${ANDROID_SERIAL:-emulator-5554}"
if ! "$ADB" devices | grep -q "^${DEVICE}[[:space:]]"; then
  echo "设备 $DEVICE 未连接。先启动模拟器，或用 ANDROID_SERIAL 指定。" >&2
  "$ADB" devices
  exit 1
fi

# 装好两个 APK（Gradle 只在必要时才重装，这里先确保存在且是最新构建）
./gradlew --quiet :app:assembleDebug :app:assembleDebugAndroidTest
"$ADB" -s "$DEVICE" install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" -s "$DEVICE" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

RUNNER=io.github.hecate2.D7.test/androidx.test.runner.AndroidJUnitRunner
if [ $# -gt 0 ]; then
  "$ADB" -s "$DEVICE" shell am instrument -w -e class "$1" "$RUNNER"
else
  "$ADB" -s "$DEVICE" shell am instrument -w "$RUNNER"
fi
