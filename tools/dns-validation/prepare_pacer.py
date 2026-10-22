from pathlib import Path
import sys
root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1]) / "dnstt-client"
out.mkdir(parents=True, exist_ok=True)
p = (root / "scripts/engines/dnstt-query-rate.patch").read_text()
part = p.split("+++ b/dnstt-client/querypace.go\n",1)[1].split("diff -ruN",1)[0]
text = "\n".join(x[1:] for x in part.splitlines() if x.startswith("+")) + "\n"
p = (root / "scripts/engines/dnstt-multipath.patch").read_text()
part = p.split("+++ b/dnstt-client/querypace.go\n",1)[1]
text += "\n".join(x[1:] for x in part.splitlines() if x.startswith("+")) + "\n"
(out / "querypace.go").write_text(text)
