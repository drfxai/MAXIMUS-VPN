#!/usr/bin/env python3
"""Summarises results.jsonl into the KPI tables of docs/WAR_PLAN.md (Markdown on stdout)."""
import json, statistics, sys
from collections import defaultdict

rows = [json.loads(l) for l in open(sys.argv[1]) if l.startswith("{")]
by = defaultdict(list)
for r in rows:
    by[(r["scenario"], r["mode"])].append(r)
    by[(r["scenario"], r["mode"], r["profile"])].append(r)


def pct(rs):
    return f"{100 * sum(r['ok'] for r in rs) / len(rs):.0f}%"


def times(rs):
    ok = sorted(r["ms"] for r in rs if r["ok"])
    if not ok:
        return "–"
    p90 = ok[min(len(ok) - 1, int(round(0.9 * (len(ok) - 1))))]
    return f"{statistics.median(ok) / 1000:.1f} s / {p90 / 1000:.1f} s"


scenarios = list(dict.fromkeys(r["scenario"] for r in rows))
profiles = sorted({r["profile"] for r in rows})
print("| Scenario | main: connected | branch: connected | branch: time to connect (median / p90) | under 10 s (branch) |")
print("|---|---|---|---|---|")
for s in scenarios:
    m, b = by[(s, "main")], by[(s, "branch")]
    under = f"{100 * sum(r['ok'] and r['ms'] < 10000 for r in b) / len(b):.0f}%"
    print(f"| {s} | {pct(m)} | {pct(b)} | {times(b)} | {under} |")
print()
print("| Scenario | " + " | ".join(profiles) + " |")
print("|---|" + "---|" * len(profiles))
for s in scenarios:
    cells = []
    for p in profiles:
        m, b = by[(s, "main", p)], by[(s, "branch", p)]
        paths = defaultdict(int)
        for r in b:
            if r["ok"]:
                paths[r["path"]] += 1
        top = max(paths, key=paths.get) if paths else "none"
        cells.append(f"{pct(m)} → {pct(b)} ({top})")
    print(f"| {s} | " + " | ".join(cells) + " |")
