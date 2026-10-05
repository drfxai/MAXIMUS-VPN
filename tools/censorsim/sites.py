#!/usr/bin/env python3
"""The simulated internet: a TLS 1.3 site REALITY borrows its handshake from (127.0.0.1:21443) and the
page the client fetches through each tunnel (http://<host ip>:18080/, answers 204 like generate_204).

Usage: sites.py <sim.json> <host ip>
"""
import asyncio, json, ssl, sys

cfg = json.load(open(sys.argv[1]))
host = sys.argv[2]


async def page(reader, writer):
    try:
        await reader.readuntil(b"\r\n\r\n")
        writer.write(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
        await writer.drain()
    except Exception:
        pass
    writer.close()


async def tls_site(reader, writer):
    try:
        await reader.read(4096)
        writer.write(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
        await writer.drain()
    except Exception:
        pass
    writer.close()


async def main():
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3
    ctx.set_alpn_protocols(["h2", "http/1.1"])
    ctx.load_cert_chain(*cfg["site"])
    await asyncio.start_server(tls_site, "127.0.0.1", 21443, ssl=ctx)
    await asyncio.start_server(page, host, 18080)
    print("sites ready", flush=True)
    await asyncio.sleep(10 ** 9)


asyncio.run(main())
