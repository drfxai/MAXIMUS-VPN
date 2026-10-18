#!/usr/bin/env bash
# Separate AGPL-3.0 transport executable. Source offer is staged beside release APKs.
set -euo pipefail
: "${ABI:?set ABI}"
: "${OUT:?set OUT}"
TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REV=b784ebd149336f4463906509738d354dd9850e23
case "$ABI" in
  arm64-v8a) rust_target=aarch64-linux-android ;;
  armeabi-v7a) rust_target=armv7-linux-androideabi ;;
  x86_64) rust_target=x86_64-linux-android ;;
  *) echo "Unsupported ABI: $ABI" >&2; exit 2 ;;
esac
# Source, Cargo.lock and patch are immutable inputs; never download a prebuilt executable.
source_dir="$TASK_ROOT/build/transport-source"
if [[ ! -f "$source_dir/MAXIMUS_TRANSPORT_POLICY" ]]; then
  mkdir -p "$source_dir"
  git -C "$source_dir" init -q
  git -C "$source_dir" fetch --depth=1 https://github.com/Floxu1/UAC-SNI-Spoofer-Android.git "$REV"
  git -C "$source_dir" checkout --detach FETCH_HEAD
  test "$(git -C "$source_dir" rev-parse HEAD)" = "$REV"
  python3 "$TASK_ROOT/scripts/transport/harden-source.py" "$source_dir"
fi
export RUSTUP_TOOLCHAIN=1.88.0
rustup toolchain install "$RUSTUP_TOOLCHAIN" --profile minimal
rustup target add --toolchain "$RUSTUP_TOOLCHAIN" "$rust_target"
if ! cargo ndk --version | grep -q '3.5.4'; then cargo install cargo-ndk --version 3.5.4 --locked; fi
# The pinned upstream script includes its BoringSSL bootstrap workaround, now building the CLI.
bash "$source_dir/core/build-android.sh" --abi "$ABI" --api 24
mkdir -p "$OUT" "$TASK_ROOT/build/transport-offer"
cp "$source_dir/core/aether/target-android/$rust_target/release/aether" "$OUT/libmatrixtransport.so"
chmod 755 "$OUT/libmatrixtransport.so"
ndk_path="$ANDROID_HOME/ndk/26.3.11579264"
case "$ABI" in
  arm64-v8a) cxx_triple=aarch64-linux-android ;;
  armeabi-v7a) cxx_triple=arm-linux-androideabi ;;
  x86_64) cxx_triple=x86_64-linux-android ;;
esac
if readelf -d "$OUT/libmatrixtransport.so" | grep -q 'libc++_shared.so'; then
  cp "$ndk_path/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$cxx_triple/libc++_shared.so" "$OUT/"
fi
# The source archive includes locked Rust dependency sources, not just download instructions.
if [[ ! -d "$source_dir/core/aether/vendor" ]]; then
  (cd "$source_dir/core/aether" && cargo vendor --locked vendor > .cargo/config.toml.vendor)
  mv "$source_dir/core/aether/.cargo/config.toml.vendor" "$source_dir/core/aether/.cargo/config.toml"
fi
cp "$TASK_ROOT/scripts/transport/harden-source.py" "$source_dir/MAXIMUS_PATCH.py"
printf '%s\n' "$REV" > "$source_dir/UPSTREAM_REVISION"
# Include the complete corresponding source and patch; omit downloaded compilers/build products.
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner \
  --exclude=target --exclude=target-android --exclude=.git \
  -C "$source_dir" -czf "$TASK_ROOT/build/transport-offer/MatrixTransport-source.tar.gz" \
  core/aether core/quiche core/build-android.sh core/LICENSE core/TRADEMARK.md \
  MAXIMUS_PATCH.py MAXIMUS_TRANSPORT_POLICY UPSTREAM_REVISION
