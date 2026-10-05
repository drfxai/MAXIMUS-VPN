#!/usr/bin/env python3
"""Creates keys, certificates, the Xray server config and the client share links for the simulator.

Usage: setup.py <xray binary> <work dir> <host ip>
The host IP must not be loopback (Xray's WireGuard needs a routable target address).
"""
import hashlib, json, os, re, subprocess, sys, uuid

xray, work, host = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(work, exist_ok=True)


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def kv(text, key):
    return re.search(rf"{re.escape(key)}:\s*(\S+)", text).group(1)


def cert(name):
    key, crt = f"{work}/{name}.key", f"{work}/{name}.crt"
    run("openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
        "-days", "30", "-subj", f"/CN={name}", "-addext", f"subjectAltName=DNS:{name}", "-keyout", key, "-out", crt)
    der = subprocess.run(["openssl", "x509", "-in", crt, "-outform", "DER"], check=True, capture_output=True).stdout
    return key, crt, hashlib.sha256(der).hexdigest()


uid = str(uuid.uuid4())
reality = run(xray, "x25519")
enc = run(xray, "vlessenc")
pairs = re.findall(r'"decryption": "([^"]+)"\s*"encryption": "([^"]+)"', enc)
decryption, encryption = pairs[0]  # X25519 authentication
wg_server, wg_client = run(xray, "wg"), run(xray, "wg")
ws_key, ws_crt, ws_pin = cert("edge.example.com")
hy_key, hy_crt, hy_pin = cert("h.example.com")
site_key, site_crt, _ = cert("www.example.com")

S = {"reality": 21001, "ws": 21002, "enc": 21003, "hy2": 21004, "wg": 21005}
C = {k: v + 10000 for k, v in S.items()}  # the censor listens here and forwards to S

server = {
    "log": {"loglevel": "warning"},
    "inbounds": [
        {"port": S["reality"], "listen": "127.0.0.1", "protocol": "vless",
         "settings": {"clients": [{"id": uid, "flow": "xtls-rprx-vision"}], "decryption": "none"},
         "streamSettings": {"network": "tcp", "security": "reality", "realitySettings": {
             "target": "127.0.0.1:21443", "serverNames": ["www.example.com"],
             "privateKey": kv(reality, "PrivateKey"), "shortIds": ["6ba7"]}}},
        {"port": S["ws"], "listen": "127.0.0.1", "protocol": "vless",
         "settings": {"clients": [{"id": uid}], "decryption": "none"},
         "streamSettings": {"network": "ws", "wsSettings": {"path": "/ws"}, "security": "tls",
                            "tlsSettings": {"certificates": [{"certificateFile": ws_crt, "keyFile": ws_key}]}}},
        {"port": S["enc"], "listen": "127.0.0.1", "protocol": "vless",
         "settings": {"clients": [{"id": uid}], "decryption": decryption},
         "streamSettings": {"network": "tcp"}},
        {"port": S["hy2"], "listen": "127.0.0.1", "protocol": "hysteria",
         "settings": {"version": 2, "clients": [{"auth": "hy2-secret"}]},
         "streamSettings": {"network": "hysteria", "hysteriaSettings": {"version": 2}, "security": "tls",
                            "tlsSettings": {"alpn": ["h3"], "certificates": [{"certificateFile": hy_crt, "keyFile": hy_key}]}}},
        {"port": S["wg"], "listen": "127.0.0.1", "protocol": "wireguard",
         "settings": {"secretKey": kv(wg_server, "PrivateKey"),
                      "peers": [{"publicKey": kv(wg_client, "Password (PublicKey)"), "allowedIPs": ["10.0.0.2/32"]}]}},
    ],
    "outbounds": [{"protocol": "freedom", "settings": {"finalRules": [{"action": "allow"}]}}],
}
json.dump(server, open(f"{work}/server.json", "w"), indent=1)

links = {
    "REALITY": f"vless://{uid}@{host}:{C['reality']}?security=reality&sni=www.example.com&fp=chrome"
               f"&pbk={kv(reality, 'Password (PublicKey)')}&sid=6ba7&flow=xtls-rprx-vision&type=tcp#REALITY",
    "CDN (WS+TLS)": f"vless://{uid}@{host}:{C['ws']}?security=tls&sni=edge.example.com&fp=chrome&type=ws"
                    f"&host=edge.example.com&path=%2Fws&pcs={ws_pin}#CDN",
    "VLESS Encryption": f"vless://{uid}@{host}:{C['enc']}?security=none&type=tcp&encryption={encryption}#ENC",
    "Hysteria2": f"hysteria2://hy2-secret@{host}:{C['hy2']}?sni=h.example.com&pinSHA256={hy_pin}#HY2",
    "WireGuard": f"wireguard://{kv(wg_client, 'PrivateKey')}@{host}:{C['wg']}"
                 f"?publickey={kv(wg_server, 'Password (PublicKey)').replace('+', '%2B').replace('/', '%2F').replace('=', '%3D')}"
                 f"&address=10.0.0.2%2F32&mtu=1280#WG",
}
json.dump({"links": links, "ports": {str(C[k]): S[k] for k in S}, "site": [site_crt, site_key]},
          open(f"{work}/sim.json", "w"), indent=1)
print(json.dumps(links, indent=1))
