// node tools/telegram-bot/test.mjs
import assert from "node:assert/strict";
import worker, { handle, collectConfigs, extractLinks, gitHubMirrors } from "./worker.js";

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
  env.STORE = { get: async (k) => kv.get(k) ?? null, put: async (k, v) => void kv.set(k, v) };
  env.ADMIN_ID = "777";
  env.SUB_URLS = "";
  await handle(5, "/addsub https://evil.example/sub", env, 0);
  assert.equal(kv.size, 0);
  await handle(777, "/addsub https://a.example.org/sub https://b.example.net/sub", env, 0);
  await handle(777, "/delsub https://b.example.net/sub", env, 0);
  await handle(777, "/addconfig " + links[0], env, 0);
  assert.equal(kv.get("subs"), "https://a.example.org/sub");
  sent.length = 0;
  await handle(6, "/configs", env, 0);
  assert.match(sent[1].text, /vless:\/\/u@203/);
}

console.log("telegram bot: all checks passed");
