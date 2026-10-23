#!/usr/bin/env bash
# MIT ByeDPI, pinned native TCP retransmission/fake-SNI transport (not a TLS stream prefix).
set -euo pipefail
: "${OUT:?set OUT}"
REV=ba532298de7b28cfe854aea83d061369d13ca290
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$OUT"
out=$(cd "$OUT" && pwd)
git -C "$work" init -q
git -C "$work" fetch -q --depth=1 https://github.com/hufrea/byedpi.git "$REV"
git -C "$work" checkout -q --detach FETCH_HEAD
test "$(git -C "$work" rev-parse HEAD)" = "$REV"
if [ "${HOST:-0}" = 1 ]; then
  compiler=${CC:-cc}
else
  ndk=${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}
  case "${ABI:?set ABI}" in
    arm64-v8a) triple=aarch64-linux-android;;
    armeabi-v7a) triple=armv7a-linux-androideabi;;
    x86_64) triple=x86_64-linux-android;;
    *) exit 2;;
  esac
  compiler="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/${triple}24-clang"
fi
# Limit this helper to the selected numeric endpoint. It must never become a general open proxy.
python3 - "$work/proxy.c" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1]); s = p.read_text()
needle = '    union sockaddr_u addr = *dst;'
start = s.index('int create_conn(')
index = s.index(needle, start)
prefix, body = s[:index], s[index:]
s = prefix + body.replace(needle, '''    const char *allowed = getenv("MAXIMUS_PEER_IP");
    const char *port = getenv("MAXIMUS_PEER_PORT");
    struct in_addr wanted;
    if (!allowed || !port || inet_pton(AF_INET, allowed, &wanted) != 1 ||
        dst->sa.sa_family != AF_INET || dst->in.sin_addr.s_addr != wanted.s_addr ||
        ntohs(dst->in.sin_port) != atoi(port)) return -1;
''' + needle, 1)
p.write_text(s)
PY
make -C "$work" -j2 CC="$compiler" CFLAGS='-I. -std=c99 -O2 -fPIE' LDFLAGS='-pie -Wl,-z,max-page-size=16384'
cp "$work/ciadpi" "$out/libbyedpi.so"
