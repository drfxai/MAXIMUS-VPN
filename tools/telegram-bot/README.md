# Telegram distribution bot

A Cloudflare Worker that gives users ready-to-paste configurations and subscription addresses (with
CDN mirrors) over Telegram. It is for people who cannot open any web address yet: Telegram is often
still reachable, or reachable through its built-in proxy.

User commands: `/configs`, `/sub`, `/app`, `/help` (English and Persian).
Admin commands (only the `ADMIN_ID` account): `/addsub <url>`, `/delsub <url>`, `/addconfig <links>`,
`/clearconfigs`, `/list`.

## Install from the Cloudflare dashboard (no tools needed)

1. In Telegram, open @BotFather, send `/newbot`, pick a name, and copy the token it gives you.
2. Open dash.cloudflare.com → **Workers & Pages** → **Create** → **Create Worker**. Name it
   `maximus-bot` and press **Deploy**.
3. Press **Edit code**, delete everything, paste the whole of `worker.js`, and press **Deploy**.
4. Go to the Worker's **Settings** → **Variables and Secrets** and add:
   - `BOT_TOKEN` (type **Secret**): the token from step 1.
   - `WEBHOOK_SECRET` (type **Secret**): any long random text, for example 40 letters and digits.
   - `ADMIN_ID` (type **Text**): your numeric Telegram ID.
   - `APP_URL` (type **Text**, optional): where people download the app.
5. Go to **Storage & Databases** → **KV** → **Create**, name it `maximus-bot-store`. Back in the
   Worker's **Settings** → **Bindings** → **Add** → **KV namespace**, variable name `STORE`, pick
   `maximus-bot-store`.
6. Open `https://maximus-bot.<your-subdomain>.workers.dev/setup?secret=<WEBHOOK_SECRET>` once in a
   browser. It should show `"ok":true`.
7. In Telegram, send your bot `/addsub https://your-subscription-link` and then `/configs` to check.

Telegram only calls the webhook with the secret header, so other callers get 403. Requests are
limited to 6 a minute per chat. The Worker fetches subscriptions from Cloudflare's network and falls
back to the jsDelivr, Statically and Githack copies of a GitHub-hosted file.

## Test

`node test.mjs`
