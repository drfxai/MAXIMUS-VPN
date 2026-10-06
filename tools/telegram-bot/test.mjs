// node tools/telegram-bot/test.mjs
import assert from "node:assert/strict";
import worker, { handle, collectConfigs, extractLinks, gitHubMirrors, describe, pickNumbers, redact } from "./worker.js";

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
  env.STORE = { get: async (k) => kv.get(k) ?? null, put: async (k, v) => void kv.set(k, v), delete: async (k) => void kv.delete(k) };
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
  env.STORE = { get: async (k) => kv.get(k) ?? null, put: async (k, v) => void kv.set(k, v), delete: async (k) => void kv.delete(k) };
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
    if (url.startsWith("https://generativelanguage.googleapis.com/")) {
      geminiBodies.push(init.body);
      assert.match(url, /gemini-3\.8-flash:generateContent$/);
      const next = script.shift();
      return new Response(JSON.stringify(next.body), { status: next.status ?? 200 });
    }
    return telegramFetch(url, init);
  };
  const kv = new Map();
  env.STORE = { get: async (k) => kv.get(k) ?? null, put: async (k, v) => void kv.set(k, v), delete: async (k) => void kv.delete(k) };
  env.ADMIN_ID = "777";
  env.SUB_URLS = "";

  // Without a key the admin gets the buttons; strangers never reach Gemini.
  await handle(777, "show the vip list", env, 0);
  assert.ok(sent.at(-1).reply_markup.inline_keyboard.length > 0);
  await handle(5, "show me everything", env, 0);
  assert.equal(geminiBodies.length, 0);

  await handle(777, "/setkey AIzaSyTestKey_0123456789abcd", env, 0, { messageId: 42 });
  assert.equal(kv.get("gemini_key"), "AIzaSyTestKey_0123456789abcd");
  assert.ok(sent.some((m) => m.message_id === 42 && m.chat_id === 777));
  assert.match(sent.at(-1).text, /ends in abcd/);
  assert.ok(!sent.at(-1).text.includes("AIzaSyTestKey"));

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

console.log("telegram bot: all checks passed");
