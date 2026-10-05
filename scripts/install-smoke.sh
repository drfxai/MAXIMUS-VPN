#!/usr/bin/env bash
set -euo pipefail
apk=${1:?APK path required}
package=${APP_PACKAGE:-com.drfxai.maximusvpn.debug}
adb install -r "$apk"
if [[ -n "${2:-}" ]]; then
  adb install -r "$2"
  adb shell am instrument -w -r "$package.test/androidx.test.runner.AndroidJUnitRunner"
fi
adb logcat -c
adb shell am force-stop "$package"
adb shell am start -W -n "$package/com.example.MainActivity" | tee /tmp/maximus-launch.txt
if grep -Eq 'Error:|Exception|Status: timeout' /tmp/maximus-launch.txt; then exit 1; fi
sleep 8
adb shell pidof "$package"
adb logcat -d -b crash > /tmp/maximus-crash.txt
if grep -Fq "$package" /tmp/maximus-crash.txt; then cat /tmp/maximus-crash.txt; exit 1; fi

# The engine programs must be installed executable and run on Android's own linker.
if [[ -z "${2:-}" ]] && adb root >/dev/null 2>&1; then
  adb wait-for-device
  lib_dir=$(adb shell pm dump "$package" | tr -d '\r' | sed -n 's/^ *legacyNativeLibraryDir=//p' | head -n 1)
  abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
  engine="$lib_dir/${abi%%-*}"
  [[ "$abi" == x86_64 ]] && engine="$lib_dir/x86_64"
  [[ "$abi" == arm64-v8a ]] && engine="$lib_dir/arm64"
  adb shell ls -l "$engine/" | tee /tmp/maximus-engines.txt
  adb shell "$engine/libmihomo.so" -v | tee -a /tmp/maximus-engines.txt
  grep -q "Mihomo" /tmp/maximus-engines.txt
fi
