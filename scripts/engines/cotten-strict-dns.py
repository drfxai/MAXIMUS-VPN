#!/usr/bin/env python3
"""Apply MAXIMUS's strict encrypted resolver policy to the pinned Cotten source."""
from pathlib import Path
import sys
root = Path(sys.argv[1])
def replace(path, old, new, count=1):
    p = root / path
    text = p.read_text()
    if text.count(old) != count:
        raise SystemExit("Pinned Cotten source changed: " + path)
    p.write_text(text.replace(old, new))
for transport in ("DoT", "DoH"):
    replace("internal/client/mtu.go",
        "return []resolverTransport{transport" + transport + ", transportUDP, transportTCP}",
        "return []resolverTransport{transport" + transport + "}")
replace("internal/client/transport_chain_test.go", '[]string{"DoT", "UDP/53", "TCP/53"}', '[]string{"DoT"}')
replace("internal/client/transport_chain_test.go", '[]string{"DoH", "UDP/53", "TCP/53"}', '[]string{"DoH"}', 2)
replace("internal/client/transport_chain_test.go", "func TestEncryptedChainsEndOnSurvivalTransport", "func TestEncryptedChainsNeverDowngrade")
replace("internal/client/transport_chain_test.go", "if last != transportTCP && last != transportUDP {", "if len(chain) != 1 || (last != transportDoT && last != transportDoH) {")
replace("internal/client/transport_chain_test.go", "expected a plain transport", "expected only the selected encrypted transport")
