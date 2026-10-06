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
//   GEMINI_API_KEY  optional secret; or send /setkey to the bot. Lets the admin manage everything by
//                   writing normally (Persian or English). Config links never reach Gemini.
// Binding (optional, needed for the admin commands): a KV namespace named STORE.
//
// Routes: POST /webhook (Telegram), GET /setup?secret=WEBHOOK_SECRET (registers the webhook),
// GET /sub (the app's free subscription: the admin's subscription links plus free configs, Base64),
// GET /vip (the app's VIP subscription: the admin's VIP configs, Base64),
// GET /status?secret=WEBHOOK_SECRET (what is set up and Telegram's last delivery error; no secrets).

// Only what the app runs (TUIC is refused at import).
const LINK = /^(vless|vmess|trojan|ss|hysteria2|hy2|wireguard):\/\/\S+$/i;
const TELEGRAM_LIMIT = 3800;
const RATE_WINDOW_MS = 60_000;
const RATE_MAX = 6;
const recent = new Map();

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/setup") return setup(url, env);
    if (request.method === "GET" && url.pathname === "/status") return status(url, env);
    if (request.method === "GET" && url.pathname === "/sub") return subscription(env);
    if (request.method === "GET" && url.pathname === "/vip") return subscription(env, "vip");
    if (request.method === "POST" && url.pathname === "/webhook") {
      if (!env.WEBHOOK_SECRET || request.headers.get("X-Telegram-Bot-Api-Secret-Token") !== env.WEBHOOK_SECRET) {
        return new Response("forbidden", { status: 403 });
      }
      const update = await request.json().catch(() => null);
      // Answer Telegram at once; AI replies can take a few seconds and Telegram retries slow webhooks.
      const work = processUpdate(update, env).catch((e) => console.log("update failed", e?.message));
      if (ctx?.waitUntil) ctx.waitUntil(work);
      else await work;
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

async function processUpdate(update, env) {
  const message = update?.message;
  if (message?.chat?.id && typeof message.text === "string") {
    return handle(message.chat.id, message.text.trim(), env, Date.now(), { messageId: message.message_id });
  }
  if (update?.callback_query) return onButton(update.callback_query, env);
}

// Telegram accepts only these characters in the webhook secret.
const SECRET_OK = /^[A-Za-z0-9_-]{1,256}$/;

function plain(text, statusCode = 200) {
  return new Response(text, { status: statusCode, headers: { "content-type": "text/plain; charset=utf-8" } });
}

async function setup(url, env) {
  if (!env.WEBHOOK_SECRET || url.searchParams.get("secret") !== env.WEBHOOK_SECRET) {
    return new Response("forbidden", { status: 403 });
  }
  if (!SECRET_OK.test(env.WEBHOOK_SECRET)) {
    return plain("WEBHOOK_SECRET may only contain letters, digits, _ and -. Telegram rejects anything else, " +
      "so the bot never receives messages. Change it in the Worker's settings, deploy, and open /setup again.", 400);
  }
  if (!env.BOT_TOKEN) return plain("BOT_TOKEN is not set in the Worker's settings.", 400);
  const res = await telegram(env, "setWebhook", {
    url: `${url.origin}/webhook`,
    secret_token: env.WEBHOOK_SECRET,
    allowed_updates: ["message", "callback_query"],
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

/** What is set up, and Telegram's own view of the webhook. Never shows a secret. */
async function status(url, env) {
  if (!env.WEBHOOK_SECRET || url.searchParams.get("secret") !== env.WEBHOOK_SECRET) {
    return new Response("forbidden", { status: 403 });
  }
  const info = env.BOT_TOKEN ? await telegram(env, "getWebhookInfo", {}) : null;
  const hook = info?.result || {};
  const body = {
    bot_token: Boolean(env.BOT_TOKEN),
    bot_token_accepted: info ? info.ok === true : false,
    webhook_secret_valid: SECRET_OK.test(env.WEBHOOK_SECRET),
    admin_id: Boolean(env.ADMIN_ID),
    storage_bound: Boolean(env.STORE),
    ai_key: Boolean(await aiKey(env)),
    ai_model: await aiModel(env),
    webhook_registered: hook.url === `${url.origin}/webhook`,
    pending_updates: hook.pending_update_count ?? null,
    last_error: hook.last_error_message || null,
    last_error_at: hook.last_error_date ? new Date(hook.last_error_date * 1000).toISOString() : null,
  };
  return new Response(JSON.stringify(body, null, 2), { headers: { "content-type": "application/json" } });
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
  ["/ai", "AI assistant status"],
  ["/setkey", "Save the Gemini API key"],
  ["/delkey", "Remove the Gemini API key"],
  ["/model", "Show or change the Gemini model"],
  ["/reset", "Forget the AI conversation"],
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

export async function handle(chatId, text, env, now = Date.now(), meta = {}) {
  const command = text.split(/[\s@]/)[0].toLowerCase();
  if (ADMIN_COMMANDS.includes(command)) return admin(chatId, command, text, env, meta);
  // The admin can simply write; everyone else gets the fixed commands.
  if (!command.startsWith("/") && isAdmin(env, chatId)) return aiChat(chatId, text, env);
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
    if (numbers.length === 0) return send(env, chatId, `Usage: /del${tier} N (numbers from /list${tier}, for example 2 5-7)`);
    return confirm(env, chatId, { kind: "del", tier, numbers });
  }
  if (action === "clear") {
    if (current.length === 0) return send(env, chatId, `There are no ${t.label} configs.`);
    return confirm(env, chatId, { kind: "clear", tier });
  }
}

// ---------------------------------------------------------------- changes that delete something

/** One line per item an operation would remove, for the confirmation message. */
async function describeOp(env, op) {
  if (op.kind === "delsub") {
    const subs = await stored(env, "subs");
    return op.numbers.filter((n) => n <= subs.length).map((n) => `${n}. ${hostOf(subs[n - 1])}`);
  }
  const t = TIERS[op.tier];
  const current = await stored(env, t.key);
  const numbers = op.kind === "clear" ? current.map((_, i) => i + 1) : op.numbers.filter((n) => n <= current.length);
  return numbers.map((n) => describe(current[n - 1], n));
}

function opTitle(op) {
  if (op.kind === "delsub") return "Delete these subscription links?";
  const label = TIERS[op.tier].label;
  return op.kind === "clear" ? `Delete ALL ${label} configs?` : `Delete these ${label} configs?`;
}

/** Asks with Yes/No buttons; the change waits in storage for 10 minutes. */
async function confirm(env, chatId, op) {
  const items = await describeOp(env, op);
  if (items.length === 0) return send(env, chatId, "Nothing matches those numbers. Check the list first.");
  const id = Math.random().toString(36).slice(2, 10);
  await env.STORE.put("pending", JSON.stringify({ id, op, at: Date.now() }));
  const shown = items.length > 30 ? [...items.slice(0, 30), `… and ${items.length - 30} more`] : items;
  await send(env, chatId, {
    text: `${opTitle(op)}\n\n${shown.join("\n")}`,
    reply_markup: { inline_keyboard: [[{ text: "Yes, delete", callback_data: `ok:${id}` }, { text: "No", callback_data: `no:${id}` }]] },
  });
  return items;
}

async function runOp(env, op) {
  if (op.kind === "delsub") {
    const subs = await stored(env, "subs");
    const next = subs.filter((_, i) => !op.numbers.includes(i + 1));
    await env.STORE.put("subs", next.join("\n"));
    return `Deleted ${subs.length - next.length}. ${next.length} subscription link(s) left.`;
  }
  const t = TIERS[op.tier];
  const current = await stored(env, t.key);
  const next = op.kind === "clear" ? [] : current.filter((_, i) => !op.numbers.includes(i + 1));
  await env.STORE.put(t.key, next.join("\n"));
  return `${t.label}: ${current.length - next.length} deleted, ${next.length} left.` +
    (next.length ? ` Numbers have shifted, check /list${op.tier}.` : "");
}

const BUTTON_COMMANDS = ["/list", "/listfree", "/listvip", "/ai", "/admin"];

async function onButton(query, env) {
  const chatId = query.message?.chat?.id;
  await telegram(env, "answerCallbackQuery", { callback_query_id: query.id });
  if (!chatId || !isAdmin(env, query.from?.id) || !env.STORE) return;
  const data = String(query.data || "");
  if (data.startsWith("cmd:") && BUTTON_COMMANDS.includes(data.slice(4))) return admin(chatId, data.slice(4), data.slice(4), env);
  const m = data.match(/^(ok|no):(\w+)$/);
  if (!m) return;
  const pending = JSON.parse((await env.STORE.get("pending")) || "null");
  const fresh = pending && pending.id === m[2] && Date.now() - pending.at < 10 * 60_000;
  await telegram(env, "editMessageReplyMarkup", { chat_id: chatId, message_id: query.message.message_id, reply_markup: { inline_keyboard: [] } });
  if (!fresh) return send(env, chatId, "That question has expired. Ask again.");
  await env.STORE.delete("pending");
  if (m[1] === "no") return send(env, chatId, "Cancelled. Nothing was deleted.");
  return send(env, chatId, await runOp(env, pending.op));
}

function isAdmin(env, id) {
  return Boolean(env.ADMIN_ID) && String(id) === String(env.ADMIN_ID).trim();
}

function hostOf(url) {
  try { return new URL(url).hostname; } catch (_) { return "link"; }
}

function adminHelp() {
  return ["Admin commands", "",
    "Free (everyone, MAXIMUS subscription):",
    "/addfree LINKS · /listfree · /delfree N · /clearfree", "",
    "VIP (the app's VIP section, MAXIMUS VIP subscription):",
    "/addvip LINKS · /listvip · /delvip N · /clearvip", "",
    "Free subscription links:",
    "/addsub URL · /editsub N URL · /delsub N", "",
    "/list shows everything. N can be several numbers or a range: /delvip 2 5-7", "",
    "AI assistant (Gemini): /setkey KEY · /delkey · /model · /ai · /reset",
    "With a key saved, just write what you want, for example: add these to VIP, or delete the German free servers."].join("\n");
}

const ADMIN_BUTTONS = {
  inline_keyboard: [
    [{ text: "Overview", callback_data: "cmd:/list" }, { text: "Free list", callback_data: "cmd:/listfree" }],
    [{ text: "VIP list", callback_data: "cmd:/listvip" }, { text: "AI status", callback_data: "cmd:/ai" }],
  ],
};

async function admin(chatId, command, text, env, meta = {}) {
  if (!isAdmin(env, chatId)) return send(env, chatId, helpText());
  if (!env.STORE) return send(env, chatId, "Add a KV namespace binding named STORE to the Worker first.");
  command = ALIASES[command] || command;
  const args = text.split(/\s+/).slice(1).filter(Boolean);
  if (command === "/admin") return send(env, chatId, { text: adminHelp(), reply_markup: ADMIN_BUTTONS });
  if (["/ai", "/setkey", "/delkey", "/model", "/reset"].includes(command)) return aiCommand(chatId, command, args, env, meta);
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
    const numbers = [...new Set([...pickNumbers(args, current.length), ...current.map((u, i) => (args.includes(u) ? i + 1 : 0)).filter(Boolean)])];
    if (numbers.length === 0) return send(env, chatId, "Usage: /delsub N (from /list) or /delsub https://...");
    return confirm(env, chatId, { kind: "delsub", numbers });
  }
  return send(env, chatId, adminHelp());
}

// ---------------------------------------------------------------- AI assistant (admin only)

const DEFAULT_MODEL = "gemini-3.8-flash";
const GEMINI = "https://generativelanguage.googleapis.com/v1beta/models";
const HISTORY_TURNS = 12;
const MAX_TOOL_ROUNDS = 6;

async function aiKey(env) {
  if (env.GEMINI_API_KEY) return env.GEMINI_API_KEY;
  return env.STORE ? (await env.STORE.get("gemini_key")) || "" : "";
}

async function aiModel(env) {
  return (env.STORE && (await env.STORE.get("gemini_model"))) || env.GEMINI_MODEL || DEFAULT_MODEL;
}

async function aiCommand(chatId, command, args, env, meta) {
  if (command === "/setkey") {
    // The key must not stay in the chat history: delete the admin's message first.
    if (meta.messageId) await telegram(env, "deleteMessage", { chat_id: chatId, message_id: meta.messageId });
    const key = args[0] || "";
    if (!/^[A-Za-z0-9_-]{20,200}$/.test(key)) return send(env, chatId, "That does not look like a Gemini API key. Send /setkey followed by the key from aistudio.google.com.");
    await env.STORE.put("gemini_key", key);
    return send(env, chatId, `Key saved (ends in ${key.slice(-4)}) and your message deleted. Now just write what you want, for example: show the VIP list.`);
  }
  if (command === "/delkey") {
    await env.STORE.delete("gemini_key");
    return send(env, chatId, env.GEMINI_API_KEY ? "Stored key removed. A GEMINI_API_KEY secret is still set in Cloudflare and stays in use." : "Key removed. The AI assistant is off.");
  }
  if (command === "/model") {
    if (args[0]) {
      if (!/^gemini-[a-z0-9.-]{2,60}$/i.test(args[0])) return send(env, chatId, "Usage: /model gemini-3.8-flash");
      await env.STORE.put("gemini_model", args[0].toLowerCase());
    }
    return send(env, chatId, `Model: ${await aiModel(env)}${args[0] ? "" : `\nChange it with /model NAME (default ${DEFAULT_MODEL}).`}`);
  }
  if (command === "/reset") {
    await env.STORE.delete("ai_history");
    return send(env, chatId, "Conversation forgotten.");
  }
  const key = await aiKey(env);
  return send(env, chatId, key
    ? `AI assistant: on (key ends in ${key.slice(-4)}), model ${await aiModel(env)}.\nWrite normally to manage configs. Config links are hidden from Gemini.`
    : "AI assistant: off. Send /setkey followed by your Gemini API key to turn it on.");
}

/**
 * Replaces config links and web addresses with [LINK1], [URL1]... so Gemini never sees servers or
 * subscription addresses. The tools turn the placeholders back into the real links.
 */
export function redact(text) {
  const refs = {};
  let links = 0;
  let urls = 0;
  const out = text
    .replace(/\b(?:vless|vmess|trojan|ss|hysteria2|hy2|wireguard|tuic):\/\/\S+/gi, (m) => { const k = `LINK${++links}`; refs[k] = m; return `[${k}]`; })
    .replace(/\bhttps?:\/\/\S+/gi, (m) => { const k = `URL${++urls}`; refs[k] = m; return `[${k}]`; });
  return { text: out, refs };
}

const TIER_ARG = { type: "string", enum: ["free", "vip"], description: "free = everyone (MAXIMUS subscription), vip = the app's VIP section" };
const NUMBERS_ARG = { type: "array", items: { type: "integer" }, description: "Item numbers as shown by the list tool" };
const TOOLS = [{
  functionDeclarations: [
    { name: "get_overview", description: "Counts of free configs, VIP configs and subscription links.", parameters: { type: "object", properties: {} } },
    { name: "list_configs", description: "Numbered configs of one tier with protocol and display name.", parameters: { type: "object", properties: { tier: TIER_ARG }, required: ["tier"] } },
    { name: "add_configs", description: "Adds config links the admin sent, by their placeholders like LINK1.", parameters: { type: "object", properties: { tier: TIER_ARG, refs: { type: "array", items: { type: "string" } } }, required: ["tier", "refs"] } },
    { name: "move_configs", description: "Moves configs between free and VIP by number.", parameters: { type: "object", properties: { from: TIER_ARG, to: TIER_ARG, numbers: NUMBERS_ARG }, required: ["from", "to", "numbers"] } },
    { name: "delete_configs", description: "Asks the admin to confirm deleting configs by number. Nothing is deleted until they press Yes.", parameters: { type: "object", properties: { tier: TIER_ARG, numbers: NUMBERS_ARG }, required: ["tier", "numbers"] } },
    { name: "clear_configs", description: "Asks the admin to confirm deleting every config of a tier.", parameters: { type: "object", properties: { tier: TIER_ARG }, required: ["tier"] } },
    { name: "list_subscriptions", description: "Numbered free subscription links, by host name.", parameters: { type: "object", properties: {} } },
    { name: "add_subscriptions", description: "Adds subscription links the admin sent, by their placeholders like URL1.", parameters: { type: "object", properties: { refs: { type: "array", items: { type: "string" } } }, required: ["refs"] } },
    { name: "delete_subscriptions", description: "Asks the admin to confirm deleting subscription links by number.", parameters: { type: "object", properties: { numbers: NUMBERS_ARG }, required: ["numbers"] } },
  ],
}];

const SYSTEM = [
  "You are the admin assistant of the MAXIMUS VPN Telegram bot. Only the bot's owner talks to you.",
  "Answer in the language the admin writes in (usually Persian or English). Be short and plain.",
  "Use the tools to read and change the free and VIP config lists and the free subscription links. Never invent numbers or names: list first when unsure.",
  "Config links and web addresses in the admin's messages are replaced by placeholders such as [LINK1] or [URL1]; pass those names to the tools. You never see the real links.",
  "Deleting always needs the admin's confirmation: the delete tools show Yes/No buttons. Tell the admin to press one; never claim something was deleted.",
  "Free configs go to every user; VIP configs appear only in the app's VIP section.",
].join("\n");

function tierOf(v) {
  return v === "vip" ? "vip" : "free";
}

function summary(link, n) {
  const d = describe(link, n);
  // The list tool shows protocol and name only: no server address leaves the Worker.
  const scheme = link.slice(0, link.indexOf("://")).toUpperCase();
  const name = d.replace(/^\d+\. [A-Z0-9]+( · )?/, "").split(" · ")[0];
  return { n, protocol: scheme, name: name.includes(":") ? "" : name };
}

async function runTool(env, chatId, name, args, refs) {
  const nums = (a) => (Array.isArray(a) ? a.map(Number).filter((n) => Number.isInteger(n) && n > 0) : []);
  if (name === "get_overview") {
    return { free_configs: (await stored(env, TIERS.free.key)).length, vip_configs: (await stored(env, TIERS.vip.key)).length, subscription_links: (await stored(env, "subs")).length };
  }
  if (name === "list_configs") {
    const t = TIERS[tierOf(args.tier)];
    return { tier: tierOf(args.tier), items: (await stored(env, t.key)).map((l, i) => summary(l, i + 1)) };
  }
  if (name === "add_configs") {
    const t = TIERS[tierOf(args.tier)];
    const found = extractLinks((args.refs || []).map((r) => refs[String(r).replace(/[[\]]/g, "")] || "").join("\n"));
    if (found.length === 0) return { error: "No valid config link matches those placeholders." };
    const current = await stored(env, t.key);
    const next = [...new Set([...current, ...found])].slice(-MAX_STORED);
    await env.STORE.put(t.key, next.join("\n"));
    return { added: next.length - current.length, total: next.length };
  }
  if (name === "move_configs") {
    const from = TIERS[tierOf(args.from)];
    const to = TIERS[tierOf(args.to)];
    if (from === to) return { error: "from and to are the same list." };
    const src = await stored(env, from.key);
    const picked = nums(args.numbers).filter((n) => n <= src.length);
    if (picked.length === 0) return { error: "No such numbers." };
    const moving = picked.map((n) => src[n - 1]);
    await env.STORE.put(to.key, [...new Set([...(await stored(env, to.key)), ...moving])].slice(-MAX_STORED).join("\n"));
    await env.STORE.put(from.key, src.filter((_, i) => !picked.includes(i + 1)).join("\n"));
    return { moved: moving.length, note: "Numbers in both lists have shifted." };
  }
  if (name === "delete_configs" || name === "clear_configs" || name === "delete_subscriptions") {
    const op = name === "delete_subscriptions" ? { kind: "delsub", numbers: nums(args.numbers) }
      : name === "clear_configs" ? { kind: "clear", tier: tierOf(args.tier) }
      : { kind: "del", tier: tierOf(args.tier), numbers: nums(args.numbers) };
    const items = await confirm(env, chatId, op);
    return Array.isArray(items) ? { status: "Yes/No buttons shown to the admin; nothing deleted yet.", count: items.length } : { error: "Nothing matches those numbers." };
  }
  if (name === "list_subscriptions") {
    return { items: (await stored(env, "subs")).map((u, i) => ({ n: i + 1, host: hostOf(u) })) };
  }
  if (name === "add_subscriptions") {
    const urls = (args.refs || []).map((r) => refs[String(r).replace(/[[\]]/g, "")] || "").filter((u) => /^https:\/\/\S+$/i.test(u));
    if (urls.length === 0) return { error: "No https link matches those placeholders." };
    const current = await stored(env, "subs");
    const next = [...new Set([...current, ...urls])];
    await env.STORE.put("subs", next.join("\n"));
    return { added: next.length - current.length, total: next.length };
  }
  return { error: `Unknown tool ${name}` };
}

function geminiError(statusCode, body) {
  const msg = String(body?.error?.message || "");
  if (/location is not supported/i.test(msg)) return "Gemini is not available from this Cloudflare location. Try again later.";
  if (statusCode === 400 && /api key/i.test(msg) || statusCode === 401 || statusCode === 403) return "Gemini rejected the key. Send a new one with /setkey.";
  if (statusCode === 404) return "Gemini does not know that model. Change it with /model gemini-3.8-flash.";
  if (statusCode === 429) return "The Gemini quota is used up for now. Try again later.";
  return `Gemini error ${statusCode}. Try again in a moment.`;
}

export async function aiChat(chatId, text, env) {
  if (!env.STORE) return send(env, chatId, "Add a KV namespace binding named STORE to the Worker first.");
  const key = await aiKey(env);
  if (!key) return send(env, chatId, { text: "Send /setkey followed by a Gemini API key to talk to me normally, or use the buttons.\n\n" + adminHelp(), reply_markup: ADMIN_BUTTONS });
  const fetchImpl = env.fetchImpl || fetch;
  await telegram(env, "sendChatAction", { chat_id: chatId, action: "typing" });
  const { text: safe, refs } = redact(text);
  const history = JSON.parse((await env.STORE.get("ai_history")) || "[]");
  const contents = [...history, { role: "user", parts: [{ text: safe }] }];
  const model = await aiModel(env);
  let answer = "";
  for (let round = 0; round < MAX_TOOL_ROUNDS; round++) {
    let res;
    try {
      res = await fetchImpl(`${GEMINI}/${encodeURIComponent(model)}:generateContent`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-goog-api-key": key },
        body: JSON.stringify({ systemInstruction: { parts: [{ text: SYSTEM }] }, contents, tools: TOOLS }),
      });
    } catch (_) {
      return send(env, chatId, "Could not reach Gemini. Try again in a moment.");
    }
    const body = await res.json().catch(() => ({}));
    if (!res.ok) return send(env, chatId, geminiError(res.status, body));
    const content = body?.candidates?.[0]?.content;
    if (!content?.parts?.length) return send(env, chatId, "Gemini sent an empty answer. Try rephrasing.");
    // Sent back unchanged: newer models need their own parts (with thought signatures) returned as is.
    contents.push(content);
    const calls = content.parts.filter((p) => p.functionCall);
    if (calls.length === 0) {
      answer = content.parts.filter((p) => typeof p.text === "string" && !p.thought).map((p) => p.text).join("").trim();
      break;
    }
    const replies = [];
    for (const { functionCall } of calls) {
      const result = await runTool(env, chatId, functionCall.name, functionCall.args || {}, refs).catch((e) => ({ error: String(e?.message || e) }));
      replies.push({ functionResponse: { name: functionCall.name, response: { result } } });
    }
    contents.push({ role: "user", parts: replies });
  }
  if (!answer) answer = "Done.";
  const kept = [...history, { role: "user", parts: [{ text: safe }] }, { role: "model", parts: [{ text: answer }] }].slice(-HISTORY_TURNS);
  await env.STORE.put("ai_history", JSON.stringify(kept));
  return send(env, chatId, answer.slice(0, TELEGRAM_LIMIT));
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
