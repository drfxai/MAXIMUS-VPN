// MAXIMUS VPN — Cloudflare relay Worker (VLESS over WebSocket, TCP only). MAXIMUS's own code.
//
// The app deploys this file unchanged on the user's own Cloudflare account (Panels → Cloudflare → Cloudflare relay).
// Its SHA-256 is pinned in the app (CloudflareRelay.WORKER_SHA256) and checked by a unit test, so any change to this
// file has to be reviewed and re-pinned. The app adds two secret bindings per user, never written into this file:
//   RELAY_UUID  the VLESS id (a random UUID made on the phone)
//   RELAY_PATH  the WebSocket path (random, at least 16 characters)
// A request on any other path, without a WebSocket upgrade, or with another id gets 404 / a closed socket.
// What it does: one WebSocket = one TCP connection to the address the VLESS header names (command 1 only; no UDP,
// no mux, no port 25). No logging, no storage, no outside services. Cloudflare itself refuses sockets to its own
// address ranges, so sites hosted on Cloudflare cannot be opened through this relay (Workers limit).

import { connect } from "cloudflare:sockets";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function notFound() {
  return new Response("Not found", { status: 404, headers: { "cache-control": "no-store" } });
}

function sameText(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function sameBytes(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

function uuidBytes(text) {
  const hex = text.replace(/-/g, "");
  const out = new Uint8Array(16);
  for (let i = 0; i < 16; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

// Xray sends the first bytes in Sec-WebSocket-Protocol (base64url) when the path carries ?ed=.
function earlyData(header) {
  if (!header) return null;
  try {
    const binary = atob(header.replace(/-/g, "+").replace(/_/g, "/"));
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

// VLESS request: version(1) id(16) addons-length(1) addons command(1) port(2) address-type(1) address, then payload.
function parseHeader(buf, id) {
  if (buf.length < 24 || buf[0] !== 0 || !sameBytes(buf.subarray(1, 17), id)) return null;
  let i = 18 + buf[17];
  if (buf.length < i + 4) return null;
  const command = buf[i];
  if (command !== 1) return null;
  const port = (buf[i + 1] << 8) | buf[i + 2];
  const type = buf[i + 3];
  i += 4;
  let hostname;
  if (type === 1) {
    if (buf.length < i + 4) return null;
    hostname = Array.from(buf.subarray(i, i + 4)).join(".");
    i += 4;
  } else if (type === 2) {
    const length = buf[i];
    i += 1;
    if (length === 0 || buf.length < i + length) return null;
    hostname = new TextDecoder().decode(buf.subarray(i, i + length));
    i += length;
  } else if (type === 3) {
    if (buf.length < i + 16) return null;
    const parts = [];
    for (let k = 0; k < 8; k++) parts.push(((buf[i + 2 * k] << 8) | buf[i + 2 * k + 1]).toString(16));
    hostname = parts.join(":");
    i += 16;
  } else {
    return null;
  }
  if (port === 0 || port === 25) return null;
  return { hostname, port, payload: buf.subarray(i) };
}

function relay(ws, id, early) {
  let socket = null;
  let writer = null;
  let closed = false;
  let queue = Promise.resolve();

  const close = () => {
    if (closed) return;
    closed = true;
    try { ws.close(1000, "done"); } catch {}
    try { if (socket) socket.close(); } catch {}
  };

  const pipeBack = async () => {
    try {
      const reader = socket.readable.getReader();
      for (;;) {
        const { value, done } = await reader.read();
        if (done || closed) break;
        ws.send(value);
      }
    } catch {}
    close();
  };

  const handle = async (chunk) => {
    if (closed) return;
    if (writer) {
      await writer.write(chunk);
      return;
    }
    const target = parseHeader(chunk, id);
    if (!target) {
      close();
      return;
    }
    socket = connect({ hostname: target.hostname, port: target.port });
    await socket.opened;
    writer = socket.writable.getWriter();
    ws.send(new Uint8Array([0, 0]));
    if (target.payload.length > 0) await writer.write(target.payload);
    pipeBack();
  };

  const enqueue = (chunk) => {
    queue = queue.then(() => handle(chunk)).catch(close);
  };

  ws.addEventListener("message", (event) => {
    const data = typeof event.data === "string" ? new TextEncoder().encode(event.data) : new Uint8Array(event.data);
    enqueue(data);
  });
  ws.addEventListener("close", close);
  ws.addEventListener("error", close);
  if (early && early.length > 0) enqueue(early);
}

export default {
  async fetch(request, env) {
    const id = String(env.RELAY_UUID || "").toLowerCase();
    const path = String(env.RELAY_PATH || "");
    const url = new URL(request.url);
    if (!UUID_PATTERN.test(id) || path.length < 16 || !sameText(url.pathname, "/" + path)) return notFound();
    if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") return notFound();
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();
    relay(server, uuidBytes(id), earlyData(request.headers.get("Sec-WebSocket-Protocol")));
    return new Response(null, { status: 101, webSocket: client });
  },
};
