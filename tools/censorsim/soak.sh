#!/usr/bin/env bash
# KPI 3: holds a connection for 8 minutes while the censor changes what it blocks, with the failover
# logic from before Phase 4 and after it. ./soak.sh <xray binary> <work dir> <host ip>
# Results: <work dir>/soak.jsonl, summarised by soak_report.py
set -euo pipefail
XRAY="$(realpath "$1")"; WORK="$(realpath -m "$2")"; HOST="$3"
HERE="$(cd "$(dirname "$0")" && pwd)"
python3 "$HERE/setup.py" "$XRAY" "$WORK" "$HOST" >/dev/null
(cd "$HERE/jvm" && gradle -q installDist)
"$XRAY" run -c "$WORK/server.json" >"$WORK/server.log" 2>&1 & SERVER=$!
python3 "$HERE/sites.py" "$WORK/sim.json" "$HOST" >"$WORK/sites.log" 2>&1 & SITES=$!
echo none >"$WORK/modes"
python3 "$HERE/censor.py" "$WORK/sim.json" "@$WORK/modes" >"$WORK/censor-soak.log" 2>&1 & CENSOR=$!
trap 'kill $SERVER $SITES $CENSOR 2>/dev/null || true' EXIT
sleep 1
: >"$WORK/soak.jsonl"
for policy in before after; do
  "$HERE/jvm/build/install/censorsim/bin/censorsim" "$XRAY" "$WORK/sim.json" "$HOST" soak "$policy" "$WORK/modes" \
    | grep --line-buffered '^{' >>"$WORK/soak.jsonl"
done
python3 "$HERE/soak_report.py" "$WORK/soak.jsonl"
