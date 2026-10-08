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
//   GEMINI_API_KEY  optional secret; or send /setkey to the bot (or use the panel). Lets the admin manage
//                   everything by writing normally (Persian or English). Config links never reach Gemini.
//   KEY_ENCRYPTION_SECRET  optional, recommended: encrypts the saved Gemini key (otherwise derived from BOT_TOKEN)
//   GH_TOKEN        optional secret: a GitHub token allowed to run Actions, for /refreshfree and the panel
//   FREE_REPO       optional, default drfxai/MAXIMUS-VPN (where the free-configs branch lives)
// Binding (optional, needed for the admin commands): a KV namespace named STORE.
//
// Routes: POST /webhook (Telegram), GET /setup?secret=WEBHOOK_SECRET (registers the webhook),
// GET /sub (the app's free subscription: the admin's subscription links plus free configs, Base64),
// GET /vip (the app's VIP subscription: the admin's VIP configs, Base64),
// GET /status?secret=WEBHOOK_SECRET (what is set up and Telegram's last delivery error; no secrets).
// GET /admin (the admin panel, a Telegram Mini App; holds no data), /api/* (the panel's data; every call
// needs init data signed by Telegram for the ADMIN_ID account).

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
    if (request.method === "GET" && url.pathname === "/sub.signed") return signedSubscription(env);
    if (request.method === "GET" && url.pathname === "/vip.signed") return signedSubscription(env, "vip");
    if (request.method === "GET" && url.pathname === "/admin") return adminPage();
    if (url.pathname.startsWith("/api/")) return api(request, env);
    if (request.method === "POST" && url.pathname === "/webhook") {
      if (!env.WEBHOOK_SECRET || request.headers.get("X-Telegram-Bot-Api-Secret-Token") !== env.WEBHOOK_SECRET) {
        return new Response("forbidden", { status: 403 });
      }
      const update = await request.json().catch(() => null);
      // Answer Telegram at once; AI replies can take a few seconds and Telegram retries slow webhooks.
      const work = processUpdate(update, env).catch((e) => logError(env, "update", e));
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

/** How long a signed copy stays valid; mirrors must refresh their copy before it runs out. */
export const SIGNED_TTL_MS = 7 * 24 * 3_600_000;

/**
 * The subscription as one signed envelope: {manifest, signature, payload}. Built from a single snapshot,
 * so payload and signature always match, and any mirror can serve the file unchanged (the app checks
 * the signature, the subscription id, the SHA-256, the expiry and that the sequence never goes back).
 * Signed with OFFICIAL_SIGNING_KEY, an ECDSA P-256 private key in PKCS#8 PEM form (a Worker secret).
 */
export async function signedSubscription(env, tier = "free", now = Date.now()) {
  if (!env.OFFICIAL_SIGNING_KEY) return new Response("signing is not configured", { status: 503 });
  const plain = await subscription(env, tier);
  if (plain.status !== 200) return plain;
  const payload = await plain.text();
  const manifest = JSON.stringify({
    version: 1,
    id: tier === "vip" ? "official-vip" : "official-sub",
    sequence: now,
    expires: now + SIGNED_TTL_MS,
    sha256: await sha256Hex(payload),
    bytes: new TextEncoder().encode(payload).length,
  });
  const signature = await signManifest(manifest, env.OFFICIAL_SIGNING_KEY);
  return new Response(JSON.stringify({ manifest, signature, payload }), {
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "public, max-age=300" },
  });
}

async function sha256Hex(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** ECDSA P-256 / SHA-256 over the manifest's UTF-8 bytes, DER-encoded and Base64, as Java's SHA256withECDSA expects. */
export async function signManifest(manifest, pem) {
  const der = Uint8Array.from(atob(pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "")), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  const raw = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(manifest)));
  let binary = "";
  for (const b of rawToDer(raw)) binary += String.fromCharCode(b);
  return btoa(binary);
}

/** WebCrypto returns r||s (64 bytes); DER is SEQUENCE { INTEGER r, INTEGER s }. */
function rawToDer(raw) {
  const int = (bytes) => {
    let i = 0;
    while (i < bytes.length - 1 && bytes[i] === 0) i++;
    let v = bytes.slice(i);
    if (v[0] & 0x80) v = Uint8Array.from([0, ...v]);
    return [0x02, v.length, ...v];
  };
  const body = [...int(raw.slice(0, 32)), ...int(raw.slice(32))];
  return Uint8Array.from([0x30, body.length, ...body]);
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
    // The admin's own chat also lists the management commands and opens the panel from the menu button.
    const chatId = Number(String(env.ADMIN_ID).trim());
    await telegram(env, "setMyCommands", {
      scope: { type: "chat", chat_id: chatId },
      commands: ADMIN_MENU.map(([command, description]) => ({ command: command.slice(1), description })),
    });
    await telegram(env, "setChatMenuButton", { chat_id: chatId, menu_button: { type: "web_app", text: "Admin", web_app: { url: `${url.origin}/admin` } } });
  }
  if (env.STORE) await env.STORE.put("origin", url.origin);
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
  ["/panel", "Open the admin panel"],
  ["/statusfree", "Published free list status"],
  ["/refreshfree", "Rebuild the free list now"],
  ["/sources", "Free list sources"],
  ["/health", "Bot and free list health"],
  ["/iranstatus", "What is known about Iran"],
  ["/diagnostics", "Recent problems"],
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
  if (!command.startsWith("/") && isAdmin(env, chatId)) {
    // /setkey tapped from the menu arrives without the key: the next message is the key.
    if (env.STORE && (await env.STORE.get("await_key"))) {
      await env.STORE.delete("await_key");
      return aiCommand(chatId, "/setkey", [text.trim()], env, meta);
    }
    return aiChat(chatId, text, env);
  }
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
  if (op.kind === "refresh") return ["The free list is rebuilt on GitHub now. The current list stays until the new one is published."];
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
  if (op.kind === "refresh") return "Rebuild the free list now?";
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
    reply_markup: { inline_keyboard: [[{ text: op.kind === "refresh" ? "Yes, rebuild" : "Yes, delete", callback_data: `ok:${id}` }, { text: "No", callback_data: `no:${id}` }]] },
  });
  return items;
}

async function runOp(env, op) {
  if (op.kind === "refresh") return (await dispatchRefresh(env)).message;
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

const BUTTON_COMMANDS = ["/list", "/listfree", "/listvip", "/ai", "/admin", "/statusfree", "/health"];

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
  if (m[1] === "no") return send(env, chatId, pending.op.kind === "refresh" ? "Cancelled. Nothing was started." : "Cancelled. Nothing was deleted.");
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
    "Published free list: /statusfree · /sources · /iranstatus · /refreshfree",
    "Bot: /health · /diagnostics · /panel (admin panel)", "",
    "AI assistant (Gemini): /setkey KEY · /delkey · /model · /ai · /reset",
    "With a key saved, just write what you want, for example: add these to VIP, or delete the German free servers."].join("\n");
}

const ADMIN_BUTTONS = {
  inline_keyboard: [
    [{ text: "Overview", callback_data: "cmd:/list" }, { text: "Free list", callback_data: "cmd:/listfree" }],
    [{ text: "VIP list", callback_data: "cmd:/listvip" }, { text: "AI status", callback_data: "cmd:/ai" }],
    [{ text: "Free list status", callback_data: "cmd:/statusfree" }, { text: "Health", callback_data: "cmd:/health" }],
  ],
};

async function admin(chatId, command, text, env, meta = {}) {
  if (!isAdmin(env, chatId)) return send(env, chatId, helpText());
  if (!env.STORE) return send(env, chatId, "Add a KV namespace binding named STORE to the Worker first.");
  command = ALIASES[command] || command;
  const args = text.split(/\s+/).slice(1).filter(Boolean);
  if (command === "/admin") return send(env, chatId, { text: adminHelp(), reply_markup: ADMIN_BUTTONS });
  if (["/ai", "/setkey", "/delkey", "/model", "/reset"].includes(command)) return aiCommand(chatId, command, args, env, meta);
  if (["/statusfree", "/sources", "/iranstatus", "/health", "/diagnostics"].includes(command)) return opsCommand(chatId, command, env);
  if (command === "/refreshfree") return confirm(env, chatId, { kind: "refresh" });
  if (command === "/panel") {
    const origin = await env.STORE.get("origin");
    if (!origin) return send(env, chatId, "Open /setup once in the browser first, so the bot knows its own address.");
    return send(env, chatId, { text: "MAXIMUS admin panel. Only your Telegram account can use it.", reply_markup: { inline_keyboard: [[{ text: "Open admin panel", web_app: { url: `${origin}/admin` } }]] } });
  }
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

// ---- The Gemini key: stored only encrypted (AES-GCM) in KV, never shown, logged or sent to a page.
// The encryption key comes from the KEY_ENCRYPTION_SECRET secret (or, without it, from BOT_TOKEN),
// so a copy of the KV data alone does not reveal the Gemini key.
const KEY_ENC = "gemini_key_enc";
const KEY_META = "gemini_key_meta";
const LEGACY_KEY = "gemini_key";
const KEY_FORMAT = /^[A-Za-z0-9_-]{20,200}$/;

/**
 * A pasted key often carries characters nobody can see: spaces or line breaks from copying, and on
 * phones set to Persian or Arabic, direction marks (U+200E/U+200F and friends) or zero-width spaces.
 * They are removed before the key is checked, so only real typos are refused.
 */
export function cleanKey(raw) {
  return String(raw || "").replace(/[\s\u00AD\u061C\u180E\u200B-\u200F\u202A-\u202E\u2060-\u2064\u2066-\u206F\uFEFF"'`]/g, "");
}

/** Why a cleaned key was refused, without repeating any of it. */
function keyProblem(key) {
  if (!key) return "The key field was empty. Paste the key and press Save again.";
  if (key.length < 20) return `That is only ${key.length} characters; a Gemini API key from aistudio.google.com is usually 39 and starts with AIza.`;
  if (key.length > 200) return `That is ${key.length} characters, far longer than a Gemini API key (usually 39, starting with AIza). Copy only the key.`;
  return "The key has characters a Gemini API key never contains (only letters, digits, - and _). Copy it again with the copy button in aistudio.google.com.";
}
const utf8 = new TextEncoder();

function b64(bytes) {
  let s = "";
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s);
}

function unb64(s) {
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
}

async function sealingKey(env) {
  const secret = env.KEY_ENCRYPTION_SECRET || env.BOT_TOKEN;
  if (!secret) throw new Error("no encryption secret");
  const base = await crypto.subtle.importKey("raw", utf8.encode(secret), "HKDF", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt: utf8.encode("maximus-gemini-key"), info: utf8.encode("v1") },
    base, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
}

export async function seal(env, text) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: utf8.encode(KEY_ENC) }, await sealingKey(env), utf8.encode(text));
  return `v1.${b64(iv)}.${b64(ct)}`;
}

export async function unseal(env, sealed) {
  const [v, iv, ct] = String(sealed).split(".");
  if (v !== "v1" || !iv || !ct) throw new Error("unknown format");
  const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: unb64(iv), additionalData: utf8.encode(KEY_ENC) }, await sealingKey(env), unb64(ct));
  return new TextDecoder().decode(pt);
}

async function readMeta(env) {
  return JSON.parse((env.STORE && (await env.STORE.get(KEY_META))) || "{}");
}

async function saveKey(env, key) {
  await env.STORE.put(KEY_ENC, await seal(env, key));
  await env.STORE.put(KEY_META, JSON.stringify({ last4: key.slice(-4), savedAt: Date.now() }));
  await env.STORE.delete(LEGACY_KEY);
}

async function deleteKey(env) {
  await env.STORE.delete(KEY_ENC);
  await env.STORE.delete(KEY_META);
  await env.STORE.delete(LEGACY_KEY);
}

/** The saved key: "" when none, null when it can no longer be decrypted (the secret changed). */
async function savedKey(env) {
  if (!env.STORE) return "";
  // Older versions stored the key as plain text: encrypt it and remove the plain copy.
  const legacy = await env.STORE.get(LEGACY_KEY);
  if (legacy) {
    await saveKey(env, legacy);
    return legacy;
  }
  const sealed = await env.STORE.get(KEY_ENC);
  if (!sealed) return "";
  try { return await unseal(env, sealed); } catch (_) { return null; }
}

/** The key saved from Telegram or the panel wins, so saving always takes effect; the GEMINI_API_KEY secret is the fallback. */
async function aiKey(env) {
  return (await savedKey(env)) || env.GEMINI_API_KEY || "";
}

/** What anyone may see about the key: whether it is set, where from, and its last four characters. */
export async function keyStatus(env) {
  const saved = await savedKey(env);
  const meta = await readMeta(env);
  const key = saved || env.GEMINI_API_KEY || "";
  return {
    configured: Boolean(key),
    source: saved ? "saved" : key ? "cloudflare-secret" : null,
    mask: key ? `••••${saved ? meta.last4 || key.slice(-4) : key.slice(-4)}` : null,
    unreadable: saved === null,
    encryption: env.KEY_ENCRYPTION_SECRET ? "KEY_ENCRYPTION_SECRET" : "derived from BOT_TOKEN",
    validatedAt: meta.validatedAt || null,
    valid: meta.valid ?? null,
    message: meta.message || null,
    model: await aiModel(env),
    modelChecked: meta.modelChecked || null,
  };
}

async function aiModel(env) {
  return (env.STORE && (await env.STORE.get("gemini_model"))) || env.GEMINI_MODEL || DEFAULT_MODEL;
}

/** "Gemini 3.8 Flash" or "models/gemini-3.8-flash" → "gemini-3.8-flash". */
export function normalizeModel(name) {
  return String(name || "").trim().toLowerCase().replace(/^models\//, "").replace(/\s+/g, "-");
}

/** Google's model list for a key: proves the key works and says which models it may use. */
async function listModels(env, key) {
  const fetchImpl = env.fetchImpl || fetch;
  const models = [];
  let page = "";
  for (let i = 0; i < 5; i++) {
    let res;
    try {
      res = await fetchImpl(`${GEMINI}?pageSize=1000${page ? `&pageToken=${encodeURIComponent(page)}` : ""}`, { headers: { "x-goog-api-key": key } });
    } catch (_) {
      return { ok: false, message: "Could not reach Google. Try again in a moment." };
    }
    const body = await res.json().catch(() => ({}));
    if (!res.ok) return { ok: false, status: res.status, message: geminiError(res.status, body) };
    for (const m of body.models || []) {
      if (!(m.supportedGenerationMethods || []).includes("generateContent")) continue;
      models.push({ id: normalizeModel(m.name), name: String(m.displayName || m.name).slice(0, 60) });
    }
    page = body.nextPageToken || "";
    if (!page) break;
  }
  return { ok: true, models };
}

/** Checks the key with Google and records the result (time, yes/no, message) next to it. */
export async function validateKey(env) {
  const key = await aiKey(env);
  if (!key) return { ok: false, message: "No key is configured." };
  const r = await listModels(env, key);
  const model = await aiModel(env);
  const meta = await readMeta(env);
  meta.validatedAt = Date.now();
  meta.valid = r.ok;
  meta.message = r.ok ? "Google accepted the key." : r.message;
  if (r.ok) meta.modelChecked = r.models.some((m) => m.id === model) ? "available" : "unavailable";
  await env.STORE.put(KEY_META, JSON.stringify(meta));
  if (r.ok) await env.STORE.put("gemini_models", JSON.stringify(r.models.slice(0, 200)));
  return { ...r, model, modelAvailable: r.ok ? meta.modelChecked === "available" : null };
}

/** Changes the model only when Google lists it for this key. Never substitutes another one. */
export async function setModel(env, raw) {
  const id = normalizeModel(raw);
  if (!/^gemini-[a-z0-9.-]{2,60}$/.test(id)) return { ok: false, message: "Model names look like gemini-3.8-flash." };
  const key = await aiKey(env);
  if (!key) return { ok: false, message: "Save a key first: the model is checked against Google's list for that key." };
  const r = await listModels(env, key);
  if (!r.ok) return { ok: false, message: r.message };
  if (!r.models.some((m) => m.id === id)) {
    const flash = r.models.map((m) => m.id).filter((m) => m.includes("flash")).slice(0, 6);
    return { ok: false, message: `Google does not offer ${id} for this key. The model was not changed.${flash.length ? ` Available, for example: ${flash.join(", ")}` : ""}` };
  }
  await env.STORE.put("gemini_model", id);
  await env.STORE.put("gemini_models", JSON.stringify(r.models.slice(0, 200)));
  const meta = await readMeta(env);
  meta.modelChecked = "available";
  await env.STORE.put(KEY_META, JSON.stringify(meta));
  await activity(env, `Gemini model set to ${id}`);
  return { ok: true, model: id };
}

/** Saves a new key encrypted and checks it with Google straight away. */
export async function storeKey(env, key) {
  key = cleanKey(key);
  if (!KEY_FORMAT.test(key)) return { ok: false, message: keyProblem(key) };
  await saveKey(env, key);
  await activity(env, "Gemini key saved");
  const v = await validateKey(env);
  return { ok: true, valid: v.ok, message: v.message || (v.ok ? "Google accepted the key." : ""), modelAvailable: v.modelAvailable, model: v.model };
}

async function aiCommand(chatId, command, args, env, meta) {
  if (command === "/setkey") {
    // The key must not stay in the chat history: delete the admin's message first.
    if (meta.messageId) await telegram(env, "deleteMessage", { chat_id: chatId, message_id: meta.messageId });
    if (!args[0]) {
      await env.STORE.put("await_key", "1", { expirationTtl: 300 });
      return send(env, chatId, "Send the Gemini API key as your next message. I will delete it from the chat right away.");
    }
    const r = await storeKey(env, args[0]);
    if (!r.ok) return send(env, chatId, `${r.message} Send /setkey followed by the key.`);
    const mask = (await keyStatus(env)).mask;
    return send(env, chatId, `Key saved encrypted (${mask}) and your message deleted. ` +
      (r.valid ? `Google accepted it${r.modelAvailable === false ? `, but model ${r.model} is not available for it: pick another with /model` : `; model ${r.model} is available`}. Now just write what you want, for example: show the VIP list.`
        : `Google did not accept it yet: ${r.message}`));
  }
  if (command === "/delkey") {
    await deleteKey(env);
    await activity(env, "Gemini key deleted");
    return send(env, chatId, env.GEMINI_API_KEY ? "Saved key removed. The GEMINI_API_KEY secret in Cloudflare is used instead; delete it there to turn the assistant off." : "Key removed. The AI assistant is off.");
  }
  if (command === "/model") {
    if (args[0]) {
      const r = await setModel(env, args.join(" "));
      if (!r.ok) return send(env, chatId, r.message);
    }
    return send(env, chatId, `Model: ${await aiModel(env)}${args[0] ? " (checked with Google)" : `\nChange it with /model NAME (default ${DEFAULT_MODEL}).`}`);
  }
  if (command === "/reset") {
    await env.STORE.delete("ai_history");
    return send(env, chatId, "Conversation forgotten.");
  }
  const s = await keyStatus(env);
  if (s.unreadable) return send(env, chatId, "The saved key can no longer be decrypted (BOT_TOKEN or KEY_ENCRYPTION_SECRET changed). Save it again with /setkey.");
  return send(env, chatId, s.configured
    ? `AI assistant: on (key ${s.mask}${s.source === "cloudflare-secret" ? ", from the Cloudflare secret" : ", stored encrypted"}), model ${s.model}.` +
      (s.validatedAt ? `\nLast check: ${s.valid ? "accepted" : "rejected"} ${new Date(s.validatedAt).toISOString().slice(0, 16).replace("T", " ")} UTC.` : "") +
      "\nWrite normally to manage configs. Config links are hidden from Gemini. Gemini only suggests; deleting always needs your Yes."
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
    if (!res.ok) {
      await logError(env, "gemini", `HTTP ${res.status}`);
      return send(env, chatId, geminiError(res.status, body));
    }
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

// ---------------------------------------------------------------- free list status, logs, refresh

const FREE_REPO = "drfxai/MAXIMUS-VPN";
const FREE_FILE_TTL_MS = 5 * 60_000;
const freeCache = new Map();
const LOG_MAX = 50;

/** A file of the published free list (free-configs branch), through the same CDN mirrors the app uses. */
async function freeFile(env, name, now = Date.now()) {
  const raw = `https://raw.githubusercontent.com/${env.FREE_REPO || FREE_REPO}/free-configs/${name}`;
  const hit = freeCache.get(raw);
  if (hit && now - hit.at < FREE_FILE_TTL_MS && !env.fetchImpl) return hit.text;
  let missing = false;
  for (const url of [raw, ...gitHubMirrors(raw)]) {
    try {
      const res = await (env.fetchImpl || fetch)(url, { headers: { "User-Agent": "Maximus-VPN-Bot/1.0" }, cf: { cacheTtl: 300 } });
      if (res.status === 404) missing = true;
      if (!res.ok) continue;
      const text = await res.text();
      freeCache.set(raw, { at: now, text });
      return text;
    } catch (_) {
      // next mirror
    }
  }
  // Every copy that answered says the file does not exist (an older build without it, or nothing published yet).
  if (missing) return null;
  throw new Error(`could not download ${name}`);
}

async function freeJson(env, name) {
  const text = await freeFile(env, name);
  return text ? JSON.parse(text) : null;
}

/** The free list builder runs at minute 17 of every sixth hour (free-configs.yml). */
export function nextBuild(now = Date.now()) {
  const d = new Date(now);
  d.setUTCMinutes(17, 0, 0);
  while (d.getTime() <= now || d.getUTCHours() % 6 !== 0) d.setUTCHours(d.getUTCHours() + 1);
  return d.getTime();
}

/** Everything the admin may know about the published free list. No server address or credential. */
export async function freeStatus(env, now = Date.now()) {
  const manifest = await freeJson(env, "manifest.json");
  if (!manifest) return { published: false };
  const configs = await freeJson(env, "configs.json").catch(() => null);
  const sig = ((await freeFile(env, "manifest.sig").catch(() => "")) || "").trim();
  const report = manifest.report || {};
  const meta = report.source_meta || [];
  const rejected = report.rejected || {};
  const records = configs?.configs || [];
  const count = (key) => records.reduce((m, r) => ({ ...m, [r[key]]: (m[r[key]] || 0) + 1 }), {});
  const created = Date.parse(manifest.created);
  return {
    published: true,
    count: manifest.count ?? 0,
    created: manifest.created,
    ageHours: Number.isFinite(created) ? Math.round((now - created) / 360_000) / 10 : null,
    nextBuild: new Date(nextBuild(now)).toISOString(),
    signed: sig.length > 0,
    candidates: meta.reduce((n, m) => n + (m.candidate_count || 0), 0) || null,
    rejectedTotal: Object.values(rejected).reduce((a, b) => a + b, 0),
    rejected,
    sources: Object.entries(report.sources || {}).map(([name, state]) => {
      const m = meta.find((x) => x.source_name === name) || {};
      return { name, state, fetch: m.fetch_status || null, candidates: m.candidate_count ?? null, valid: m.valid_count ?? null };
    }),
    diversity: report.diversity || null,
    detailed: Boolean(configs),
    iran: records.length ? count("iran_status") : { UNKNOWN_IRAN_STATUS: manifest.count ?? 0 },
    lifecycle: records.length ? count("lifecycle") : null,
    evidence: manifest.evidence || { global: "tested from a GitHub Actions runner", iran: "UNKNOWN_IRAN_STATUS" },
  };
}

/** Bot tokens, API keys, links and long tokens never reach a log. */
export function scrub(text) {
  return redact(String(text || "")).text
    .replace(/\b\d{6,12}:[A-Za-z0-9_-]{30,}/g, "[BOT_TOKEN]")
    .replace(/AIza[0-9A-Za-z_-]{20,}/g, "[API_KEY]")
    .replace(/\b[A-Za-z0-9_+/-]{32,}={0,2}/g, "[TOKEN]")
    .slice(0, 300);
}

async function pushLog(env, key, entry) {
  if (!env.STORE) return;
  try {
    const list = JSON.parse((await env.STORE.get(key)) || "[]");
    list.push(entry);
    await env.STORE.put(key, JSON.stringify(list.slice(-LOG_MAX)));
  } catch (_) {
    // a log must never break the request
  }
}

export function logError(env, where, error) {
  return pushLog(env, "errors", { at: Date.now(), where, message: scrub(error?.message || error) });
}

function activity(env, text) {
  return pushLog(env, "activity", { at: Date.now(), text: scrub(text) });
}

async function readLog(env, key) {
  return env.STORE ? JSON.parse((await env.STORE.get(key)) || "[]") : [];
}

/** Starts the free list builder on GitHub. Needs a GH_TOKEN secret allowed to run Actions. */
async function dispatchRefresh(env) {
  if (!env.GH_TOKEN) return { ok: false, message: "Add a GH_TOKEN secret (a GitHub token allowed to run Actions on the repository) to start builds from here." };
  const repo = env.FREE_REPO || FREE_REPO;
  let res;
  try {
    res = await (env.fetchImpl || fetch)(`https://api.github.com/repos/${repo}/actions/workflows/free-configs.yml/dispatches`, {
      method: "POST",
      headers: { authorization: `Bearer ${env.GH_TOKEN}`, accept: "application/vnd.github+json", "user-agent": "Maximus-VPN-Bot/1.0", "content-type": "application/json" },
      body: JSON.stringify({ ref: "main" }),
    });
  } catch (e) {
    await logError(env, "refresh", e);
    return { ok: false, message: "Could not reach GitHub. Try again in a moment." };
  }
  if (res.status !== 204) {
    await logError(env, "refresh", `GitHub answered ${res.status}`);
    return { ok: false, message: `GitHub refused the build (${res.status}). Check that GH_TOKEN may run Actions.` };
  }
  freeCache.clear();
  await activity(env, "Free list rebuild started by the admin");
  return { ok: true, message: "Build started on GitHub. The new list is published in a few minutes; the current one stays until then." };
}

/** The bot's own parts: what is set up and Telegram's view of the webhook. */
async function health(env, origin) {
  const info = env.BOT_TOKEN ? await telegram(env, "getWebhookInfo", {}) : null;
  const hook = info?.result || {};
  return {
    botToken: Boolean(env.BOT_TOKEN),
    botTokenAccepted: info ? info.ok === true : false,
    webhookSecretValid: SECRET_OK.test(env.WEBHOOK_SECRET || ""),
    webhookRegistered: origin ? hook.url === `${origin}/webhook` : Boolean(hook.url),
    pendingUpdates: hook.pending_update_count ?? null,
    lastError: hook.last_error_message ? scrub(hook.last_error_message) : null,
    lastErrorAt: hook.last_error_date ? new Date(hook.last_error_date * 1000).toISOString() : null,
    adminId: Boolean(env.ADMIN_ID),
    storage: Boolean(env.STORE),
    keyEncryptionSecret: Boolean(env.KEY_ENCRYPTION_SECRET),
    refreshToken: Boolean(env.GH_TOKEN),
  };
}

function utc(iso) {
  return iso ? String(iso).slice(0, 16).replace("T", " ") + " UTC" : "unknown";
}

function iranLine(iran, total) {
  const unknown = iran.UNKNOWN_IRAN_STATUS || 0;
  const known = Object.entries(iran).filter(([k]) => k !== "UNKNOWN_IRAN_STATUS");
  return `${unknown} of ${total} unknown` + (known.length ? `, ${known.map(([k, v]) => `${v} ${k}`).join(", ")}` : "") +
    ". The list is built on GitHub's servers outside Iran, so it cannot say what works inside Iran; phones keep their own results private.";
}

/** /statusfree, /sources, /iranstatus, /health, /diagnostics */
async function opsCommand(chatId, command, env) {
  if (command === "/health" || command === "/diagnostics") {
    const h = await health(env, await env.STORE.get("origin"));
    const k = await keyStatus(env);
    if (command === "/health") {
      let free = "unknown";
      try {
        const f = await freeStatus(env);
        free = f.published ? `${f.count} configs, built ${f.ageHours} h ago${f.ageHours > 12 ? " (late: the builder may be failing)" : ""}` : "not published";
      } catch (e) { free = "could not download the status"; }
      return send(env, chatId, [
        `Webhook: ${h.webhookRegistered ? "registered" : "NOT registered (open /setup)"}, ${h.pendingUpdates ?? "?"} waiting` + (h.lastError ? `, last error: ${h.lastError} (${utc(h.lastErrorAt)})` : ""),
        `Storage: ${h.storage ? "bound" : "missing"} · Gemini: ${k.configured ? `configured ${k.mask}` : "not configured"}${k.unreadable ? " (saved key unreadable)" : ""}`,
        `Free list: ${free}`,
        `Rebuild from Telegram: ${h.refreshToken ? "ready" : "add a GH_TOKEN secret"}`,
      ].join("\n"));
    }
    const errors = (await readLog(env, "errors")).slice(-10).reverse();
    return send(env, chatId, errors.length
      ? `Last ${errors.length} problems (newest first):\n` + errors.map((e) => `${utc(new Date(e.at).toISOString())} · ${e.where}: ${e.message}`).join("\n")
      : "No problems recorded.");
  }
  let f;
  try {
    f = await freeStatus(env);
  } catch (e) {
    await logError(env, command, e);
    return send(env, chatId, "Could not download the free list's status from GitHub or its mirrors. Try again in a moment.");
  }
  if (!f.published) return send(env, chatId, "No free list has been published yet.");
  if (command === "/statusfree") {
    const top = Object.entries(f.rejected).sort((a, b) => b[1] - a[1]).slice(0, 5).map(([k, v]) => `${k} ${v}`).join(", ");
    return send(env, chatId, [
      `Free list: ${f.count} configs, built ${utc(f.created)} (${f.ageHours} h ago), ${f.signed ? "signed" : "NOT signed"}.`,
      f.candidates ? `${f.candidates} candidates from ${f.sources.length} sources.` : `${f.sources.length} sources.`,
      `Rejected ${f.rejectedTotal}${top ? `: ${top}` : ""}.`,
      f.lifecycle ? `Lifecycle: ${Object.entries(f.lifecycle).map(([k, v]) => `${v} ${k}`).join(", ")}.` : "",
      `Next scheduled build: ${utc(f.nextBuild)}. Rebuild now: /refreshfree`,
    ].filter(Boolean).join("\n"));
  }
  if (command === "/sources") {
    return sendChunks(env, chatId, `Sources of the free list (build ${utc(f.created)}):`,
      f.sources.map((s) => `${s.name}: ${s.state}${s.candidates != null ? ` · ${s.candidates} candidates, ${s.valid ?? 0} kept` : ""}`));
  }
  if (command === "/iranstatus") return send(env, chatId, `Iran status: ${iranLine(f.iran, f.count)}`);
}

// ---------------------------------------------------------------- admin mini app (Telegram Web App)

const INIT_MAX_AGE_S = 3600;

async function hmac(key, data) {
  const k = await crypto.subtle.importKey("raw", typeof key === "string" ? utf8.encode(key) : key, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", k, utf8.encode(data)));
}

function hex(bytes) {
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Telegram's Web App check: the hash is HMAC-SHA256 of the sorted fields under a key derived from the
 * bot token, so only Telegram can produce it. Then the data must be fresh and the user the admin.
 */
export async function verifyInitData(env, initData, now = Date.now()) {
  if (!env.BOT_TOKEN || !initData) return { ok: false, status: 401, error: "Open this page from the bot's Admin button in Telegram." };
  const params = new URLSearchParams(initData);
  const hash = params.get("hash") || "";
  params.delete("hash");
  const check = [...params.entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([k, v]) => `${k}=${v}`).join("\n");
  const expected = hex(await hmac(await hmac("WebAppData", env.BOT_TOKEN), check));
  let diff = expected.length ^ hash.length;
  for (let i = 0; i < expected.length; i++) diff |= expected.charCodeAt(i) ^ (hash.charCodeAt(i) || 0);
  if (diff !== 0) return { ok: false, status: 401, error: "This request was not signed by Telegram." };
  const authDate = Number(params.get("auth_date"));
  if (!Number.isFinite(authDate) || now / 1000 - authDate > INIT_MAX_AGE_S || authDate - now / 1000 > 60) {
    return { ok: false, status: 401, error: "The session expired. Close the panel and open it again from the bot." };
  }
  let user = null;
  try { user = JSON.parse(params.get("user") || "null"); } catch (_) {}
  if (!user?.id || !isAdmin(env, user.id)) return { ok: false, status: 403, error: "Only the bot's admin can use this panel." };
  return { ok: true, userId: user.id };
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
}

/** A stable id per link, so deleting works by identity and never hits a shifted number. */
async function linkId(link) {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", utf8.encode(link)))).slice(0, 12);
}

/** Protocol, name, transport and security of a link; never its address or credentials. */
async function linkView(link, n) {
  const protocol = link.slice(0, link.indexOf("://")).toUpperCase();
  let name = "";
  let transport = "";
  let security = "";
  const q = link.indexOf("?");
  const hash = link.indexOf("#");
  if (protocol === "VMESS") {
    try {
      const j = JSON.parse(atob(link.slice(8).split("#")[0]));
      name = j.ps || "";
      transport = j.net || "";
      security = j.tls || "";
    } catch (_) {}
  } else if (hash >= 0) {
    try { name = decodeURIComponent(link.slice(hash + 1)); } catch (_) { name = link.slice(hash + 1); }
  }
  if (protocol !== "VMESS" && q > 0) {
    const p = new URLSearchParams(link.slice(q + 1).split("#")[0]);
    transport = p.get("type") || "";
    security = p.get("security") || "";
  }
  // A name that is itself an address (host:port) is not shown.
  name = name.slice(0, 60);
  if (/^[\w.-]+:\d+$/.test(name) || /^\d{1,3}(\.\d{1,3}){3}$/.test(name)) name = "";
  return { id: await linkId(link), n, protocol, name, transport, security };
}

async function apiOverview(env, origin, now) {
  const [free, vip, subs] = await Promise.all([stored(env, TIERS.free.key), stored(env, TIERS.vip.key), stored(env, "subs")]);
  let list = null;
  let listError = null;
  try { list = await freeStatus(env, now); } catch (e) { listError = "Could not download the free list's status."; }
  const h = await health(env, origin);
  return {
    list, listError,
    vip: vip.length, botFree: free.length, subs: subs.length,
    gemini: await keyStatus(env),
    webhook: { ok: h.webhookRegistered && !h.lastError, registered: h.webhookRegistered, pending: h.pendingUpdates, lastError: h.lastError, lastErrorAt: h.lastErrorAt },
    activity: (await readLog(env, "activity")).slice(-8).reverse(),
  };
}

/** /api/*: every call needs Telegram-signed init data from the admin's account. */
export async function api(request, env, now = Date.now()) {
  const url = new URL(request.url);
  const auth = request.headers.get("authorization") || "";
  const v = await verifyInitData(env, auth.startsWith("tma ") ? auth.slice(4) : "", now);
  if (!v.ok) return json({ error: v.error }, v.status);
  if (!env.STORE) return json({ error: "Add a KV namespace binding named STORE to the Worker first." }, 500);
  const route = url.pathname.slice(5);
  const body = request.method === "POST" ? await request.json().catch(() => ({})) : {};
  const confirmed = body.confirm === true;
  try {
    if (request.method === "GET") {
      if (route === "overview") return json(await apiOverview(env, url.origin, now));
      if (route === "configs") {
        const tier = tierOf(url.searchParams.get("tier"));
        const links = await stored(env, TIERS[tier].key);
        return json({ tier, items: await Promise.all(links.map((l, i) => linkView(l, i + 1))) });
      }
      if (route === "sources") {
        const subs = await stored(env, "subs");
        let list = null;
        try { list = await freeStatus(env, now); } catch (_) {}
        return json({ list: list && { sources: list.sources, created: list.created }, subscriptions: subs.map((u, i) => ({ n: i + 1, host: hostOf(u) })) });
      }
      if (route === "validation" || route === "iran") return json(await freeStatus(env, now));
      if (route === "diagnostics") return json({ health: await health(env, url.origin), errors: (await readLog(env, "errors")).slice(-30).reverse() });
      if (route === "gemini") return json({ ...(await keyStatus(env)), models: JSON.parse((await env.STORE.get("gemini_models")) || "[]") });
    }
    if (request.method === "POST") {
      if (route === "configs/add") {
        const tier = tierOf(body.tier);
        const t = TIERS[tier];
        const current = await stored(env, t.key);
        const found = extractLinks(String(body.text || ""));
        if (!found.length) return json({ error: "No vless://, vmess://, trojan://, ss://, hysteria2:// or wireguard:// links found." }, 400);
        const next = [...new Set([...current, ...found])].slice(-MAX_STORED);
        await env.STORE.put(t.key, next.join("\n"));
        await activity(env, `${t.label}: ${next.length - current.length} added from the panel`);
        return json({ added: next.length - current.length, total: next.length });
      }
      if (route === "configs/delete" || route === "configs/clear") {
        if (!confirmed) return json({ error: "Deleting needs confirmation." }, 400);
        const tier = tierOf(body.tier);
        const t = TIERS[tier];
        const current = await stored(env, t.key);
        const ids = new Set(Array.isArray(body.ids) ? body.ids.map(String) : []);
        const keep = [];
        for (const l of current) if (route === "configs/delete" && !ids.has(await linkId(l))) keep.push(l);
        await env.STORE.put(t.key, keep.join("\n"));
        await activity(env, `${t.label}: ${current.length - keep.length} deleted from the panel`);
        return json({ deleted: current.length - keep.length, total: keep.length });
      }
      if (route === "configs/link") {
        // Only on an explicit tap: the full link holds the server's credentials.
        const links = await stored(env, TIERS[tierOf(body.tier)].key);
        for (const l of links) if ((await linkId(l)) === String(body.id)) return json({ link: l });
        return json({ error: "That config no longer exists." }, 404);
      }
      if (route === "refresh") {
        if (!confirmed) return json({ error: "Starting a build needs confirmation." }, 400);
        const r = await dispatchRefresh(env);
        return json(r, r.ok ? 200 : 400);
      }
      if (route === "gemini/key") {
        const r = await storeKey(env, body.key);
        return r.ok ? json({ ...r, status: await keyStatus(env) }) : json({ error: r.message }, 400);
      }
      if (route === "gemini/validate") {
        const r = await validateKey(env);
        return json({ ok: r.ok, message: r.message || "Google accepted the key.", modelAvailable: r.modelAvailable, status: await keyStatus(env) });
      }
      if (route === "gemini/delete") {
        if (!confirmed) return json({ error: "Deleting needs confirmation." }, 400);
        await deleteKey(env);
        await activity(env, "Gemini key deleted from the panel");
        return json({ status: await keyStatus(env) });
      }
      if (route === "gemini/model") {
        const r = await setModel(env, body.model);
        return r.ok ? json({ ...r, status: await keyStatus(env) }) : json({ error: r.message }, 400);
      }
    }
  } catch (e) {
    await logError(env, `api ${route}`, e);
    return json({ error: "Something went wrong on the server. It was recorded under Diagnostics." }, 500);
  }
  return json({ error: "Unknown request." }, 404);
}

/** The panel page. It holds no secret: every piece of data comes from /api with Telegram-signed init data. */
function adminPage() {
  return new Response(ADMIN_HTML, {
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "content-security-policy": "default-src 'none'; script-src 'unsafe-inline' https://telegram.org; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'",
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff",
    },
  });
}

const ADMIN_HTML = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MAXIMUS Admin</title><script src="https://telegram.org/js/telegram-web-app.js"></script>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:Inter,Roboto,system-ui,sans-serif;color:#F3F4F8;background:#090A0F;min-height:100vh}
.app{min-height:100vh;padding-bottom:24px;background:radial-gradient(120% 35% at 20% 0%,#2a2150 0%,#090A0F 60%)}
.top{text-align:center;padding:12px 16px 2px;font-size:15px;font-weight:700}.top small{display:block;font-weight:500;color:#989AA8;font-size:12px}
.wrap{padding:14px 16px;max-width:640px;margin:0 auto}
.card{background:#161720;border:1px solid #262837;border-radius:16px;padding:14px;margin-bottom:10px}
.row{display:flex;align-items:center;gap:8px}.sp{justify-content:space-between}
.h{font-size:11px;font-weight:700;letter-spacing:1px;color:#686A7A;margin:14px 2px 8px}
.k{font-size:24px;font-weight:800}.k2{font-size:17px;font-weight:800;margin:5px 0}.s{font-size:11.5px;color:#989AA8;line-height:1.45}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px}.grid .card{margin:0}
.pill{font-size:10.5px;font-weight:700;padding:3px 7px;border-radius:7px;white-space:nowrap;background:#282A38;color:#989AA8}
.pill.ok{background:#1d2a22;color:#4ADE80}.pill.err{background:#3a1f24;color:#F87171}.pill.warn{background:#2a2410;color:#FBBF24}
.ok{color:#4ADE80}.warn{color:#FBBF24}.err{color:#F87171}.pri{color:#D0BCFF}
button{font:inherit;border:0;cursor:pointer;display:inline-flex;align-items:center;justify-content:center;gap:6px;font-size:13px;font-weight:700;padding:10px 14px;border-radius:12px;background:#1E202B;color:#D0BCFF}
button.main{background:#4F378B;color:#E8DEF8}button.danger{background:#2a1b1e;color:#F87171;border:1px solid #5b2a2f}button:disabled{opacity:.5}
.tabs{display:flex;gap:6px;overflow-x:auto;padding:10px 16px 0;scrollbar-width:none}
.tab{font-size:12px;padding:7px 11px;border-radius:10px;background:#161720;border:1px solid #262837;color:#989AA8;white-space:nowrap}
.tab.on{background:#4F378B;color:#E8DEF8;border-color:#4F378B}.tab.gold.on{background:#2a2410;color:#FBBF24;border-color:#4a3f17}
textarea,input,select{width:100%;font:inherit;font-size:14px;color:#F3F4F8;background:#14151D;border:1px solid #383B4E;border-radius:12px;padding:11px 12px}
textarea{min-height:70px;resize:vertical}
.li{display:flex;align-items:center;gap:10px;padding:10px 0;border-top:1px solid #262837}.li:first-child{border:0}
.bar{height:6px;border-radius:3px;background:#262837;margin-top:4px}.bar i{display:block;height:6px;border-radius:3px;background:#D0BCFF}
</style></head><body><div class="app">
<div class="top">MAXIMUS Admin<small id="who">checking access…</small></div>
<div class="tabs" id="tabs"></div><div class="wrap" id="main"></div></div>
<script>
var tg = window.Telegram && Telegram.WebApp; if (tg) { tg.ready(); tg.expand(); }
var TABS = [["dash","Dashboard"],["configs","Configs"],["sources","Sources"],["validation","Validation"],["iran","Iran Health"],["gemini","Gemini AI"],["diag","Diagnostics"],["settings","Settings"]];
var tab = "dash", tier = "vip";
function $(id) { return document.getElementById(id); }
function h(tag, props) {
  var e = document.createElement(tag), kids = [].slice.call(arguments, 2);
  Object.keys(props || {}).forEach(function (k) { var v = props[k]; if (k === "on") e.onclick = v; else if (k === "cls") e.className = v; else if (k === "style") e.style.cssText = v; else e.setAttribute(k, v); });
  (function add(list) { list.forEach(function (c) { if (Array.isArray(c)) add(c); else if (c != null && c !== false) e.append(c.nodeType ? c : String(c)); }); })(kids);
  return e;
}
function api(path, body) {
  return fetch("/api/" + path, { method: body ? "POST" : "GET", headers: { authorization: "tma " + (tg ? tg.initData : ""), "content-type": "application/json" }, body: body ? JSON.stringify(body) : undefined })
    .then(function (r) { return r.json().catch(function () { return { error: "Bad answer from the server." }; }).then(function (j) { if (!r.ok) throw new Error(j.error || ("Error " + r.status)); $("who").textContent = "mini app · verified admin"; return j; }); });
}
function ask(text) { return new Promise(function (res) { if (tg && tg.showConfirm) tg.showConfirm(text, res); else res(confirm(text)); }); }
function note(text) { if (tg && tg.showAlert) tg.showAlert(text); else alert(text); }
function ago(t) { if (!t) return "never"; var m = Math.round((Date.now() - new Date(t).getTime()) / 60000); return m < 1 ? "just now" : m < 60 ? m + " min ago" : m < 1440 ? Math.round(m / 60) + " h ago" : Math.round(m / 1440) + " d ago"; }
function card() { return h("div", { cls: "card" }, [].slice.call(arguments)); }
function pill(text, kind) { return h("span", { cls: "pill " + (kind || "") }, text); }
function stat(v, label, cls) { return h("div", null, h("div", { cls: "k " + (cls || "") }, v), h("div", { cls: "s" }, label)); }
function act(btn, fn) { return function () { btn.disabled = true; Promise.resolve().then(fn).catch(function (e) { note(e.message); }).then(function () { btn.disabled = false; }); }; }
function button(text, cls, fn) { var b = h("button", { cls: cls || "" }, text); b.onclick = act(b, fn); return b; }
function renderTabs() { $("tabs").replaceChildren.apply($("tabs"), TABS.map(function (t) { return h("span", { cls: "tab" + (t[0] === tab ? " on" : ""), on: function () { show(t[0]); } }, t[1]); })); }
function show(name) {
  tab = name; renderTabs(); var main = $("main"); main.replaceChildren(h("div", { cls: "s" }, "Loading…"));
  return Promise.resolve().then(VIEWS[name]).then(function (els) { main.replaceChildren.apply(main, [].concat(els).flat(Infinity).filter(Boolean)); })
    .catch(function (e) { main.replaceChildren(card(h("b", { cls: "err" }, "Could not load"), h("div", { cls: "s", style: "margin-top:6px" }, e.message))); });
}
function iranUnknown(i) { return i ? (i.UNKNOWN_IRAN_STATUS || 0) : 0; }
var VIEWS = {
  dash: function () { return api("overview").then(function (o) {
    var l = o.list, g = o.gemini, w = o.webhook;
    return [
      card(h("div", { cls: "row sp" }, h("b", null, "Free list"), l && l.published ? pill((l.signed ? "✓ Signed " : "Unsigned ") + ago(l.created), l.signed ? "ok" : "err") : pill(o.listError ? "Unreachable" : "Not published", "warn")),
        l && l.published ? [h("div", { cls: "row", style: "gap:18px;margin-top:10px" }, stat(l.count, "published"), l.candidates != null ? stat(l.candidates, "candidates") : null, stat(l.rejectedTotal, "rejected", "err")),
          h("div", { cls: "s", style: "margin-top:10px" }, "Next build " + new Date(l.nextBuild).toUTCString().slice(17, 22) + " UTC · " + l.sources.length + " sources")] : h("div", { cls: "s", style: "margin-top:8px" }, o.listError || "")),
      h("div", { cls: "grid" },
        card(h("div", { cls: "s" }, "VIP configs"), h("div", { cls: "k" }, o.vip), h("div", { cls: "s" }, "served at /vip")),
        card(h("div", { cls: "s" }, "Iran status"), h("div", { cls: "k warn" }, l && l.published ? iranUnknown(l.iran) : "–"), h("div", { cls: "s" }, "unknown · built outside Iran; phones keep their results private")),
        card(h("div", { cls: "s" }, "Gemini AI"), h("div", { cls: "k2" }, g.configured ? "Configured" : "Not configured"), h("div", { cls: "s" }, g.configured ? "key " + g.mask + " · " + g.model + (g.modelChecked === "available" ? " ✓" : "") : "advisory only")),
        card(h("div", { cls: "s" }, "Bot webhook"), h("div", { cls: "k2 " + (w.ok ? "ok" : "err") }, w.ok ? "Healthy" : w.registered ? "Errors" : "Not set"), h("div", { cls: "s" }, w.lastError ? "last error " + ago(w.lastErrorAt) : (w.pending || 0) + " waiting"))),
      h("div", { cls: "h" }, "RECENT ACTIVITY"),
      card(o.activity.length ? o.activity.map(function (a) { return h("div", { cls: "li" }, h("span", { cls: "pri" }, "●"), h("div", { style: "flex:1;font-size:13px" }, a.text, h("div", { cls: "s" }, ago(a.at)))); }) : h("div", { cls: "s" }, "Nothing yet.")),
      h("div", { cls: "row", style: "margin-top:6px" },
        button("↻ Refresh free list", "main", function () { return ask("Start a free list rebuild on GitHub now? The current list stays until the new one is published.").then(function (y) { if (y) return api("refresh", { confirm: true }).then(function (r) { note(r.message); }); }); }),
        button("Diagnostics", "", function () { return show("diag"); }))
    ];
  }); },
  configs: function () { return api("configs?tier=" + tier).then(function (c) {
    var label = tier === "vip" ? "VIP" : "Free (bot)";
    var input = h("textarea", { placeholder: "Paste vless:// trojan:// ss:// links to add" });
    return [
      h("div", { cls: "row", style: "margin-bottom:10px" },
        h("span", { cls: "tab gold" + (tier === "vip" ? " on" : ""), on: function () { tier = "vip"; show("configs"); } }, "VIP"),
        h("span", { cls: "tab" + (tier === "free" ? " on" : ""), on: function () { tier = "free"; show("configs"); } }, "Free (bot)")),
      input,
      h("div", { style: "margin:8px 0 12px" }, button("Add to " + label, "main", function () { return api("configs/add", { tier: tier, text: input.value }).then(function (r) { note(r.added + " added, " + r.total + " in total."); return show("configs"); }); })),
      card(c.items.length ? c.items.map(function (it) {
        var del = button("🗑", "danger", function () { return ask("Delete " + (it.name || it.protocol) + "? Phones lose it at their next refresh.").then(function (y) { if (y) return api("configs/delete", { tier: tier, ids: [it.id], confirm: true }).then(function () { return show("configs"); }); }); });
        del.onclick = (function (f) { return function (e) { e.stopPropagation(); f(); }; })(del.onclick);
        return h("div", { cls: "li", on: function () { ask("Copy this config's link? It contains the server's credentials.").then(function (y) { if (y) api("configs/link", { tier: tier, id: it.id }).then(function (r) { return navigator.clipboard.writeText(r.link); }).then(function () { note("Copied."); }).catch(function (e) { note(e.message); }); }); } },
          h("div", { style: "flex:1;min-width:0" }, h("div", { style: "font-size:13.5px;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" }, it.n + ". " + (it.name || it.protocol)),
            h("div", { cls: "s" }, [it.protocol, it.transport, it.security].filter(Boolean).join(" · "))), del);
      }) : h("div", { cls: "s" }, "No " + label + " configs.")),
      h("div", { cls: "s", style: "margin:4px 2px 12px" }, "Server addresses and credentials are never shown here; tap a row to copy its link."),
      c.items.length ? button("Clear " + label + " list…", "danger", function () { return ask("Clear all " + c.items.length + " " + label + " configs? Phones lose them at their next refresh. This cannot be undone.").then(function (y) { if (y) return api("configs/clear", { tier: tier, confirm: true }).then(function () { return show("configs"); }); }); }) : null
    ];
  }); },
  sources: function () { return api("sources").then(function (s) {
    return [h("div", { cls: "h" }, "FREE LIST SOURCES" + (s.list ? " · BUILD " + ago(s.list.created).toUpperCase() : "")),
      card(s.list && s.list.sources.length ? s.list.sources.map(function (x) { return h("div", { cls: "li" }, h("div", { style: "flex:1" }, h("div", { style: "font-size:13.5px;font-weight:700" }, x.name), h("div", { cls: "s" }, x.state + (x.candidates != null ? " · " + x.candidates + " candidates, " + (x.valid || 0) + " kept" : ""))), pill(x.fetch && x.fetch.indexOf("failed") === 0 ? "Failed" : "OK", x.fetch && x.fetch.indexOf("failed") === 0 ? "err" : "ok")); }) : h("div", { cls: "s" }, "No build report yet.")),
      h("div", { cls: "h" }, "BOT SUBSCRIPTION LINKS"),
      card(s.subscriptions.length ? s.subscriptions.map(function (x) { return h("div", { cls: "li" }, h("div", { cls: "s" }, x.n + ". " + x.host)); }) : h("div", { cls: "s" }, "None. Add them with /addsub in the bot."))];
  }); },
  validation: function () { return api("validation").then(function (v) {
    if (!v.published) return card(h("div", { cls: "s" }, "No free list has been published yet."));
    var max = Math.max.apply(null, [1].concat(Object.values(v.rejected)));
    var rows = Object.entries(v.rejected).sort(function (a, b) { return b[1] - a[1]; });
    var div = v.diversity;
    return [card(h("div", { cls: "row sp" }, h("b", null, "Last build"), pill(v.signed ? "✓ Signed" : "Unsigned", v.signed ? "ok" : "err")), h("div", { cls: "row", style: "gap:18px;margin-top:10px" }, stat(v.count, "kept"), stat(v.rejectedTotal, "rejected", "err")), h("div", { cls: "s", style: "margin-top:8px" }, "Built " + ago(v.created) + ". Every config was tested from a GitHub Actions runner, outside Iran.")),
      h("div", { cls: "h" }, "WHY CONFIGS WERE REJECTED"),
      card(rows.map(function (r) { return h("div", { style: "margin:6px 0" }, h("div", { cls: "row sp s" }, h("span", null, r[0]), h("span", null, r[1])), h("div", { cls: "bar" }, h("i", { style: "width:" + Math.round(r[1] / max * 100) + "%" }))); })),
      div ? [h("div", { cls: "h" }, "DIVERSITY"), card(h("div", { cls: "s" }, "Kinds: " + Object.entries(div.kinds || {}).map(function (e) { return e[0] + " " + e[1]; }).join(", ")), h("div", { cls: "s" }, "CDN: " + Object.entries(div.cdn || {}).map(function (e) { return e[0] + " " + e[1]; }).join(", ")), h("div", { cls: "s" }, div.failure_domains + " failure domains, the largest holds " + div.largest_failure_domain))] : null];
  }); },
  iran: function () { return api("iran").then(function (v) {
    if (!v.published) return card(h("div", { cls: "s" }, "No free list has been published yet."));
    return [card(h("div", { cls: "s" }, "Iran status"), h("div", { cls: "k warn" }, iranUnknown(v.iran) + " of " + v.count + " unknown"),
        h("div", { cls: "s", style: "margin-top:8px" }, "The list is built and tested on GitHub's servers outside Iran, so it cannot say what works inside Iran. Phones test configs themselves and keep those results private; nothing is reported back.")),
      v.lifecycle ? card(h("b", null, "Lifecycle"), h("div", { cls: "s", style: "margin-top:6px" }, Object.entries(v.lifecycle).map(function (e) { return e[1] + " " + e[0]; }).join(" · "))) : null];
  }); },
  gemini: function () { return api("gemini").then(function (g) {
    var keyInput = h("input", { type: "password", autocomplete: "off", placeholder: "Paste your key from aistudio.google.com" });
    var save = button(g.configured ? "Save new key" : "Save and validate", "main", function () { var k = keyInput.value.replace(/[\\s\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]/g, ""); keyInput.value = ""; return api("gemini/key", { key: k }).then(function (r) { note(r.valid ? "Saved encrypted. Google accepted the key." : "Saved encrypted, but Google did not accept it: " + r.message); return show("gemini"); }); });
    var box = h("div", { style: "margin-top:12px;display:" + (g.configured ? "none" : "block") }, keyInput, h("div", { style: "margin-top:10px" }, save));
    var select = h("select", null, (g.models.length ? g.models : [{ id: g.model, name: g.model }]).map(function (m) { var o = h("option", { value: m.id }, m.name + " (" + m.id + ")"); if (m.id === g.model) o.setAttribute("selected", ""); return o; }));
    return [
      card(h("div", { cls: "row sp" }, h("b", null, "API key"), pill(g.configured ? "✓ Configured" : "Not configured", g.configured ? "ok" : "")),
        g.configured ? h("div", { style: "margin-top:12px;letter-spacing:2px", cls: "card" }, "••••••••" + g.mask) : null,
        g.unreadable ? h("div", { cls: "s err", style: "margin-top:8px" }, "The saved key can no longer be decrypted. Save it again.") : null,
        h("div", { cls: "s", style: "margin-top:8px" }, g.source === "cloudflare-secret" ? "From the GEMINI_API_KEY secret in Cloudflare. A key saved here takes its place." : "Stored encrypted on the server. It is never sent to this page, the app or the bot chat."),
        g.configured ? h("div", { cls: "row", style: "margin-top:12px" },
          button("Validate", "", function () { return api("gemini/validate", {}).then(function (r) { note(r.ok ? "Google accepted the key." + (r.modelAvailable === false ? " The selected model is not available for it." : "") : r.message); return show("gemini"); }); }),
          button("Replace", "", function () { box.style.display = "block"; keyInput.focus(); }),
          g.source === "saved" ? button("Delete", "danger", function () { return ask("Delete the saved Gemini key? The AI assistant stops until a new key is saved.").then(function (y) { if (y) return api("gemini/delete", { confirm: true }).then(function () { return show("gemini"); }); }); }) : null) : null,
        g.validatedAt ? h("div", { cls: "s", style: "margin-top:10px" }, h("span", { cls: g.valid ? "ok" : "err" }, g.valid ? "✓ " : "✕ "), "Validated " + ago(g.validatedAt) + " · " + (g.message || "")) : null,
        box),
      card(h("b", null, "Model"), h("div", { style: "margin-top:12px" }, select),
        h("div", { cls: "s", style: "margin-top:8px" }, g.modelChecked === "available" ? h("span", { cls: "ok" }, "✓ Available for this key (checked against Google's model list)") : g.modelChecked === "unavailable" ? h("span", { cls: "err" }, "✕ Not available for this key: pick another") : "Not checked yet: validate the key."),
        h("div", { cls: "s", style: "margin-top:6px" }, "If a model is not available you get an error, never a different model."),
        h("div", { style: "margin-top:10px" }, button("Use this model", "", function () { return api("gemini/model", { model: select.value }).then(function (r) { note("Model set to " + r.model + "."); return show("gemini"); }); }))),
      h("div", { cls: "card", style: "border-color:#4a3f17;background:#1a1710" }, h("b", { cls: "warn" }, "Advisory only"), h("div", { cls: "s", style: "margin-top:6px" }, "Gemini explains and suggests. It never decides which configs are published or deleted; the scoring rules and you do."))
    ];
  }); },
  diag: function () { return api("diagnostics").then(function (d) {
    var w = d.health;
    return [card(h("b", null, "Webhook"), h("div", { cls: "s", style: "margin-top:6px" }, (w.webhookRegistered ? "Registered" : "Not registered: open /setup") + " · " + (w.pendingUpdates == null ? "?" : w.pendingUpdates) + " updates waiting"),
        w.lastError ? h("div", { cls: "s err" }, "Telegram's last delivery error " + ago(w.lastErrorAt) + ": " + w.lastError) : null),
      h("div", { cls: "h" }, "RECENT PROBLEMS"),
      card(d.errors.length ? d.errors.map(function (e) { return h("div", { cls: "li" }, h("span", { cls: "err" }, "●"), h("div", { style: "flex:1;font-size:13px" }, e.where + ": " + e.message, h("div", { cls: "s" }, ago(e.at)))); }) : h("div", { cls: "s" }, "No problems recorded."))];
  }); },
  settings: function () { return api("diagnostics").then(function (d) {
    var w = d.health;
    function item(ok, text, hint) { return h("div", { cls: "li" }, h("span", { cls: ok ? "ok" : "warn" }, ok ? "✓" : "!"), h("div", { style: "flex:1;font-size:13px" }, text, ok ? null : h("div", { cls: "s" }, hint))); }
    return [card(
      item(w.botToken && w.botTokenAccepted, "BOT_TOKEN accepted by Telegram", "Set BOT_TOKEN in the Worker's Variables and Secrets."),
      item(w.webhookSecretValid, "WEBHOOK_SECRET uses only allowed characters", "Letters, digits, _ and - only."),
      item(w.adminId, "ADMIN_ID set", "Your numeric Telegram ID, as a secret."),
      item(w.storage, "STORE storage bound", "Add a KV namespace binding named STORE."),
      item(w.keyEncryptionSecret, "KEY_ENCRYPTION_SECRET set", "Optional but recommended: without it the Gemini key is encrypted with a key derived from BOT_TOKEN, and changing the token means saving the Gemini key again."),
      item(w.refreshToken, "GH_TOKEN set (refresh from the panel)", "A GitHub token allowed to run Actions on the repository.")),
      h("div", { cls: "s", style: "margin:4px 2px" }, "Secrets are changed in Cloudflare, never here. Storage changes can take up to a minute to reach every Cloudflare location.")];
  }); }
};
show("dash");
</script></body></html>`;
