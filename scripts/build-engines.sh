#!/usr/bin/env bash
# Builds the separate engine programs (Mihomo, Psiphon, the DNS tunnel...) into
# app/src/main/jniLibs/<abi>/lib<name>.so, where Android installs them as executables.
# Usage: scripts/build-engines.sh [abi ...]   (default: every ABI in scripts/engine-abis.txt)
# Every ABI needs the Android NDK (ANDROID_NDK_HOME): Go links with its C toolchain so DNS uses Android's resolver.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -gt 0 ]]; then
  ABIS=("$@")
else
  # Same list the Gradle -PtargetAbi check reads, so the two sets cannot drift apart.
  mapfile -t ABIS < <(sed -e 's/#.*//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' "$REPO_ROOT/scripts/engine-abis.txt" | sed '/^$/d')
  [[ ${#ABIS[@]} -gt 0 ]] || { echo "scripts/engine-abis.txt lists no Android ABI" >&2; exit 2; }
fi
for abi in "${ABIS[@]}"; do
  for script in "$REPO_ROOT"/scripts/engines/*.sh; do
    [[ "$(basename "$script")" == common.sh ]] && continue
    ABI="$abi" OUT="$REPO_ROOT/app/src/main/jniLibs/$abi" bash "$script"
  done
  ABI="$abi" OUT="$REPO_ROOT/app/src/main/jniLibs/$abi" \
    RADIANCE_SOURCE_OFFER="$REPO_ROOT/build/transport-offer" bash "$REPO_ROOT/scripts/radiance/build-android.sh"
done
