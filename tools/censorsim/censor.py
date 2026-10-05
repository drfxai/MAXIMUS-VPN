#!/usr/bin/env python3
"""A small, deliberately harsh censor that sits between the client and the simulated server.

Usage: censor.py <sim.json> <modes>      modes: comma list, or "none", or @file
  @file      Read the modes from a file and follow its changes (the soak run changes the censor while
             a connection is held). A change also cuts flows already open that the new modes block,
             as DPI that starts filtering mid-session does.

  sni        TCP: the first chunk of a connection that names a blocked server (TLS SNI) is answered
             with a reset. Like most national DPI it looks at the packet, not the reassembled stream.
  fe         TCP: "fully encrypted" first packet (high entropy, no TLS/HTTP look, as in the GFW's 2023
             detector, without its random 26% sampling) is reset.
  udp-block  All UDP is dropped (QUIC and WireGuard die).
  udp-dpi    The first datagram of each UDP flow is classified; a QUIC long header or a WireGuard
             handshake initiation blocks the whole flow.
  throttle   Every TCP chunk and UDP datagram is delayed 150 ms and 5% of datagrams are lost.
"""
import asyncio, json, random, socket, struct, sys

BLOCKED_NAMES = [b"www.example.com", b"edge.example.com", b"h.example.com"]
cfg = json.load(open(sys.argv[1]))
MODES_FILE = sys.argv[2][1:] if sys.argv[2].startswith("@") else None


def parse_modes(text):
    return set(text.strip().split(",")) - {"none", ""}


MODES = parse_modes(open(MODES_FILE).read() if MODES_FILE else sys.argv[2])
TCP_FLOWS = {}  # writer -> first chunk, for cutting open flows when the modes change
UDP_PORTS = {31004, 31005}
stats = {"reset": 0, "udp_flows_blocked": 0}


def fully_encrypted(data: bytes) -> bool:
    if len(data) < 6:
        return False
    bits = sum(bin(b).count("1") for b in data) / len(data)
    if bits <= 3.4 or bits >= 4.6:
        return False  # Ex1
    printable = [0x20 <= b <= 0x7e for b in data]
    if all(printable[:6]) or sum(printable) > len(data) / 2:
        return False  # Ex2, Ex3
    run = best = 0
    for p in printable:
        run = run + 1 if p else 0
        best = max(best, run)
    if best > 20:
        return False  # Ex4
    if data[0] == 0x16 and data[1] == 0x03 or data[:4] in (b"GET ", b"POST", b"HTTP"):
        return False  # Ex5
    return True


def tcp_blocked(first):
    if "sni" in MODES and first[0] == 0x16 and any(n in first for n in BLOCKED_NAMES):
        return True
    return "fe" in MODES and fully_encrypted(first)


def reset(writer):
    sock = writer.get_extra_info("socket")
    if sock is not None:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
    writer.close()
    stats["reset"] += 1


async def pipe(reader, writer):
    try:
        while data := await reader.read(65536):
            if "throttle" in MODES:
                await asyncio.sleep(0.15)
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        writer.close()


async def tcp_handler(reader, writer, upstream_port):
    try:
        first = await asyncio.wait_for(reader.read(65536), 10)
    except Exception:
        writer.close()
        return
    if not first:
        writer.close()
        return
    if tcp_blocked(first):
        return reset(writer)
    try:
        up_reader, up_writer = await asyncio.open_connection("127.0.0.1", upstream_port)
    except OSError:
        writer.close()
        return
    if "throttle" in MODES:
        await asyncio.sleep(0.15)
    up_writer.write(first)
    TCP_FLOWS[writer] = first
    try:
        await asyncio.gather(pipe(reader, up_writer), pipe(up_reader, writer))
    finally:
        TCP_FLOWS.pop(writer, None)
        up_writer.close()


def udp_dpi_blocks(first):
    return "udp-dpi" in MODES and ((first[0] & 0xC0) == 0xC0 or (first[0] == 1 and len(first) == 148))


RELAYS = []


async def follow_modes_file():
    global MODES
    while True:
        await asyncio.sleep(0.5)
        try:
            new = parse_modes(open(MODES_FILE).read())
        except OSError:
            continue
        if new == MODES:
            continue
        MODES = new
        cut = [w for w, first in list(TCP_FLOWS.items()) if tcp_blocked(first)]
        for w in cut:
            reset(w)
        for relay in RELAYS:
            for addr, flow in list(relay.flows.items()):
                flow["blocked"] = udp_dpi_blocks(flow["first"])
                if not flow["blocked"] and flow["up"] is None and not flow.get("opening"):
                    flow["opening"] = True
                    asyncio.ensure_future(relay.open_upstream(addr))
        print(f"censor now: {sorted(MODES) or ['none']}, cut {len(cut)} TCP flows", flush=True)


class UdpRelay(asyncio.DatagramProtocol):
    def __init__(self, upstream_port):
        self.upstream_port = upstream_port
        self.flows = {}  # client addr -> (transport to server, blocked)

    def connection_made(self, transport):
        self.transport = transport
        RELAYS.append(self)

    def datagram_received(self, data, addr):
        if "udp-block" in MODES:
            return
        flow = self.flows.get(addr)
        if flow is None:
            blocked = udp_dpi_blocks(data)
            if blocked:
                stats["udp_flows_blocked"] += 1
            flow = self.flows[addr] = {"blocked": blocked, "up": None, "queue": [data], "first": data}
            if not blocked:
                flow["opening"] = True
                asyncio.ensure_future(self.open_upstream(addr))
            return
        if flow["blocked"]:
            return
        if flow["up"] is None:
            flow["queue"].append(data)
        else:
            self.send_up(flow, data)

    def send_up(self, flow, data):
        if "throttle" in MODES:
            if random.random() < 0.05:
                return
            asyncio.get_running_loop().call_later(0.15, flow["up"].sendto, data)
        else:
            flow["up"].sendto(data)

    async def open_upstream(self, addr):
        loop = asyncio.get_running_loop()
        relay = self

        class Back(asyncio.DatagramProtocol):
            def datagram_received(self, data, _):
                if "throttle" in MODES:
                    if random.random() < 0.05:
                        return
                    loop.call_later(0.15, relay.transport.sendto, data, addr)
                else:
                    relay.transport.sendto(data, addr)

        up, _ = await loop.create_datagram_endpoint(Back, remote_addr=("127.0.0.1", self.upstream_port))
        flow = self.flows[addr]
        flow["up"] = up
        for d in flow["queue"]:
            self.send_up(flow, d)
        flow["queue"] = []


async def main():
    loop = asyncio.get_running_loop()
    for listen, upstream in cfg["ports"].items():
        listen, upstream = int(listen), int(upstream)
        if listen in UDP_PORTS:
            await loop.create_datagram_endpoint(lambda u=upstream: UdpRelay(u), local_addr=("0.0.0.0", listen))
        else:
            await asyncio.start_server(lambda r, w, u=upstream: tcp_handler(r, w, u), "0.0.0.0", listen)
    print(f"censor ready: {sorted(MODES) or ['none']}", flush=True)
    if MODES_FILE:
        asyncio.ensure_future(follow_modes_file())
    while True:
        await asyncio.sleep(3600)


asyncio.run(main())
