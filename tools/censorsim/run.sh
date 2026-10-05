#!/usr/bin/env bash
# Runs every scenario: ./run.sh <xray binary> <work dir> <host ip> [trials]
# SCENARIOS="sni fe" limits the run to those scenarios.
# The host IP must be a non-loopback address of this machine. Results: <work dir>/results.jsonl
set -euo pipefail
XRAY="$(realpath "$1")"; WORK="$(realpath -m "$2")"; HOST="$3"; TRIALS="${4:-10}"
HERE="$(cd "$(dirname "$0")" && pwd)"
python3 "$HERE/setup.py" "$XRAY" "$WORK" "$HOST" >/dev/null
(cd "$HERE/jvm" && gradle -q installDist)
"$XRAY" run -c "$WORK/server.json" >"$WORK/server.log" 2>&1 & SERVER=$!
python3 "$HERE/sites.py" "$WORK/sim.json" "$HOST" >"$WORK/sites.log" 2>&1 & SITES=$!
trap 'kill $SERVER $SITES 2>/dev/null || true' EXIT
sleep 1
: >"$WORK/results.jsonl"
for scenario in ${SCENARIOS:-none sni fe udp-block udp-dpi throttle sni,fe,udp-dpi}; do
  python3 "$HERE/censor.py" "$WORK/sim.json" "$scenario" >"$WORK/censor-$scenario.log" 2>&1 & CENSOR=$!
  sleep 0.5
  "$HERE/jvm/build/install/censorsim/bin/censorsim" "$XRAY" "$WORK/sim.json" "$HOST" "$scenario" "$TRIALS" \
    | grep --line-buffered '^{' | tee -a "$WORK/results.jsonl" | tail -n 2
  kill $CENSOR; wait $CENSOR 2>/dev/null || true
done
python3 "$HERE/report.py" "$WORK/results.jsonl"
