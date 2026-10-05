#!/usr/bin/env bash
# Builds the separate engine programs (Mihomo, Psiphon, the DNS tunnel...) into
# app/src/main/jniLibs/<abi>/lib<name>.so, where Android installs them as executables.
# Usage: scripts/build-engines.sh [abi ...]   (default: arm64-v8a armeabi-v7a x86_64)
# Every ABI but arm64-v8a needs the Android NDK (ANDROID_NDK_HOME), whose C linker Go uses there.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ABIS=("$@")
[[ ${#ABIS[@]} -gt 0 ]] || ABIS=(arm64-v8a armeabi-v7a x86_64)
for abi in "${ABIS[@]}"; do
  for script in "$REPO_ROOT"/scripts/engines/*.sh; do
    [[ "$(basename "$script")" == common.sh ]] && continue
    ABI="$abi" OUT="$REPO_ROOT/app/src/main/jniLibs/$abi" bash "$script"
  done
done
