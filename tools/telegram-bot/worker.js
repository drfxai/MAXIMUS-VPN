// MAXIMUS VPN distribution bot: a Cloudflare Worker that hands out subscription addresses and
// working configurations over Telegram, for users who cannot reach any web address yet (Telegram
// often stays reachable, or is reached through its own built-in proxy).
//
// Settings (Worker variables; BOT_TOKEN and WEBHOOK_SECRET as encrypted secrets):
//   BOT_TOKEN       token from @BotFather
//   WEBHOOK_SECRET  any long random string; Telegram sends it back on every update
//   SUB_URLS        subscription addresses, one per line, best first
//   CONFIGS         optional extra links (vless://, trojan://, hysteria2://, ...), one per line
//   APP_URL         optional download page for the app
//   MAX_CONFIGS     optional, default 20
//   ADMIN_ID        your numeric Telegram ID; lets you change the lists from Telegram:
//                   /addsub, /delsub, /addconfig, /clearconfigs, /list
// Binding (optional, needed for the admin commands): a KV namespace named STORE.
//
// Routes: POST /webhook (Telegram), GET /setup?secret=WEBHOOK_SECRET (registers the webhook).

// Only what the app runs (TUIC is refused at import).
const LINK = /^(vless|vmess|trojan|ss|hysteria2|hy2|wireguard):\/\/\S+$/i;
const TELEGRAM_LIMIT = 3800;
const RATE_WINDOW_MS = 60_000;
const RATE_MAX = 6;
const recent = new Map();

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/setup") return setup(url, env);
    if (request.method === "POST" && url.pathname === "/webhook") {
      if (!env.WEBHOOK_SECRET || request.headers.get("X-Telegram-Bot-Api-Secret-Token") !== env.WEBHOOK_SECRET) {
        return new Response("forbidden", { status: 403 });
      }
      const update = await request.json().catch(() => null);
      const message = update?.message;
      if (message?.chat?.id && typeof message.text === "string") {
        await handle(message.chat.id, message.text.trim(), env);
      }
      return new Response("ok");
    }
    return new Response("not found", { status: 404 });
  },
};

async function setup(url, env) {
  if (!env.WEBHOOK_SECRET || url.searchParams.get("secret") !== env.WEBHOOK_SECRET) {
    return new Response("forbidden", { status: 403 });
  }
  const res = await telegram(env, "setWebhook", {
    url: `${url.origin}/webhook`,
    secret_token: env.WEBHOOK_SECRET,
    allowed_updates: ["message"],
    drop_pending_updates: true,
  });
  await telegram(env, "setMyCommands", {
    commands: [
      { command: "configs", description: "Working configurations / کانفیگ‌ها" },
      { command: "sub", description: "Subscription addresses / لینک اشتراک" },
      { command: "app", description: "Download the app / دانلود برنامه" },
      { command: "help", description: "How to use / راهنما" },
    ],
  });
  return new Response(JSON.stringify(res), { headers: { "content-type": "application/json" } });
}

const ADMIN_COMMANDS = ["/addsub", "/delsub", "/addconfig", "/clearconfigs", "/list"];

export async function handle(chatId, text, env, now = Date.now()) {
  const command = text.split(/[\s@]/)[0].toLowerCase();
  if (ADMIN_COMMANDS.includes(command)) return admin(chatId, command, text, env);
  if (!["/start", "/help", "/sub", "/configs", "/app"].includes(command)) {
    return send(env, chatId, helpText(env));
  }
  if (limited(chatId, now)) {
    return send(env, chatId, "Too many requests, try again in a minute.\nدرخواست زیاد است، یک دقیقه دیگر دوباره امتحان کنید.");
  }
  if (command === "/sub") {
    const subs = await subUrls(env);
    if (subs.length === 0) return send(env, chatId, "No subscription is published yet.\nهنوز اشتراکی منتشر نشده است.");
    const all = [...new Set(subs.flatMap((s) => [s, ...gitHubMirrors(s)]))];
    return sendChunks(env, chatId, "Add one of these in MAXIMUS → Subscriptions (paste them all: the rest become mirrors).\n" +
      "یکی از این‌ها را در بخش اشتراک‌ها اضافه کنید (همه را با هم بچسبانید تا بقیه پشتیبان شوند).", all);
  }
  if (command === "/configs") {
    const configs = await collectConfigs(env);
    if (configs.length === 0) return send(env, chatId, "No configuration could be fetched right now. Try /sub.\nالان کانفیگی در دسترس نیست. /sub را امتحان کنید.");
    return sendChunks(env, chatId, "Copy all, then in MAXIMUS tap + → Paste.\nهمه را کپی کنید و در برنامه + ← چسباندن را بزنید.", configs);
  }
  if (command === "/app") {
    return send(env, chatId, env.APP_URL ? `Download: ${env.APP_URL}` : "The download link is not set yet.");
  }
  return send(env, chatId, helpText(env));
}

function helpText() {
  return [
    "MAXIMUS VPN",
    "/configs: working configurations to paste into the app",
    "/sub: subscription addresses with mirrors",
    "/app: download the app",
    "",
    "/configs: کانفیگ‌های آماده برای چسباندن در برنامه",
    "/sub: لینک‌های اشتراک با آدرس‌های پشتیبان",
    "/app: دانلود برنامه",
  ].join("\n");
}

export async function collectConfigs(env, fetchImpl = env.fetchImpl || fetch) {
  const max = Number(env.MAX_CONFIGS || 20);
  const out = [...(await extraConfigs(env)).filter((l) => LINK.test(l))];
  for (const sub of await subUrls(env)) {
    if (out.length >= max) break;
    for (const source of [sub, ...gitHubMirrors(sub)]) {
      try {
        const res = await fetchImpl(source, { headers: { "User-Agent": "Maximus-VPN-Bot/1.0" }, cf: { cacheTtl: 300 } });
        if (!res.ok) continue;
        const found = extractLinks(await res.text());
        if (found.length === 0) continue;
        out.push(...found);
        break;
      } catch (_) {
        // next source
      }
    }
  }
  return [...new Set(out)].slice(0, max);
}

export function extractLinks(body) {
  let text = body.trim();
  if (!text.includes("://")) {
    try {
      text = new TextDecoder().decode(Uint8Array.from(atob(text.replace(/\s+/g, "")), (c) => c.charCodeAt(0)));
    } catch (_) {
      return [];
    }
  }
  return text.split(/\r?\n/).map((l) => l.trim()).filter((l) => LINK.test(l));
}

/** The same mirrors the app derives (SubscriptionSources.derivedMirrors). */
export function gitHubMirrors(url) {
  let u;
  try { u = new URL(url); } catch (_) { return []; }
  if (u.protocol !== "https:" || u.search) return [];
  const p = u.pathname.split("/").filter(Boolean);
  const file = (owner, repo, rest) => {
    if (rest.length >= 4 && rest[0] === "refs" && (rest[1] === "heads" || rest[1] === "tags")) return [owner, repo, rest[2], rest.slice(3).join("/")];
    if (rest.length >= 2) return [owner, repo, rest[0], rest.slice(1).join("/")];
    return null;
  };
  let f = null;
  if (u.hostname === "raw.githubusercontent.com" && p.length >= 4) f = file(p[0], p[1], p.slice(2));
  else if (u.hostname === "github.com" && p.length >= 5 && (p[2] === "raw" || p[2] === "blob")) f = file(p[0], p[1], p.slice(3));
  else if (u.hostname.endsWith("jsdelivr.net") && p.length >= 4 && p[0] === "gh" && p[2].includes("@")) {
    const [repo, ref] = p[2].split("@");
    if (ref) f = [p[1], repo, ref, p.slice(3).join("/")];
  }
  if (!f) return [];
  const [o, r, ref, path] = f;
  return [
    `https://raw.githubusercontent.com/${o}/${r}/${ref}/${path}`,
    `https://cdn.jsdelivr.net/gh/${o}/${r}@${ref}/${path}`,
    `https://fastly.jsdelivr.net/gh/${o}/${r}@${ref}/${path}`,
    `https://gcore.jsdelivr.net/gh/${o}/${r}@${ref}/${path}`,
    `https://testingcf.jsdelivr.net/gh/${o}/${r}@${ref}/${path}`,
    `https://cdn.statically.io/gh/${o}/${r}/${ref}/${path}`,
    `https://raw.githack.com/${o}/${r}/${ref}/${path}`,
  ].filter((m) => m !== url);
}

async function stored(env, key) {
  if (!env.STORE) return [];
  return lines(await env.STORE.get(key));
}

async function subUrls(env) {
  return [...new Set([...lines(env.SUB_URLS), ...(await stored(env, "subs"))])];
}

async function extraConfigs(env) {
  return [...new Set([...lines(env.CONFIGS), ...(await stored(env, "configs"))])];
}

async function admin(chatId, command, text, env) {
  if (!env.ADMIN_ID || String(chatId) !== String(env.ADMIN_ID).trim()) return send(env, chatId, helpText());
  if (!env.STORE) return send(env, chatId, "Add a KV namespace binding named STORE to the Worker first.");
  const args = text.split(/\s+/).slice(1).filter(Boolean);
  if (command === "/list") {
    const subs = await stored(env, "subs");
    const configs = await stored(env, "configs");
    return send(env, chatId, `Subscriptions (${subs.length}):\n${subs.join("\n") || "none"}\n\nExtra configs: ${configs.length}`);
  }
  if (command === "/addsub" || command === "/delsub") {
    const urls = args.filter((a) => /^https:\/\/\S+$/i.test(a));
    if (urls.length === 0) return send(env, chatId, `Usage: ${command} https://...`);
    const current = await stored(env, "subs");
    const next = command === "/addsub" ? [...new Set([...current, ...urls])] : current.filter((u) => !urls.includes(u));
    await env.STORE.put("subs", next.join("\n"));
    return send(env, chatId, `Saved. ${next.length} subscription(s).`);
  }
  if (command === "/addconfig") {
    const found = extractLinks(text.split(/\s+/).slice(1).join("\n"));
    if (found.length === 0) return send(env, chatId, "Usage: /addconfig vless://... (one or more links)");
    const next = [...new Set([...(await stored(env, "configs")), ...found])].slice(-200);
    await env.STORE.put("configs", next.join("\n"));
    return send(env, chatId, `Saved. ${next.length} extra config(s).`);
  }
  if (command === "/clearconfigs") {
    await env.STORE.put("configs", "");
    return send(env, chatId, "Extra configs cleared.");
  }
}

function limited(chatId, now) {
  const times = (recent.get(chatId) || []).filter((t) => now - t < RATE_WINDOW_MS);
  times.push(now);
  recent.set(chatId, times);
  if (recent.size > 5000) recent.clear();
  return times.length > RATE_MAX;
}

function lines(value) {
  return String(value || "").split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
}

async function sendChunks(env, chatId, intro, items) {
  await send(env, chatId, intro);
  let chunk = [];
  let size = 0;
  for (const item of items) {
    if (size + item.length + 1 > TELEGRAM_LIMIT && chunk.length) {
      await send(env, chatId, code(chunk));
      chunk = [];
      size = 0;
    }
    chunk.push(item);
    size += item.length + 1;
  }
  if (chunk.length) await send(env, chatId, code(chunk));
}

function code(items) {
  const escaped = items.join("\n").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  return { text: `<pre>${escaped}</pre>`, parse_mode: "HTML" };
}

function send(env, chatId, message) {
  const body = typeof message === "string" ? { text: message } : message;
  return telegram(env, "sendMessage", { chat_id: chatId, disable_web_page_preview: true, ...body });
}

async function telegram(env, method, payload) {
  const res = await (env.fetchImpl || fetch)(`https://api.telegram.org/bot${env.BOT_TOKEN}/${method}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  });
  return res.json().catch(() => ({ ok: false }));
}
