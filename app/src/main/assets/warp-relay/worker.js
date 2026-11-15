// MAXIMUS VPN — WARP account API relay (Cloudflare Worker). Deploy it on your own Cloudflare account.
//
// Aether's account API lane posts each WARP account call (register, enroll, refresh) here as JSON
// {method, host, path, headers, body}; this Worker forwards it to the WARP API from Cloudflare's edge and
// answers {ok, status, body} (the envelope UAC SNI Spoofer's relay uses). Your phone then only shows
// your workers.dev name on the wire, never api.cloudflareclient.com.
//
// Setup: Cloudflare dashboard → Workers → Create → paste this file → Deploy. Then set a secret variable
// RELAY_KEY (Settings → Variables, any long random text) and enter, in MAXIMUS: Lab → Aether → WARP API relay,
//   https://<worker-name>.<your-subdomain>.workers.dev/<RELAY_KEY>
// Without RELAY_KEY the Worker refuses every call, so nobody else can use your relay.

const HOSTS = new Set(["api.cloudflareclient.com", "api.devices.cloudflare.com"]);
const METHODS = new Set(["GET", "POST", "PUT", "PATCH", "DELETE"]);
const PATH = /^\/v0[a-z0-9]{1,16}(\/[A-Za-z0-9._~-]{1,128}){0,6}$/;
const HEADERS = new Set(["authorization", "cf-client-version", "content-type", "accept", "accept-language",
  "user-agent", "cf-access-jwt-assertion", "cf-access-client-id", "cf-access-client-secret"]);

function reply(object, status = 200) {
  return new Response(JSON.stringify(object), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });
}

function sameText(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export default {
  async fetch(request, env) {
    const key = env.RELAY_KEY || "";
    const url = new URL(request.url);
    if (key.length < 16 || request.method !== "POST" || !sameText(url.pathname, "/" + key)) {
      return new Response("Not found", { status: 404 });
    }
    let call;
    try {
      call = await request.json();
    } catch {
      return reply({ ok: false, error: "bad json" }, 400);
    }
    const method = String(call.method || "").toUpperCase();
    const host = String(call.host || "api.cloudflareclient.com");
    const path = String(call.path || "");
    if (!METHODS.has(method) || !HOSTS.has(host) || !PATH.test(path)) {
      return reply({ ok: false, error: "route not allowed" }, 403);
    }
    const headers = new Headers();
    for (const [name, value] of Object.entries(call.headers || {})) {
      if (HEADERS.has(name.toLowerCase()) && typeof value === "string") headers.set(name, value);
    }
    const body = method === "GET" || method === "DELETE" || call.body == null ? undefined : String(call.body);
    try {
      const answer = await fetch("https://" + host + path, { method, headers, body, redirect: "manual" });
      const text = (await answer.text()).slice(0, 512 * 1024);
      const retry = parseInt(answer.headers.get("retry-after") || "", 10);
      return reply({ ok: true, status: answer.status, body: text, ...(retry > 0 ? { retry_after: retry } : {}) });
    } catch (error) {
      return reply({ ok: false, error: "upstream fetch failed" }, 502);
    }
  },
};
