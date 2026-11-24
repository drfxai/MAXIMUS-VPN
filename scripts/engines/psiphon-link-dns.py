#!/usr/bin/env python3
"""Lets a psiphon-tunnel-core ConsoleClient use the phone's link DNS servers.

    python3 scripts/engines/psiphon-link-dns.py SRC/ConsoleClient/main.go

ConsoleClient only wires a DNS provider in its standalone TUN mode. MAXIMUS owns the Android TUN and passes the
underlying link's DNS servers in MAXIMUS_PSIPHON_LINK_DNS (comma-separated IPs). Same change as the one inlined in
scripts/engines/psiphon.sh; used by scripts/engines/shirokhorshid.sh for the Shir o Khorshid fork.
"""
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
source = path.read_text()
anchor = "\t// All config fields should be set before calling Commit."
if source.count(anchor) != 1:
    raise SystemExit("Psiphon DNS provider patch no longer matches ConsoleClient")
if "type tunProvider struct" not in source:
    raise SystemExit("ConsoleClient has no tunProvider to reuse")
source = source.replace(anchor, """\tif config.DNSServerGetter == nil {
\t\tvar linkDNS []string
\t\tfor _, address := range strings.Split(os.Getenv("MAXIMUS_PSIPHON_LINK_DNS"), ",") {
\t\t\tif ip := net.ParseIP(address); ip != nil && !ip.IsUnspecified() && !ip.IsLoopback() {
\t\t\t\tlinkDNS = append(linkDNS, ip.String())
\t\t\t}
\t\t}
\t\tif len(linkDNS) > 0 {
\t\t\tconfig.DNSServerGetter = &tunProvider{dnsServers: linkDNS}
\t\t}
\t}

""" + anchor)
if '\t"net"\n' not in source:
    if '\t"io/ioutil"\n' not in source:
        raise SystemExit("ConsoleClient imports changed; add \"net\" by hand")
    source = source.replace('\t"io/ioutil"\n', '\t"io/ioutil"\n\t"net"\n', 1)
path.write_text(source)
