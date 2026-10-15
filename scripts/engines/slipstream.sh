#!/usr/bin/env bash
# Builds slipstream-client (a DNS tunnel that carries QUIC, with several resolvers at once) for Android
# as $OUT/libslipstream.so.
#
#   ABI=arm64-v8a OUT=app/src/main/jniLibs/arm64-v8a scripts/engines/slipstream.sh
#   HOST=1 OUT=build/slipstream-host scripts/engines/slipstream.sh   # client and server for this machine (tests)
#
# Upstream is C: github.com/EndPositive/slipstream (Apache-2.0) on its picoquic fork (MIT), picotls
# (MIT) and OpenSSL (Apache-2.0). Every source is fetched with git at a pinned commit and the commit is
# checked after checkout; the submodule commits are pinned by slipstream's own tree and checked too.
#
# slipstream-client.patch changes the client in four ways: it listens on 127.0.0.1 only (upstream
# listens on every address, which would let other devices on the Wi-Fi or hotspot use the tunnel);
# --cert pins the server's certificate, so a resolver on the path cannot read or change the tunnel
# (upstream never checks it); it never writes TLS keys to a file named by SSLKEYLOGFILE; and it exits
# with status 2 when the tunnel never came up, so the app can tell a failed start from a clean stop.
#
# Every ABI needs the Android NDK (ANDROID_NDK_HOME or ANDROID_NDK_LATEST_HOME); this is C, not Go.
set -euo pipefail

: "${OUT:?set OUT to the output directory}"
HOST="${HOST:-0}"
HERE="$(cd "$(dirname "$0")" && pwd)"

SLIPSTREAM_REPO=https://github.com/EndPositive/slipstream
SLIPSTREAM_COMMIT=397850b16e70a52513269134b2c7b9aa13a56745   # main, 2026-04-11 ("fix: Skip trailing dot")
# Submodule commits recorded in that tree (git submodule status); checked after checkout.
SUBMODULES="2ee2ecd189354791e15cc1ff9db86e4e4b8eba10 subprojects/picoquic
d291537e1f66b4a7ede069b949a8db0f94c59246 extern/SPCDNS
bd9246f681806b26469e0d874c893dcab4975aeb extern/lua-resty-base-encoding
153b0accc1f0201df9d2fd169bafaeee53e6f300 extern/quick_arg_parser"
# picoquic's CMake fetches picotls by this commit itself (FetchContent GIT_TAG); checked below.
PICOTLS_COMMIT=5a4461d8a3948d9d26bf861e7d90cb80d8093515
OPENSSL_REPO=https://github.com/openssl/openssl
OPENSSL_TAG=openssl-3.5.9
OPENSSL_COMMIT=45e844fa2a14ec92d146bd8f5778ac130b6625fb
API=24   # the app's minSdk

if [ "$HOST" = 1 ]; then
  CC="${CC:-cc}" CXX="${CXX:-c++}" AR="${AR:-ar}" STRIP="${STRIP:-strip}"
  OPENSSL_TARGET=""
  CMAKE_TOOLCHAIN=()
  LABEL=host
else
  : "${ABI:?set ABI to arm64-v8a, armeabi-v7a or x86_64}"
  ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:-}}}"
  if [ -z "$ndk" ] || [ ! -d "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin" ]; then
    echo "Building Slipstream for $ABI needs the Android NDK (set ANDROID_NDK_HOME)" >&2
    exit 2
  fi
  bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
  case "$ABI" in
    arm64-v8a)   triple=aarch64-linux-android;     OPENSSL_TARGET=android-arm64 ;;
    armeabi-v7a) triple=armv7a-linux-androideabi;  OPENSSL_TARGET=android-arm ;;
    x86_64)      triple=x86_64-linux-android;      OPENSSL_TARGET=android-x86_64 ;;
    *) echo "Unsupported ABI: $ABI" >&2; exit 2 ;;
  esac
  CC="$bin/${triple}${API}-clang" CXX="$bin/${triple}${API}-clang++" AR="$bin/llvm-ar" STRIP="$bin/llvm-strip"
  CMAKE_TOOLCHAIN=(-DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI"
    -DANDROID_PLATFORM="android-$API")
  export ANDROID_NDK_ROOT="$ndk" PATH="$bin:$PATH"
  LABEL="$ABI"
fi

for tool in git cmake make perl; do
  command -v "$tool" >/dev/null || { echo "Building Slipstream needs $tool" >&2; exit 2; }
done
GENERATOR=()
command -v ninja >/dev/null && GENERATOR=(-G Ninja)
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# checkout_pinned REPO COMMIT DIR [REF]: a clone at exactly COMMIT, or a failure.
checkout_pinned() {
  local repo="$1" commit="$2" dir="$3" ref="${4:-$2}"
  git init -q "$dir"
  git -C "$dir" fetch -q --depth 1 "$repo" "$ref"
  git -C "$dir" -c advice.detachedHead=false checkout -q FETCH_HEAD
  local got
  got="$(git -C "$dir" rev-parse HEAD)"
  if [ "$got" != "$commit" ]; then
    echo "Commit mismatch for $repo: got $got, pinned $commit" >&2
    exit 1
  fi
}

# Slipstream and its submodules.
SRC="$work/slipstream"
checkout_pinned "$SLIPSTREAM_REPO" "$SLIPSTREAM_COMMIT" "$SRC"
git -C "$SRC" submodule update -q --init --depth 1 2>/dev/null || git -C "$SRC" submodule update -q --init
while read -r want path; do
  got="$(git -C "$SRC/$path" rev-parse HEAD)"
  [ "$got" = "$want" ] || { echo "Submodule $path is at $got, pinned $want" >&2; exit 1; }
done <<< "$SUBMODULES"
patch -d "$SRC" -p1 --forward --batch --silent < "$HERE/slipstream-client.patch"

# OpenSSL, static, libraries only.
SSL_SRC="$work/openssl" SSL="$work/openssl-out"
checkout_pinned "$OPENSSL_REPO" "$OPENSSL_COMMIT" "$SSL_SRC" "refs/tags/$OPENSSL_TAG"
(
  cd "$SSL_SRC"
  common=(no-shared no-tests no-apps no-docs no-ui-console no-engine no-dso no-legacy no-comp
    --prefix="$SSL" --libdir=lib --openssldir=/nonexistent)
  if [ "$HOST" = 1 ]; then
    CC="$CC" ./Configure "${common[@]}" >/dev/null
  else
    ./Configure "$OPENSSL_TARGET" -D__ANDROID_API__=$API "${common[@]}" >/dev/null
  fi
  make -s -j"$JOBS" build_libs >/dev/null
  make -s install_dev >/dev/null
)

# picoquic (with picotls) as static libraries. pkg-config is pointed at nothing so a cross build never
# picks up the build machine's brotli; picotls then builds without certificate compression.
PQ="$work/picoquic-build"
mkdir -p "$work/no-pkgconfig"
PKG_CONFIG_LIBDIR="$work/no-pkgconfig" PKG_CONFIG_PATH="" cmake -S "$SRC/subprojects/picoquic" -B "$PQ" "${GENERATOR[@]}" \
  "${CMAKE_TOOLCHAIN[@]}" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER="$CC" -DCMAKE_CXX_COMPILER="$CXX" \
  -DPICOQUIC_FETCH_PTLS=ON -DBUILD_HTTP=OFF -DBUILD_DEMO=OFF -DBUILD_TESTING=OFF -DBUILD_LOGREADER=OFF \
  -DDISABLE_DEBUG_PRINTF=ON -DENABLE_ASAN=OFF -DENABLE_UBSAN=OFF -DPTLS_WITH_FUSION=OFF -DWITH_FUSION=OFF \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DOPENSSL_ROOT_DIR="$SSL" -DOPENSSL_USE_STATIC_LIBS=ON -DCMAKE_FIND_ROOT_PATH="$SSL" \
  -DCMAKE_C_FLAGS="-fPIE" >/dev/null
got="$(git -C "$PQ/_deps/picotls-src" rev-parse HEAD)"
[ "$got" = "$PICOTLS_COMMIT" ] || { echo "picotls is at $got, pinned $PICOTLS_COMMIT" >&2; exit 1; }
PKG_CONFIG_LIBDIR="$work/no-pkgconfig" cmake --build "$PQ" --target picoquic-core -j"$JOBS" >/dev/null
libs=("$PQ/libpicoquic-core.a")
for l in picotls-openssl picotls-minicrypto picotls-core; do
  f="$(find "$PQ" -name "lib$l.a" -print -quit)"
  [ -n "$f" ] || { echo "lib$l.a was not built" >&2; exit 1; }
  libs+=("$f")
done
libs+=("$SSL/lib/libssl.a" "$SSL/lib/libcrypto.a")

# Slipstream itself: the same sources and flags as its meson.build, linked statically except libc.
includes=(-I"$SRC/include" -I"$SRC/extern" -I"$SRC/extern/quick_arg_parser" -I"$SRC/extern/SPCDNS/src"
  -I"$SRC/extern/lua-resty-base-encoding" -I"$PQ/_deps/picotls-src/include" -I"$SRC/subprojects/picoquic/picoquic"
  -I"$SRC/subprojects/picoquic")
common_c=(src/slipstream_sockloop.c src/slipstream_utils.c src/slipstream_inline_dots.c
  extern/lua-resty-base-encoding/base32.c extern/SPCDNS/src/codec.c extern/SPCDNS/src/mappings.c
  extern/SPCDNS/src/netsimple.c extern/SPCDNS/src/output.c)
flags=(-O2 -fPIE -pthread -D_FILE_OFFSET_BITS=64 -w)

build_program() { # NAME C_SOURCES... -- CPP_SOURCE
  local name="$1"; shift
  local objs=() dir="$work/obj-$name" s
  mkdir -p "$dir"
  for s in "$@"; do
    local o="$dir/$(echo "$s" | tr / _).o"
    case "$s" in
      *.cpp) "$CXX" -std=gnu++11 "${flags[@]}" "${includes[@]}" -c "$SRC/$s" -o "$o" ;;
      *)     "$CC" -std=gnu11 "${flags[@]}" "${includes[@]}" -c "$SRC/$s" -o "$o" ;;
    esac
    objs+=("$o")
  done
  local extra=()
  [ "$HOST" = 1 ] && extra=(-ldl)
  "$CXX" -pie -pthread -static-libstdc++ -o "$work/$name" "${objs[@]}" \
    -Wl,--start-group "${libs[@]}" -Wl,--end-group "${extra[@]}" -lm
  "$STRIP" "$work/$name"
}

build_program slipstream-client src/slipstream_client.c src/slipstream_client_cli.cpp "${common_c[@]}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
if [ "$HOST" = 1 ]; then
  build_program slipstream-server src/slipstream_server.c src/slipstream_server_cc.c src/slipstream_server_cli.cpp "${common_c[@]}"
  cp "$work/slipstream-client" "$work/slipstream-server" "$OUT/"
  echo "Built $OUT/slipstream-client and $OUT/slipstream-server for this machine"
else
  cp "$work/slipstream-client" "$OUT/libslipstream.so"
  echo "Built $OUT/libslipstream.so ($(wc -c < "$OUT/libslipstream.so") bytes) for $LABEL"
fi
