# Telegram distribution bot

A Cloudflare Worker that gives users subscription addresses (with CDN mirrors) and ready-to-paste
configurations over Telegram. It is for people who cannot open any web address yet: Telegram is often
still reachable, or reachable through its built-in proxy.

Commands: `/configs`, `/sub`, `/app`, `/help` (English and Persian).

## Deploy

1. Create a bot with @BotFather and copy its token.
2. `npx wrangler secret put BOT_TOKEN` and `npx wrangler secret put WEBHOOK_SECRET` (any long random string).
3. Put the subscription addresses in `SUB_URLS` in `wrangler.toml` (or in the dashboard), one per line.
4. `npx wrangler deploy`, then open `https://<worker>/setup?secret=<WEBHOOK_SECRET>` once.

Telegram only calls the webhook with the secret header, so other callers get 403. Requests are
limited to 6 a minute per chat. The Worker fetches subscriptions from Cloudflare's network, and
falls back to the jsDelivr, Statically and Githack copies of a GitHub-hosted file.

## Test

`node test.mjs`
