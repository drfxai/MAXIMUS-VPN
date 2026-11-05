#!/usr/bin/env bash
# Separate, modified AGPL-3.0 executable, source staged in the release source offer.
set -euo pipefail
: "${ABI:?}" "${OUT:?}"
TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REV=9ebdcc945af12ea532b32119884f846bdb9c4bfc
patch_hash=$(sha256sum "$TASK_ROOT/scripts/connections/harden-aether.py" | cut -c1-16)
source_dir="$TASK_ROOT/build/aether-source-$patch_hash"
if [[ ! -f "$source_dir/MAXIMUS_POLICY" ]]; then
  mkdir -p "$source_dir"
  git -C "$source_dir" init -q
  git -C "$source_dir" fetch --depth=1 https://github.com/CluvexStudio/Aether.git "$REV"
  git -C "$source_dir" checkout --detach FETCH_HEAD
  test "$(git -C "$source_dir" rev-parse HEAD)" = "$REV"
  python3 "$TASK_ROOT/scripts/connections/harden-aether.py" "$source_dir"
fi
case "$ABI" in
 arm64-v8a) target=aarch64-linux-android ;;
 armeabi-v7a) target=armv7-linux-androideabi ;;
 x86_64) target=x86_64-linux-android ;;
 *) exit 2 ;;
esac
export RUSTUP_TOOLCHAIN=1.99.0
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
rustup toolchain install "$RUSTUP_TOOLCHAIN" --profile minimal
rustup target add "$target"
# boring-sys 5.3 names ErrLib from rustfmt-formatted bindgen output.
rustup component add rustfmt --toolchain "$RUSTUP_TOOLCHAIN"
if ! cargo ndk --version | grep -q '3.5.4'; then cargo install cargo-ndk --version 3.5.4 --locked; fi
ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME}/ndk/26.3.11579264}"
export ANDROID_NDK_HOME="$ndk" ANDROID_NDK_ROOT="$ndk"
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
sysroot="$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
case "$ABI" in
 arm64-v8a) prefix=aarch64-linux-android; include_arch=aarch64-linux-android ;;
 armeabi-v7a) prefix=armv7a-linux-androideabi; include_arch=arm-linux-androideabi ;;
 x86_64) prefix=x86_64-linux-android; include_arch=x86_64-linux-android ;;
esac
export CLANG_PATH="$bin/clang"
libclang=$(find /usr/lib/llvm-* -name libclang.so -print -quit)
export LIBCLANG_PATH="$(dirname "$libclang")"
under="${target//-/_}"
export "BINDGEN_EXTRA_CLANG_ARGS_${under}=--target=${prefix}24 --sysroot=$sysroot -I$sysroot/usr/include -I$sysroot/usr/include/$include_arch"
export "BORING_BSSL_RUST_CPPLIB_${under}=static:-bundle=c++"
if ! (cd "$source_dir/aether" && cargo ndk -t "$ABI" --platform 24 build --locked --release --features tor --bin aether); then
  find "$source_dir/aether/target/$target/release/build" -path '*/out/bindings.rs' -exec head -c 1800 {} \;
  exit 1
fi
mkdir -p "$OUT" "$TASK_ROOT/build/transport-offer"
install -m 0755 "$source_dir/aether/target/$target/release/aether" "$OUT/libaether.so"
cp "$TASK_ROOT/scripts/connections/harden-aether.py" "$source_dir/MAXIMUS_PATCH.py"
printf '%s\n' "$REV" > "$source_dir/UPSTREAM_REVISION"
if [[ ! -d "$source_dir/aether/vendor" ]]; then
 (cd "$source_dir/aether" && mkdir -p .cargo && cargo vendor --locked vendor > .cargo/config.toml.vendor && mv .cargo/config.toml.vendor .cargo/config.toml)
fi
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner --exclude=target --exclude=.git -C "$source_dir" \
 -czf "$TASK_ROOT/build/transport-offer/Aether-source.tar.gz" aether quiche LICENSE TRADEMARK.md MAXIMUS_POLICY MAXIMUS_PATCH.py UPSTREAM_REVISION
