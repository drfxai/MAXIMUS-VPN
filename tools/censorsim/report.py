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


def under10(rs):
    return f"{100 * sum(r['ok'] and r['ms'] < 10000 for r in rs) / len(rs):.0f}%" if rs else "–"


def pct_or_dash(rs):
    return pct(rs) if rs else "–"


def plain(scenario):
    return not scenario.endswith("+dead") and not scenario.endswith("+lab")


scenarios = [s for s in dict.fromkeys(r["scenario"] for r in rows) if plain(s)]
profiles = sorted({r["profile"] for r in rows if plain(r["scenario"])})
print("| Scenario | main: connected | branch: connected | branch: time (median / p90) | branch under 10 s "
      "| Phase 4: connected | Phase 4: time (median / p90) | Phase 4 under 10 s |")
print("|---|---|---|---|---|---|---|---|")
for s in scenarios:
    m, b, p4 = by[(s, "main")], by[(s, "branch")], by[(s, "phase4")]
    print(f"| {s} | {pct_or_dash(m)} | {pct_or_dash(b)} | {times(b)} | {under10(b)} "
          f"| {pct_or_dash(p4)} | {times(p4)} | {under10(p4)} |")
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
        cells.append(f"{pct_or_dash(m)} → {pct_or_dash(b)} ({top})")
    print(f"| {s} | " + " | ".join(cells) + " |")
dead = [s for s in dict.fromkeys(r["scenario"] for r in rows) if s.endswith("+dead")]
if dead:
    print()
    print("Saved server down (its address answers nothing):")
    print()
    print("| Scenario | before Phase 4: connected | before: time to a working server | Phase 4: connected | Phase 4: time (median / p90) |")
    print("|---|---|---|---|---|")
    for s in dead:
        b, p4 = by[(s, "branch")], by[(s, "phase4")]
        print(f"| {s.removesuffix('+dead')} | {pct_or_dash(b)} | {times(b)} | {pct_or_dash(p4)} | {times(p4)} |")


# The failure lab: what the app does when things go wrong (run.sh LAB=1).
lab = [s for s in dict.fromkeys(r["scenario"] for r in rows) if s.endswith("+lab")]
if lab:
    print()
    print("### Failure lab")
    print()
    print("| Filtering | each saved server connects | time (median / p90) | nothing can work: no path claimed | time to give up |")
    print("|---|---|---|---|---|")
    for s in lab:
        c, f = by[(s, "lab-connect")], by[(s, "lab-fail-closed")]
        print(f"| {s.removesuffix('+lab')} | {pct_or_dash(c)} | {times(c)} | {pct_or_dash(f)} | {times(f)} |")
