#!/usr/bin/env python3
"""Summarises soak.jsonl (soak.sh): outages and time without traffic while the censor changes."""
import json, sys
from collections import defaultdict

rows = [json.loads(l) for l in open(sys.argv[1]) if l.startswith("{")]
# One file can hold several soak.sh runs; a run starts with a "connected" event at t < 5 s.
runs = defaultdict(list)
run_no = defaultdict(int)
for r in rows:
    if r["event"] == "connected" and r["t"] < 5:
        run_no[r["soak"]] += 1
    runs[r["soak"]].append({**r, "run": run_no[r["soak"]]})

print("Totals over all runs in the file.")
print()
print("| Failover logic | requests through | outages | time without traffic | longest outage | switches | outages per hour* |")
print("|---|---|---|---|---|---|---|")
for name, label in (("branch", "before Phase 4"), ("phase4", "Phase 4")):
    rs = runs.get(name)
    if not rs:
        continue
    reqs = sorted((r for r in rs if r["event"] == "request"), key=lambda r: (r["run"], r["t"]))
    n_runs = max(r["run"] for r in rs)
    outages, down, longest, run, prev_run = 0, 0, 0, 0, None
    for r in reqs:
        if r["run"] != prev_run:
            run, prev_run = 0, r["run"]
        if r["ok"]:
            run = 0
            continue
        down += 1
        run += 1
        longest = max(longest, run)
        if run == 2:  # one lost request is noise; two in a row is an outage the user notices
            outages += 1
    span = n_runs * (max(r["t"] for r in reqs) - min(r["t"] for r in reqs)) / 3600
    switches = sum(r["event"] == "failover" for r in rs)
    ok = sum(r["ok"] for r in reqs)
    print(f"| {label} | {100 * ok / len(reqs):.0f}% | {outages} | {down} s | {longest} s | {switches} | {outages / span:.0f} |")
print()
print("*The censor changes every 90 s here, far more often than a real network; per hour is scaled from the run.")
