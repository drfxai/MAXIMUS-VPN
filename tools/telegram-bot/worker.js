// MAXIMUS VPN distribution bot: a Cloudflare Worker that hands out subscription addresses and
// working configurations over Telegram, for users who cannot reach any web address yet (Telegram
// often stays reachable, or is reached through its own built-in proxy).
//
// Settings (Worker variables; BOT_TOKEN and WEBHOOK_SECRET as encrypted secrets):
//   BOT_TOKEN       token from @BotFather
//   WEBHOOK_SECRET  any long random string; Telegram sends it back on every update
//   SUB_URLS        subscription addresses, one per line, best first
//   CONFIGS         optional extra free links (vless://, trojan://, hysteria2://, ...), one per line
//   VIP_CONFIGS     optional extra VIP links, one per line
//   APP_URL         optional download page for the app
//   MAX_CONFIGS     optional, default 20
//   ADMIN_ID        your numeric Telegram ID; lets you change the lists from Telegram (send /admin)
//   SUB_MAX         optional, most configurations served at /sub, default 300
// Binding (optional, needed for the admin commands): a KV namespace named STORE.
//
// Routes: POST /webhook (Telegram), GET /setup?secret=WEBHOOK_SECRET (registers the webhook),
// GET /sub (the app's free subscription: the admin's subscription links plus free configs, Base64),
// GET /vip (the app's VIP subscription: the admin's VIP configs, Base64).

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
    if (request.method === "GET" && url.pathname === "/sub") return subscription(env);
    if (request.method === "GET" && url.pathname === "/vip") return subscription(env, "vip");
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

/**
 * The app's subscriptions as standard Base64. Free: everything the admin's links and free configs hold.
 * VIP: only the configs the admin added with /addvip.
 */
export async function subscription(env, tier = "free") {
  const vip = tier === "vip";
  const configs = vip
    ? (await tierConfigs(env, "vip")).filter((l) => LINK.test(l))
    : await collectConfigs({ ...env, MAX_CONFIGS: env.SUB_MAX || 300 });
  const bytes = new TextEncoder().encode(configs.join("\n"));
  let binary = "";
  for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
  const body = btoa(binary);
  return new Response(body, {
    status: configs.length ? 200 : 503,
    headers: {
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "public, max-age=300",
      "profile-title": vip ? "MAXIMUS VIP" : "MAXIMUS",
      "profile-update-interval": "6",
    },
  });
}

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
  if (env.ADMIN_ID) {
    // The admin's own chat also lists the management commands.
    await telegram(env, "setMyCommands", {
      scope: { type: "chat", chat_id: Number(String(env.ADMIN_ID).trim()) },
      commands: ADMIN_MENU.map(([command, description]) => ({ command: command.slice(1), description })),
    });
  }
  return new Response(JSON.stringify(res), { headers: { "content-type": "application/json" } });
}

const ADMIN_MENU = [
  ["/admin", "Admin help"],
  ["/list", "Overview of everything"],
  ["/addfree", "Add free configs"],
  ["/listfree", "Show free configs"],
  ["/delfree", "Delete free configs by number"],
  ["/clearfree", "Delete all free configs"],
  ["/addvip", "Add VIP configs"],
  ["/listvip", "Show VIP configs"],
  ["/delvip", "Delete VIP configs by number"],
  ["/clearvip", "Delete all VIP configs"],
  ["/addsub", "Add a free subscription link"],
  ["/editsub", "Replace a subscription link"],
  ["/delsub", "Delete a subscription link"],
];
// /addconfig and /clearconfigs are the older names of /addfree and /clearfree.
const ALIASES = { "/addconfig": "/addfree", "/clearconfigs": "/clearfree" };
const ADMIN_COMMANDS = [...ADMIN_MENU.map(([c]) => c), ...Object.keys(ALIASES)];
// KV keys and variables per tier. "configs" keeps the free list saved by older versions.
const TIERS = {
  free: { key: "configs", env: "CONFIGS", label: "Free" },
  vip: { key: "vip_configs", env: "VIP_CONFIGS", label: "VIP" },
};
const MAX_STORED = 200;

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
  return tierConfigs(env, "free");
}

async function tierConfigs(env, tier) {
  const t = TIERS[tier];
  return [...new Set([...lines(env[t.env]), ...(await stored(env, t.key))])];
}

/** "1. VLESS · Germany fast · 203.0.113.7:443" for the admin lists. */
export function describe(link, n) {
  const scheme = link.slice(0, link.indexOf("://")).toUpperCase();
  let name = "";
  let host = "";
  if (scheme === "VMESS") {
    try {
      const j = JSON.parse(atob(link.slice(8).split("#")[0]));
      name = j.ps || "";
      host = j.add ? `${j.add}:${j.port}` : "";
    } catch (_) {}
  } else {
    const hash = link.indexOf("#");
    if (hash >= 0) {
      try { name = decodeURIComponent(link.slice(hash + 1)); } catch (_) { name = link.slice(hash + 1); }
    }
    const m = link.match(/^[a-z0-9]+:\/\/(?:[^@/?#]*@)?([^/?#]+)/i);
    host = m ? m[1] : "";
  }
  return `${n}. ${scheme}${name ? ` · ${name.slice(0, 40)}` : ""}${host ? ` · ${host}` : ""}`;
}

/** "2 5-7" → [2, 5, 6, 7], keeping only 1..max. */
export function pickNumbers(args, max) {
  const out = new Set();
  for (const a of args) {
    const m = a.match(/^(\d+)(?:-(\d+))?$/);
    if (!m) continue;
    const from = Number(m[1]);
    const to = Number(m[2] || m[1]);
    for (let n = Math.min(from, to); n <= Math.max(from, to) && n <= max; n++) if (n >= 1) out.add(n);
  }
  return [...out];
}

async function tierCommand(chatId, action, tier, text, args, env) {
  const t = TIERS[tier];
  const current = await stored(env, t.key);
  if (action === "add") {
    const found = extractLinks(text.split(/\s+/).slice(1).join("\n"));
    if (found.length === 0) return send(env, chatId, `Usage: /add${tier} vless://... (one or more links, one per line)`);
    const next = [...new Set([...current, ...found])].slice(-MAX_STORED);
    await env.STORE.put(t.key, next.join("\n"));
    const added = next.length - current.length;
    return send(env, chatId, `${t.label}: ${added} added, ${next.length} in total. Apps pick it up on their next refresh.`);
  }
  if (action === "list") {
    if (current.length === 0) return send(env, chatId, `No ${t.label} configs yet. Add some with /add${tier}.`);
    return sendChunks(env, chatId, `${t.label} configs (${current.length}). Delete with /del${tier} N (for example /del${tier} 2 5-7).`,
      current.map((l, i) => describe(l, i + 1)));
  }
  if (action === "del") {
    const numbers = pickNumbers(args, current.length);
    const next = current.filter((l, i) => !numbers.includes(i + 1) && !args.includes(l));
    if (next.length === current.length) return send(env, chatId, `Usage: /del${tier} N (numbers from /list${tier}, for example 2 5-7)`);
    await env.STORE.put(t.key, next.join("\n"));
    return send(env, chatId, `${t.label}: ${current.length - next.length} deleted, ${next.length} left. Numbers have shifted, check /list${tier}.`);
  }
  if (action === "clear") {
    await env.STORE.put(t.key, "");
    return send(env, chatId, `All ${current.length} ${t.label} configs deleted.`);
  }
}

function adminHelp() {
  return ["Admin commands", "",
    "Free (everyone, MAXIMUS subscription):",
    "/addfree LINKS · /listfree · /delfree N · /clearfree", "",
    "VIP (the app's VIP section, MAXIMUS VIP subscription):",
    "/addvip LINKS · /listvip · /delvip N · /clearvip", "",
    "Free subscription links:",
    "/addsub URL · /editsub N URL · /delsub N", "",
    "/list shows everything. N can be several numbers or a range: /delvip 2 5-7"].join("\n");
}

async function admin(chatId, command, text, env) {
  if (!env.ADMIN_ID || String(chatId) !== String(env.ADMIN_ID).trim()) return send(env, chatId, helpText());
  if (!env.STORE) return send(env, chatId, "Add a KV namespace binding named STORE to the Worker first.");
  command = ALIASES[command] || command;
  const args = text.split(/\s+/).slice(1).filter(Boolean);
  if (command === "/admin") return send(env, chatId, adminHelp());
  const tiered = command.match(/^\/(add|list|del|clear)(free|vip)$/);
  if (tiered) return tierCommand(chatId, tiered[1], tiered[2], text, args, env);
  if (command === "/list") {
    const subs = await stored(env, "subs");
    const free = await stored(env, TIERS.free.key);
    const vip = await stored(env, TIERS.vip.key);
    const numbered = subs.map((u, i) => `${i + 1}. ${u}`).join("\n") || "none";
    return send(env, chatId, `Free subscription links (${subs.length}):\n${numbered}\n\n` +
      `Free configs: ${free.length} (/listfree)\nVIP configs: ${vip.length} (/listvip)\n\nAll commands: /admin`);
  }
  if (command === "/addsub") {
    const urls = args.filter((a) => /^https:\/\/\S+$/i.test(a));
    if (urls.length === 0) return send(env, chatId, "Usage: /addsub https://...");
    const next = [...new Set([...(await stored(env, "subs")), ...urls])];
    await env.STORE.put("subs", next.join("\n"));
    return send(env, chatId, `Saved. ${next.length} subscription(s). Apps pick it up on their next refresh.`);
  }
  if (command === "/editsub") {
    const current = await stored(env, "subs");
    const n = Number(args[0]);
    if (!Number.isInteger(n) || n < 1 || n > current.length || !/^https:\/\/\S+$/i.test(args[1] || "")) {
      return send(env, chatId, "Usage: /editsub N https://... (N from /list)");
    }
    current[n - 1] = args[1];
    await env.STORE.put("subs", [...new Set(current)].join("\n"));
    return send(env, chatId, `Subscription ${n} replaced.`);
  }
  if (command === "/delsub") {
    const current = await stored(env, "subs");
    const numbers = args.map(Number).filter((n) => Number.isInteger(n) && n >= 1 && n <= current.length);
    const next = current.filter((u, i) => !numbers.includes(i + 1) && !args.includes(u));
    if (next.length === current.length) return send(env, chatId, "Usage: /delsub N (from /list) or /delsub https://...");
    await env.STORE.put("subs", next.join("\n"));
    return send(env, chatId, `Deleted. ${next.length} subscription(s) left.`);
  }
  return send(env, chatId, adminHelp());
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
