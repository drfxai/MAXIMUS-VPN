// node tools/telegram-bot/test.mjs
import assert from "node:assert/strict";
import worker, { signedSubscription, SIGNED_TTL_MS, handle, collectConfigs, extractLinks, gitHubMirrors, describe, pickNumbers, redact, unseal, keyStatus, normalizeModel, verifyInitData, nextBuild, scrub } from "./worker.js";
import { createHmac, generateKeyPairSync, verify as verifySignature, createHash } from "node:crypto";

const MODELS = { models: [
  { name: "models/gemini-3.8-flash", displayName: "Gemini 3.8 Flash", supportedGenerationMethods: ["generateContent"] },
  { name: "models/gemini-3.7-flash", displayName: "Gemini 3.7 Flash", supportedGenerationMethods: ["generateContent"] },
  { name: "models/text-embedding-9", displayName: "Embedding", supportedGenerationMethods: ["embedContent"] },
] };

/** Presses the Yes (or No) button of the last confirmation the bot sent. */
async function press(env, sent, answer = "ok", from = 777) {
  const ask = [...sent].reverse().find((m) => m.reply_markup?.inline_keyboard?.[0]?.[0]?.callback_data?.startsWith("ok:"));
  const data = ask.reply_markup.inline_keyboard[0][answer === "ok" ? 0 : 1].callback_data;
  await worker.fetch(new Request("https://bot.example/webhook", {
    method: "POST",
    headers: { "X-Telegram-Bot-Api-Secret-Token": "s3cret" },
    body: JSON.stringify({ callback_query: { id: "q", from: { id: from }, data, message: { message_id: 5, chat: { id: from } } } }),
  }), env);
}

const links = ["vless://u@203.0.113.7:443?security=tls#a", "trojan://p@203.0.113.8:443#b", "tuic://x@203.0.113.9:443#c"];
const raw = "https://raw.githubusercontent.com/someone/free-subs/main/mix.txt";

function fakeEnv(routes) {
  const sent = [];
  const env = {
    BOT_TOKEN: "T",
    WEBHOOK_SECRET: "s3cret",
    SUB_URLS: raw,
    fetchImpl: async (url, init) => {
      if (url.startsWith("https://api.telegram.org/")) {
        sent.push(JSON.parse(init.body));
        return new Response(JSON.stringify({ ok: true }));
      }
      const r = routes(url);
      if (r instanceof Error) throw r;
      return new Response(r.body, { status: r.status ?? 200 });
    },
  };
  return { env, sent };
}

// Base64 subscriptions decode; TUIC is dropped (the app refuses it).
assert.deepEqual(extractLinks(btoa(links.join("\n"))), links.slice(0, 2));
assert.deepEqual(extractLinks("<html><body>blocked</body></html>"), []);

// The bot's mirrors match the app's.
assert.ok(gitHubMirrors(raw).includes("https://cdn.jsdelivr.net/gh/someone/free-subs@main/mix.txt"));
assert.deepEqual(gitHubMirrors("https://panel.example.org/sub/x"), []);

// raw.githubusercontent.com blocked from the Worker too: the bot falls back to a CDN copy.
{
  const { env } = fakeEnv((url) => (url.startsWith("https://raw.") ? new Error("reset") : { body: links.join("\n") }));
  assert.deepEqual(await collectConfigs(env), links.slice(0, 2));
}

// /configs sends an intro and the links in a <pre> block.
{
  const { env, sent } = fakeEnv(() => ({ body: links.join("\n") }));
  await handle(1, "/configs", env, 0);
  assert.equal(sent.length, 2);
  assert.match(sent[1].text, /^<pre>vless:\/\/u@203\.0\.113\.7/);
  assert.equal(sent[1].parse_mode, "HTML");
}

// /sub lists the address and its mirrors.
{
  const { env, sent } = fakeEnv(() => ({ body: "" }));
  await handle(2, "/sub", env, 0);
  assert.ok(sent[1].text.includes("fastly.jsdelivr.net"));
}

// Rate limit per chat.
{
  const { env, sent } = fakeEnv(() => ({ body: links.join("\n") }));
  for (let i = 0; i < 7; i++) await handle(3, "/app", env, 1000 + i);
  assert.match(sent.at(-1).text, /Too many requests/);
}

// Webhook rejects calls without Telegram's secret header.
{
  const { env } = fakeEnv(() => ({ body: "" }));
  const bad = await worker.fetch(new Request("https://bot.example/webhook", { method: "POST", body: "{}" }), env);
  assert.equal(bad.status, 403);
  const good = await worker.fetch(new Request("https://bot.example/webhook", {
    method: "POST",
    headers: { "X-Telegram-Bot-Api-Secret-Token": "s3cret" },
    body: JSON.stringify({ message: { chat: { id: 9 }, text: "/help" } }),
  }), env);
  assert.equal(good.status, 200);
}

// Admin commands: only ADMIN_ID, stored in KV, used by /sub and /configs.
{
  const { env, sent } = fakeEnv(() => ({ body: "" }));
  const kv = new Map();
  env.STORE = { get: async (k) => kv.get(k) ?? null, put: async (k, v, _opts) => void kv.set(k, v), delete: async (k) => void kv.delete(k) };
  env.ADMIN_ID = "777";
  env.SUB_URLS = "";
  await handle(5, "/addsub https://evil.example/sub", env, 0);
  assert.equal(kv.size, 0);
  await handle(777, "/addsub https://a.example.org/sub https://b.example.net/sub", env, 0);
  await handle(777, "/delsub 2", env, 0);
  assert.match(kv.get("subs"), /b\.example/);
  await press(env, sent);
  await handle(777, "/addsub https://c.example.net/sub", env, 0);
  await handle(777, "/editsub 2 https://d.example.net/sub", env, 0);
  assert.equal(kv.get("subs"), "https://a.example.org/sub\nhttps://d.example.net/sub");
  await handle(777, "/delsub https://d.example.net/sub", env, 0);
  await press(env, sent);
  await handle(777, "/addconfig " + links[0], env, 0);
  assert.equal(kv.get("subs"), "https://a.example.org/sub");
  sent.length = 0;
  await handle(6, "/configs", env, 0);
  assert.match(sent[1].text, /vless:\/\/u@203/);
}

// The app's subscription endpoint serves everything as Base64.
{
  const { env } = fakeEnv(() => ({ body: links.join("\n") }));
  const res = await worker.fetch(new Request("https://bot.example/sub"), env);
  assert.equal(res.status, 200);
  assert.deepEqual(atob(await res.text()).split("\n"), links.slice(0, 2));
}

// Free and VIP configs are managed separately; VIP is served only at /vip.
{
  const { env, sent } = fakeEnv(() => ({ body: "" }));
  const kv = new Map();
  env.STORE = { get: async (k) => kv.get(k) ?? null, put: async (k, v, _opts) => void kv.set(k, v), delete: async (k) => void kv.delete(k) };
  env.ADMIN_ID = "777";
  env.SUB_URLS = "";
  const vip = ["vless://v1@198.51.100.1:443?security=reality#VIP%20DE", "trojan://v2@198.51.100.2:443#VIP2", "ss://v3@198.51.100.3:8388#VIP3"];
  await handle(5, "/addvip " + vip[0], env, 0);
  assert.equal(kv.get("vip_configs"), undefined);
  await handle(777, "/addvip " + vip.join("\n"), env, 0);
  await handle(777, "/addconfig " + links[1], env, 0);
  await handle(777, "/addfree " + links[0], env, 0);
  assert.equal(kv.get("configs"), [links[1], links[0]].join("\n"));
  assert.equal(kv.get("vip_configs"), vip.join("\n"));
  sent.length = 0;
  await handle(777, "/listvip", env, 0);
  assert.match(sent[1].text, /1\. VLESS · VIP DE · 198\.51\.100\.1:443/);
  await handle(777, "/delvip 1-2", env, 0);
  await press(env, sent, "ok", 5);
  assert.equal(kv.get("vip_configs"), vip.join("\n"));
  await press(env, sent);
  assert.equal(kv.get("vip_configs"), vip[2]);
  const vipRes = await worker.fetch(new Request("https://bot.example/vip"), env);
  assert.equal(vipRes.headers.get("profile-title"), "MAXIMUS VIP");
  assert.deepEqual(atob(await vipRes.text()).split("\n"), [vip[2]]);
  const freeRes = await worker.fetch(new Request("https://bot.example/sub"), env);
  assert.deepEqual(atob(await freeRes.text()).split("\n"), [links[1], links[0]]);
  await handle(777, "/clearfree", env, 0);
  await press(env, sent, "no");
  assert.notEqual(kv.get("configs"), "");
  await handle(777, "/clearfree", env, 0);
  await press(env, sent);
  assert.equal(kv.get("configs"), "");
  assert.equal(kv.get("vip_configs"), vip[2]);
  sent.length = 0;
  await handle(777, "/list", env, 0);
  assert.match(sent[0].text, /Free configs: 0[\s\S]*VIP configs: 1/);
}

assert.deepEqual(pickNumbers(["2", "5-7", "x", "9"], 8), [2, 5, 6, 7]);
assert.equal(describe("vmess://" + btoa(JSON.stringify({ ps: "Fast", add: "203.0.113.1", port: 443 })), 3), "3. VMESS · Fast · 203.0.113.1:443");

// Setup refuses a webhook secret Telegram would reject; /status never shows secrets.
{
  const { env } = fakeEnv(() => ({ body: "" }));
  env.WEBHOOK_SECRET = "abc,def;ghi";
  const bad = await worker.fetch(new Request("https://bot.example/setup?secret=" + encodeURIComponent(env.WEBHOOK_SECRET)), env);
  assert.equal(bad.status, 400);
  assert.match(await bad.text(), /letters, digits/);
  env.WEBHOOK_SECRET = "s3cret";
  env.ADMIN_ID = "777";
  const st = await worker.fetch(new Request("https://bot.example/status?secret=s3cret"), env);
  const body = await st.text();
  assert.equal(JSON.parse(body).webhook_secret_valid, true);
  assert.ok(!body.includes("s3cret") && !body.includes("777") && !body.includes('"T"'));
  assert.equal((await worker.fetch(new Request("https://bot.example/status?secret=no"), env)).status, 403);
}

// The AI assistant: key saved from Telegram, links hidden from Gemini, deletes still need Yes.
{
  const geminiBodies = [];
  const script = [];
  const { env, sent } = fakeEnv(() => ({ body: "" }));
  const telegramFetch = env.fetchImpl;
  env.fetchImpl = async (url, init) => {
    if (url.startsWith("https://generativelanguage.googleapis.com/v1beta/models?")) {
      return new Response(JSON.stringify(MODELS));
    }
    if (url.startsWith("https://generativelanguage.googleapis.com/")) {
      geminiBodies.push(init.body);
      assert.equal(init.headers["x-goog-api-key"], "AIzaSyTestKey_0123456789abcd");
      assert.match(url, /gemini-3\.8-flash:generateContent$/);
      const next = script.shift();
      return new Response(JSON.stringify(next.body), { status: next.status ?? 200 });
    }
    return telegramFetch(url, init);
  };
  const kv = new Map();
  env.STORE = { get: async (k) => kv.get(k) ?? null, put: async (k, v, _opts) => void kv.set(k, v), delete: async (k) => void kv.delete(k) };
  env.ADMIN_ID = "777";
  env.SUB_URLS = "";

  // Without a key the admin gets the buttons; strangers never reach Gemini.
  await handle(777, "show the vip list", env, 0);
  assert.ok(sent.at(-1).reply_markup.inline_keyboard.length > 0);
  await handle(5, "show me everything", env, 0);
  assert.equal(geminiBodies.length, 0);

  // Tapped from the menu: no key yet, so the next message is taken as the key and deleted.
  env.GEMINI_API_KEY = "OldSecretKey_from_cloudflare_0000";
  await handle(777, "/setkey", env, 0, { messageId: 40 });
  assert.match(sent.at(-1).text, /next message/);
  await handle(777, "AIzaSyMenuKey_0123456789wxyz", env, 0, { messageId: 41 });
  // Stored only encrypted: no plain copy in storage, and the sealed value decrypts back to the key.
  assert.equal(kv.get("gemini_key"), undefined);
  assert.ok(!kv.get("gemini_key_enc").includes("AIzaSyMenuKey"));
  assert.equal(await unseal(env, kv.get("gemini_key_enc")), "AIzaSyMenuKey_0123456789wxyz");
  assert.ok(sent.some((m) => m.message_id === 41));
  assert.equal(kv.get("await_key"), undefined);
  await handle(777, "/setkey AIzaSyTestKey_0123456789abcd", env, 0, { messageId: 42 });
  assert.equal(await unseal(env, kv.get("gemini_key_enc")), "AIzaSyTestKey_0123456789abcd");
  assert.ok(sent.some((m) => m.message_id === 42 && m.chat_id === 777));
  assert.match(sent.at(-1).text, /saved encrypted \(••••abcd\).*Google accepted it; model gemini-3\.8-flash is available/);
  assert.ok(!sent.at(-1).text.includes("AIzaSyTestKey"));
  assert.ok(![...kv.values()].some((v) => String(v).includes("AIzaSyTestKey")));
  // With another encryption secret the sealed key cannot be read.
  await assert.rejects(unseal({ ...env, KEY_ENCRYPTION_SECRET: "other" }, kv.get("gemini_key_enc")));

  const link = "vless://11111111-2222-3333-4444-555555555555@198.51.100.9:443?security=reality#Berlin";
  script.push(
    { body: { candidates: [{ content: { role: "model", parts: [{ functionCall: { name: "add_configs", args: { tier: "vip", refs: ["LINK1"] } }, thoughtSignature: "sig" }] } }] } },
    { body: { candidates: [{ content: { role: "model", parts: [{ text: "یک کانفیگ به VIP اضافه شد." }] } }] } },
  );
  await handle(777, "این رو به VIP اضافه کن " + link, env, 0);
  assert.equal(kv.get("vip_configs"), link);
  assert.equal(sent.at(-1).text, "یک کانفیگ به VIP اضافه شد.");
  assert.ok(geminiBodies.every((b) => !b.includes("vless://") && !b.includes("198.51.100.9")));
  assert.ok(geminiBodies[1].includes('"thoughtSignature":"sig"'));

  // Listing shows names, not addresses; deleting asks first.
  script.push(
    { body: { candidates: [{ content: { role: "model", parts: [{ functionCall: { name: "list_configs", args: { tier: "vip" } } }] } }] } },
    { body: { candidates: [{ content: { role: "model", parts: [{ functionCall: { name: "delete_configs", args: { tier: "vip", numbers: [1] } } }] } }] } },
    { body: { candidates: [{ content: { role: "model", parts: [{ text: "Press Yes to delete Berlin." }] } }] } },
  );
  await handle(777, "delete the Berlin VIP server", env, 0);
  assert.ok(geminiBodies[3].includes("Berlin") && !geminiBodies[3].includes("198.51.100.9"));
  assert.equal(kv.get("vip_configs"), link);
  await press(env, sent);
  assert.equal(kv.get("vip_configs"), "");
  assert.equal(JSON.parse(kv.get("ai_history")).length, 4);

  script.push({ status: 403, body: { error: { message: "API key not valid" } } });
  await handle(777, "hello", env, 0);
  assert.match(sent.at(-1).text, /rejected the key/);
}

assert.deepEqual(redact("add vless://a@b:1#x and https://s.example/sub").text, "add [LINK1] and [URL1]");


// ---------------------------------------------------------------- Phase 10: panel, key, model, free list

/** Init data the way Telegram signs it for a Mini App. */
function initData(botToken, user, authDate) {
  const fields = { auth_date: String(authDate), query_id: "AAH", user: JSON.stringify(user) };
  const check = Object.keys(fields).sort().map((k) => `${k}=${fields[k]}`).join("\n");
  const secret = createHmac("sha256", "WebAppData").update(botToken).digest();
  const hash = createHmac("sha256", secret).update(check).digest("hex");
  return new URLSearchParams({ ...fields, hash }).toString();
}

const manifest = {
  version: 1, created: "2026-10-06T08:17:00Z", count: 3,
  report: {
    rejected: { "not answering": 40, "certificate checks disabled": 12 },
    sources: { "barry-far": "2 carried traffic", "dead-source": "not fetched: URLError" },
    source_meta: [
      { source_name: "barry-far", fetch_status: "ok", candidate_count: 30, valid_count: 2, source_url: "https://raw.githubusercontent.com/x/y/main/z" },
      { source_name: "dead-source", fetch_status: "failed: URLError", candidate_count: 0, valid_count: 0 },
    ],
    diversity: { kinds: { reality: 2, ws: 1 }, cdn: { cdn: 1, direct: 2 }, failure_domains: 3, largest_failure_domain: 1 },
  },
  evidence: { global: "tested from a GitHub Actions runner", iran: "UNKNOWN_IRAN_STATUS" },
};
const configsJson = { configs: [1, 2, 3].map((i) => ({ name: `DE ${i}`, iran_status: "UNKNOWN_IRAN_STATUS", lifecycle: "GLOBAL_VERIFIED" })) };

function panelEnv() {
  const kv = new Map();
  const calls = [];
  const sent = [];
  const env = {
    BOT_TOKEN: "123456:TEST_TOKEN_for_the_panel_checks_0000000",
    WEBHOOK_SECRET: "s3cret",
    ADMIN_ID: "777",
    STORE: { get: async (k) => kv.get(k) ?? null, put: async (k, v) => void kv.set(k, v), delete: async (k) => void kv.delete(k) },
    fetchImpl: async (url, init = {}) => {
      calls.push({ url, init });
      if (url.startsWith("https://api.telegram.org/")) {
        sent.push(JSON.parse(init.body));
        return new Response(JSON.stringify({ ok: true, result: {} }));
      }
      // raw.githubusercontent.com is "blocked": the mirrors answer.
      if (url.startsWith("https://raw.githubusercontent.com/")) throw new Error("reset");
      if (url.includes("free-configs/manifest.json")) return new Response(JSON.stringify(manifest));
      if (url.includes("free-configs/configs.json")) return new Response(JSON.stringify(configsJson));
      if (url.includes("free-configs/manifest.sig")) return new Response("c2lnbmF0dXJl");
      if (url.startsWith("https://generativelanguage.googleapis.com/v1beta/models?")) {
        return init.headers["x-goog-api-key"].endsWith("bad0")
          ? new Response(JSON.stringify({ error: { message: "API key not valid" } }), { status: 400 })
          : new Response(JSON.stringify(MODELS));
      }
      if (url.startsWith("https://api.github.com/")) return new Response(null, { status: 204 });
      return new Response("", { status: 404 });
    },
  };
  return { env, kv, calls, sent };
}

const NOW = Date.UTC(2026, 9, 6, 13, 0, 0);
async function call(env, path, { body, data, now = NOW } = {}) {
  const req = new Request(`https://bot.example/api/${path}`, {
    method: body ? "POST" : "GET",
    headers: { authorization: `tma ${data ?? initData(env.BOT_TOKEN, { id: 777, first_name: "A" }, Math.floor(now / 1000))}`, "content-type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  const { api } = await import("./worker.js");
  const res = await api(req, env, now);
  return { status: res.status, body: await res.json() };
}

// Telegram init data: valid admin passes; another user, a forged hash and old data are refused.
{
  const { env } = panelEnv();
  const t = Math.floor(NOW / 1000);
  assert.equal((await verifyInitData(env, initData(env.BOT_TOKEN, { id: 777 }, t), NOW)).ok, true);
  assert.equal((await verifyInitData(env, initData(env.BOT_TOKEN, { id: 778 }, t), NOW)).status, 403);
  assert.equal((await verifyInitData(env, initData("999:other_bot_token_xxxxxxxxxxxxxxxxxxxxxx", { id: 777 }, t), NOW)).status, 401);
  const forged = initData(env.BOT_TOKEN, { id: 778 }, t).replace(encodeURIComponent('"id":778'), encodeURIComponent('"id":777'));
  assert.equal((await verifyInitData(env, forged, NOW)).status, 401);
  assert.equal((await verifyInitData(env, initData(env.BOT_TOKEN, { id: 777 }, t - 2 * 3600), NOW)).status, 401);
  assert.equal((await verifyInitData(env, "", NOW)).status, 401);
  assert.equal((await call(env, "overview", { data: "" })).status, 401);
  assert.equal((await call(env, "overview", { data: initData(env.BOT_TOKEN, { id: 778 }, t) })).status, 403);
}

// The panel page holds no secret and locks down what it may load.
{
  const { env } = panelEnv();
  env.GEMINI_API_KEY = "AIzaSyCloudSecret_0000wxyz";
  const res = await worker.fetch(new Request("https://bot.example/admin"), env);
  const html = await res.text();
  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-security-policy"), /connect-src 'self'/);
  assert.ok(html.includes("telegram-web-app.js") && html.includes("Iran Health") && html.includes("Gemini AI"));
  for (const secret of [env.BOT_TOKEN, env.GEMINI_API_KEY, env.WEBHOOK_SECRET, "777"]) assert.ok(!html.includes(secret), secret);
}

// Gemini key from the panel: saved encrypted, validated, shown only as a mask; model checked against Google's list.
{
  const { env, kv } = panelEnv();
  let r = await call(env, "gemini");
  assert.equal(r.body.configured, false);
  r = await call(env, "gemini/key", { body: { key: "short" } });
  assert.equal(r.status, 400);
  // A key pasted on a phone with direction marks, a zero-width space and a line break is accepted
  // and stored without them; a too-short one says why without echoing it.
  r = await call(env, "gemini/key", { body: { key: "\u200FAIzaSyPhoneKey_\u200B0123456789PHNE\n" } });
  assert.equal(r.status, 200);
  assert.equal(await unseal(env, kv.get("gemini_key_enc")), "AIzaSyPhoneKey_0123456789PHNE");
  r = await call(env, "gemini/key", { body: { key: "AIzaShort" } });
  assert.match(r.body.error, /only 9 characters/);
  assert.ok(!r.body.error.includes("AIzaShort"));
  assert.match((await call(env, "gemini/key", { body: { key: "  " } })).body.error, /empty/);
  r = await call(env, "gemini/key", { body: { key: "AIzaSyPanelKey_0123456789ABCD" } });
  assert.equal(r.status, 200);
  assert.equal(r.body.valid, true);
  assert.equal(r.body.status.mask, "••••ABCD");
  assert.equal(r.body.status.source, "saved");
  assert.equal(r.body.status.modelChecked, "available");
  const all = JSON.stringify((await call(env, "gemini")).body) + JSON.stringify((await call(env, "overview")).body);
  assert.ok(!all.includes("AIzaSyPanelKey"));
  assert.ok(![...kv.values()].some((v) => String(v).includes("AIzaSyPanelKey")));
  assert.deepEqual((await call(env, "gemini")).body.models.map((m) => m.id), ["gemini-3.8-flash", "gemini-3.7-flash"]);

  // An unknown model is refused and the model stays; no substitution.
  r = await call(env, "gemini/model", { body: { model: "gemini-9-ultra" } });
  assert.equal(r.status, 400);
  assert.match(r.body.error, /does not offer gemini-9-ultra.*not changed/);
  assert.equal((await keyStatus(env)).model, "gemini-3.8-flash");
  r = await call(env, "gemini/model", { body: { model: "Gemini 3.7 Flash" } });
  assert.equal(r.body.model, "gemini-3.7-flash");
  assert.equal(kv.get("gemini_model"), "gemini-3.7-flash");
  assert.equal(normalizeModel("models/gemini-3.8-flash"), "gemini-3.8-flash");

  // A key Google rejects is stored but marked invalid with the reason.
  r = await call(env, "gemini/key", { body: { key: "AIzaSyRejectedKey_000000bad0" } });
  assert.equal(r.body.valid, false);
  assert.match(r.body.message, /rejected the key/);
  assert.equal((await keyStatus(env)).valid, false);

  // Scenario G: a new Worker instance (restart, another Cloudflare location) with the same storage and
  // secrets reads the key back; nothing is kept only in memory.
  const restarted = { ...panelEnv().env, STORE: env.STORE };
  assert.equal((await keyStatus(restarted)).mask, "••••bad0");
  assert.equal((await keyStatus(restarted)).unreadable, false);

  // Delete needs confirmation.
  assert.equal((await call(env, "gemini/delete", { body: {} })).status, 400);
  r = await call(env, "gemini/delete", { body: { confirm: true } });
  assert.equal(r.body.status.configured, false);
  assert.equal(kv.get("gemini_key_enc"), undefined);
}

// A plain-text key left by an older version is encrypted on first use and the plain copy removed.
{
  const { env, kv } = panelEnv();
  kv.set("gemini_key", "AIzaSyLegacyPlainKey_00000000LGCY");
  const s = await keyStatus(env);
  assert.equal(s.mask, "••••LGCY");
  assert.equal(kv.get("gemini_key"), undefined);
  assert.equal(await unseal(env, kv.get("gemini_key_enc")), "AIzaSyLegacyPlainKey_00000000LGCY");
  // A changed BOT_TOKEN without KEY_ENCRYPTION_SECRET makes the saved key unreadable, and says so.
  assert.equal((await keyStatus({ ...env, BOT_TOKEN: "999:changed" })).unreadable, true);
}

// Configs in the panel: no addresses; delete by id with confirmation; the link only on an explicit request.
{
  const { env, kv } = panelEnv();
  const a = "vless://11111111-2222-3333-4444-555555555555@198.51.100.9:443?type=ws&security=tls#Frankfurt";
  const b = "trojan://secretpass@198.51.100.10:443?security=tls#Amsterdam";
  let r = await call(env, "configs/add", { body: { tier: "vip", text: `${a}\n${b}\nnot a link` } });
  assert.deepEqual(r.body, { added: 2, total: 2 });
  r = await call(env, "configs?tier=vip");
  const text = JSON.stringify(r.body);
  assert.ok(!text.includes("198.51.100") && !text.includes("secretpass") && !text.includes("11111111"));
  assert.deepEqual(r.body.items.map((i) => [i.name, i.protocol, i.transport, i.security]), [["Frankfurt", "VLESS", "ws", "tls"], ["Amsterdam", "TROJAN", "", "tls"]]);
  const id = r.body.items[0].id;
  assert.equal((await call(env, "configs/delete", { body: { tier: "vip", ids: [id] } })).status, 400);
  assert.equal(kv.get("vip_configs"), `${a}\n${b}`);
  assert.equal((await call(env, "configs/link", { body: { tier: "vip", id: r.body.items[1].id } })).body.link, b);
  r = await call(env, "configs/delete", { body: { tier: "vip", ids: [id], confirm: true } });
  assert.deepEqual(r.body, { deleted: 1, total: 1 });
  assert.equal(kv.get("vip_configs"), b);
  assert.equal((await call(env, "configs/clear", { body: { tier: "vip" } })).status, 400);
  await call(env, "configs/clear", { body: { tier: "vip", confirm: true } });
  assert.equal(kv.get("vip_configs"), "");
  const log = JSON.parse(kv.get("activity"));
  assert.ok(log.some((e) => e.text.includes("VIP: 1 deleted")));
}

// Free list status through the CDN mirrors; Iran stays unknown; refresh needs confirmation and a token.
{
  const { env, calls, sent, kv } = panelEnv();
  let r = await call(env, "validation");
  assert.equal(r.body.count, 3);
  assert.equal(r.body.signed, true);
  assert.equal(r.body.rejectedTotal, 52);
  assert.equal(r.body.candidates, 30);
  assert.equal(r.body.ageHours, 4.7);
  assert.deepEqual(r.body.iran, { UNKNOWN_IRAN_STATUS: 3 });
  assert.equal(r.body.nextBuild, "2026-10-06T18:17:00.000Z");
  assert.equal(new Date(nextBuild(Date.UTC(2026, 9, 6, 12, 10))).toISOString(), "2026-10-06T12:17:00.000Z");
  assert.ok(calls.some((c) => c.url.startsWith("https://cdn.jsdelivr.net/gh/drfxai/MAXIMUS-VPN@free-configs/manifest.json")));

  assert.equal((await call(env, "refresh", { body: {} })).status, 400);
  r = await call(env, "refresh", { body: { confirm: true } });
  assert.match(r.body.message, /GH_TOKEN/);
  env.GH_TOKEN = "ghp_test";
  r = await call(env, "refresh", { body: { confirm: true } });
  assert.equal(r.body.ok, true);
  const dispatch = calls.find((c) => c.url.includes("/actions/workflows/free-configs.yml/dispatches"));
  assert.equal(JSON.parse(dispatch.init.body).ref, "main");

  // The same from Telegram: /statusfree, /iranstatus, /sources, and /refreshfree only after Yes.
  await handle(777, "/statusfree", env, 0);
  assert.match(sent.at(-1).text, /Free list: 3 configs, built 2026-10-06 08:17 UTC .* signed\.\n30 candidates from 2 sources\.\nRejected 52: not answering 40, certificate checks disabled 12\./);
  await handle(777, "/iranstatus", env, 0);
  assert.match(sent.at(-1).text, /3 of 3 unknown\. The list is built on GitHub's servers outside Iran/);
  await handle(777, "/sources", env, 0);
  assert.match(sent.at(-1).text, /barry-far: 2 carried traffic · 30 candidates, 2 kept\ndead-source: not fetched/);
  const before = calls.filter((c) => c.url.includes("dispatches")).length;
  await handle(777, "/refreshfree", env, 0);
  assert.match(sent.at(-1).text, /Rebuild the free list now\?/);
  assert.equal(sent.at(-1).reply_markup.inline_keyboard[0][0].text, "Yes, rebuild");
  assert.equal(calls.filter((c) => c.url.includes("dispatches")).length, before);
  await press(env, sent);
  assert.equal(calls.filter((c) => c.url.includes("dispatches")).length, before + 1);
  assert.match(sent.at(-1).text, /Build started/);

  // Strangers cannot use the new commands.
  await handle(5, "/statusfree", env, 0);
  assert.match(sent.at(-1).text, /^MAXIMUS VPN/);

  // /health and /diagnostics; problems are logged without secrets.
  await handle(777, "/health", env, 0);
  assert.match(sent.at(-1).text, /Webhook: .*\nStorage: bound · Gemini: not configured\nFree list: 3 configs/);
  const { logError } = await import("./worker.js");
  await logError(env, "test", new Error(`failed for vless://u@198.51.100.1:443 with ${env.BOT_TOKEN} and AIzaSyLeaked_0123456789abcdefghij`));
  await handle(777, "/diagnostics", env, 0);
  const diag = sent.at(-1).text;
  assert.match(diag, /test: failed for \[LINK1\] with \[BOT_TOKEN\] and \[API_KEY\]/);
  assert.ok(!kv.get("errors").includes("198.51.100.1") && !kv.get("errors").includes("TEST_TOKEN"));
  assert.equal(scrub("x".repeat(40)), "[TOKEN]");
}

// /setup also gives the admin the panel button and remembers the Worker's address for /panel.
{
  const { env, sent, kv } = panelEnv();
  await worker.fetch(new Request("https://bot.example/setup?secret=s3cret"), env);
  const menu = sent.find((m) => m.menu_button);
  assert.equal(menu.menu_button.web_app.url, "https://bot.example/admin");
  assert.equal(menu.chat_id, 777);
  assert.equal(kv.get("origin"), "https://bot.example");
  await handle(777, "/panel", env, 0);
  assert.equal(sent.at(-1).reply_markup.inline_keyboard[0][0].web_app.url, "https://bot.example/admin");
}

// The signed subscription: one envelope, verifiable with SHA256withECDSA (DER) as the app does it.
{
  const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const pem = privateKey.export({ type: "pkcs8", format: "pem" });
  const env = { ...fakeEnv(() => ({ body: links.join("\n") })).env, OFFICIAL_SIGNING_KEY: pem, VIP_CONFIGS: links[0] };
  const res = await signedSubscription(env, "free", 1_700_000_000_000);
  assert.equal(res.status, 200);
  const envelope = JSON.parse(await res.text());
  const manifest = JSON.parse(envelope.manifest);
  assert.equal(manifest.id, "official-sub");
  assert.equal(manifest.sequence, 1_700_000_000_000);
  assert.equal(manifest.expires, 1_700_000_000_000 + SIGNED_TTL_MS);
  assert.equal(manifest.sha256, createHash("sha256").update(envelope.payload).digest("hex"));
  assert.ok(verifySignature("sha256", Buffer.from(envelope.manifest), { key: publicKey, dsaEncoding: "der" }, Buffer.from(envelope.signature, "base64")));
  // A changed manifest no longer verifies.
  assert.ok(!verifySignature("sha256", Buffer.from(envelope.manifest.replace("official-sub", "official-vip")), { key: publicKey, dsaEncoding: "der" }, Buffer.from(envelope.signature, "base64")));
  const vip = JSON.parse(await (await signedSubscription(env, "vip")).text());
  assert.equal(JSON.parse(vip.manifest).id, "official-vip");
  // Routed, and refused when the Worker has no key.
  assert.equal((await worker.fetch(new Request("https://bot.example/sub.signed"), env)).status, 200);
  assert.equal((await worker.fetch(new Request("https://bot.example/sub.signed"), { ...env, OFFICIAL_SIGNING_KEY: "" })).status, 503);
}

console.log("telegram bot: all checks passed");
