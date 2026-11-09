#!/usr/bin/env bash
set -euo pipefail
umask 077
stage="$(cd "$(dirname "$0")" && pwd)"
[[ $(id -u) == 0 ]] || { echo 'Root SSH is required'; exit 1; }
[[ -x /usr/local/x-ui/x-ui ]] || { echo 'Install 3X-UI first'; exit 1; }
for p in /opt/psi-panel /opt/regionhop-admin /opt/maximus-regionhop /etc/systemd/system/psi-panel.service /etc/systemd/system/psi-tunnel@.service /etc/systemd/system/regionhop-firewall.service /etc/systemd/system/regionhop-upstream.service; do
  [[ ! -e "$p" && ! -L "$p" ]] || { echo 'Existing RegionHop installation must be reviewed; nothing overwritten'; exit 1; }
done
if command -v iptables >/dev/null && iptables -S REGIONHOP-SOCKS >/dev/null 2>&1; then
  echo 'Existing RegionHop firewall chain must be reviewed'; exit 1
fi
! id psipanel >/dev/null 2>&1 || { echo 'Existing psipanel user must be reviewed'; exit 1; }
for exe in curl python3 sha256sum systemctl ss; do command -v "$exe" >/dev/null || { echo "Missing $exe"; exit 1; }; done
mkdir -m 700 "$stage/source"
mkdir "$stage/source/panel" "$stage/source/systemd"
python3 - "$stage" <<'PY'
import hashlib,json,pathlib,subprocess,sys
root=pathlib.Path(sys.argv[1]); pins=json.loads((root/'pins.json').read_text())
for name,digest in pins['files'].items():
    assert name in ['install.sh','VERSION','systemd/psi-panel.service','systemd/psi-tunnel@.service','systemd/regionhop-upstream.service']
    dest=root/'source'/name
    subprocess.run(['curl','--proto','=https','-fsSL','--connect-timeout','15','--max-time','90',f"https://raw.githubusercontent.com/freeb5d/regionhop/{pins['commit']}/{name}",'-o',str(dest)],check=True)
    assert hashlib.sha256(dest.read_bytes()).hexdigest()==digest, 'RegionHop source checksum mismatch'
p=root/'source/install.sh';text=p.read_text();assert text.endswith('\nmenu\n')
# This is a fresh installation: legacy migration rules could belong to another
# application, so retain them. Only the new dedicated RegionHop chain is managed.
lines=text[:-len('menu\n')].splitlines(True)
def legacy(line):
    return ('while iptables -D INPUT ' in line and '--dport 19000:19999' in line) or ('ufw delete deny in proto tcp from any to any port 19000:19999' in line)
assert sum(legacy(line) for line in lines)==5
p.write_text(''.join(line for line in lines if not legacy(line)))
PY
# Source the reviewed functions from a checkout-shaped folder; no menu or mutable clone.
source "$stage/source/install.sh"
cleanup() {
  code=$?; trap - EXIT
  if ((code != 0)); then
    uninstall_all <<<YES >/dev/null 2>&1 || true
    rm -rf -- /opt/maximus-regionhop
    echo 'RegionHop setup failed; fresh managed installation removed' >&2
  fi
  exit "$code"
}
trap cleanup EXIT
install_packages
ensure_user
ensure_dirs
arch=$(detect_arch)
expected=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["bundles"][sys.argv[2]])' "$stage/pins.json" "$arch")
curl --proto '=https' -fsSL --connect-timeout 15 --max-time 300 "https://github.com/freeb5d/regionhop/releases/download/v1.27.0/regionhop-linux-$arch.tar.gz" -o "$stage/bundle.tar.gz"
echo "$expected  $stage/bundle.tar.gz" | sha256sum -c - >/dev/null
python3 - "$stage" <<'PY'
import pathlib,tarfile,sys
root=pathlib.Path(sys.argv[1]); out=root/'bundle';out.mkdir()
with tarfile.open(root/'bundle.tar.gz') as t:
    for name in ['psi-panel','ConsoleClient','xray','core-version.txt']:
        try: f=t.getmember(name)
        except KeyError:
            assert name not in ['psi-panel','ConsoleClient'];continue
        assert f.isfile() and f.size<100_000_000
        (out/name).write_bytes(t.extractfile(f).read())
PY
install -m 755 -o root -g root "$stage/bundle/psi-panel" "$PREFIX/panel/psi-panel"
install -m 755 -o root -g root "$stage/bundle/ConsoleClient" "$PREFIX/core/ConsoleClient"
[[ ! -f "$stage/bundle/xray" ]] || install -m 755 -o root -g root "$stage/bundle/xray" "$PREFIX/core/xray"
echo '1.27.0' > "$VERSION_FILE"
mkdir -m 700 /opt/maximus-regionhop
cp -R "$stage/source" /opt/maximus-regionhop/source
install -m 700 "$stage/control.py" /opt/maximus-regionhop/control.py
install -m 600 -o psipanel -g psipanel "$stage/Psiphon.config" "$PREFIX/panel/extra-config.json"
python3 <<'PY'
import json,pathlib,secrets,socket,subprocess
root=pathlib.Path('/opt/maximus-regionhop');password=secrets.token_urlsafe(32)
hash_=subprocess.check_output(['/opt/psi-panel/panel/psi-panel','-hash-password',password],text=True).strip()
assert hash_.startswith('$2')
with socket.socket() as s:
    s.bind(('127.0.0.1',0));port=s.getsockname()[1]
prefix='/'+secrets.token_hex(16)
env=pathlib.Path('/opt/psi-panel/panel/panel.env')
env.write_text(f'PANEL_LISTEN=127.0.0.1:{port}\nPANEL_ADMIN_HASH={hash_}\nPANEL_SESSION_SECRET={secrets.token_hex(32)}\nPANEL_PATH_PREFIX={prefix}\nSOCKS_PORT_RANGE=19000-19999\n')
env.chmod(0o600)
p=root/'control.json';p.write_text(json.dumps({'password':password,'port':port,'prefix':prefix}));p.chmod(0o600)
PY
install_units
setup_firewall
start_panel >/dev/null
install_psictl
sleep 2
systemctl is-active --quiet psi-panel
trap - EXIT
echo '##RESULT REGIONHOP_INSTALLED=1'
