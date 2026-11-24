#!/usr/bin/env bash
# Runs the instrumented suite on the emulator started by android-emulator-runner and
# keeps the device logs whatever the outcome. CI calls this; it needs an attached device.
set -uo pipefail

LOG_DIR="build/device-logs"
mkdir -p "$LOG_DIR"

adb wait-for-device
adb logcat -c

set +e
bash gradlew --no-daemon :app:connectedDebugAndroidTest
status=$?
set -e

adb logcat -d -v threadtime > "$LOG_DIR/logcat.txt" 2>&1 || true
adb shell pm list packages 2>/dev/null | grep -i maximus > "$LOG_DIR/packages.txt" || true
adb shell dumpsys activity 2>/dev/null > "$LOG_DIR/dumpsys-activity.txt" || true

if [ "$status" -ne 0 ]; then
  echo "Instrumented tests failed (exit $status); device logs are in $LOG_DIR" >&2
fi
exit "$status"
