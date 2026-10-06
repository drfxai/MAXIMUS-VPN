# Telegram distribution bot

A Cloudflare Worker that gives users ready-to-paste configurations and subscription addresses (with
CDN mirrors) over Telegram. It is for people who cannot open any web address yet: Telegram is often
still reachable, or reachable through its built-in proxy.

User commands: `/configs`, `/sub`, `/app`, `/help` (English and Persian).
Admin commands (only the `ADMIN_ID` account; send `/admin` for the list):

| | Free | VIP |
|---|---|---|
| Add one or more links | `/addfree <links>` | `/addvip <links>` |
| Show them, numbered | `/listfree` | `/listvip` |
| Delete by number or range | `/delfree 2 5-7` | `/delvip 2 5-7` |
| Delete all | `/clearfree` | `/clearvip` |

Free subscription links: `/addsub <url>`, `/editsub <n> <url>`, `/delsub <n or url>`. `/list` shows an
overview. `/addconfig` and `/clearconfigs` still work as the older names of `/addfree` and `/clearfree`.

Deleting always asks first: the bot shows the items and Yes/No buttons, and nothing is removed until
you press Yes. `/admin` also shows buttons for the lists.

## AI assistant (optional)

Send `/setkey <your Gemini API key>` (from aistudio.google.com). The bot saves it in the `STORE`
storage, deletes your message so the key does not stay in the chat, and from then on you can write
normally, in Persian or English: "show the VIP list", "add these to VIP" followed by links, "move 3 to
free", "delete the German free servers". `/ai` shows the status, `/model` shows or changes the model
(default `gemini-3.8-flash`), `/reset` forgets the conversation, `/delkey` removes the key. A
`GEMINI_API_KEY` secret on the Worker works too.

Config links and web addresses are replaced by placeholders before anything is sent to Gemini, and
the lists it reads show only protocol and name, so no server address or UUID leaves the Worker. Only
the `ADMIN_ID` account can use the assistant.

## If the bot does not answer

Open `https://<worker>/status?secret=<WEBHOOK_SECRET>`. It shows what is missing and Telegram's last
delivery error, and never shows a secret. `WEBHOOK_SECRET` may only contain letters, digits, `_` and
`-`: Telegram rejects anything else and then never delivers messages. `/setup` now refuses such a
secret with an explanation.

`https://<worker>/sub` is the app's free subscription: every configuration from the admin's links
and free configs, in the standard Base64 format. `https://<worker>/vip` serves only the VIP configs and
feeds the app's VIP section. The app adds it on first launch and refreshes it
every 6 hours (and after each connect), so a change made with `/addsub`, `/editsub` or `/delsub`
reaches every phone without an app update.

## Install from the Cloudflare dashboard (no tools needed)

1. In Telegram, open @BotFather, send `/newbot`, pick a name, and copy the token it gives you.
2. Open dash.cloudflare.com → **Workers & Pages** → **Create** → **Create Worker**. Name it
   `maximus-bot` and press **Deploy**.
3. Press **Edit code**, delete everything, paste the whole of `worker.js`, and press **Deploy**.
4. Go to the Worker's **Settings** → **Variables and Secrets** and add:
   - `BOT_TOKEN` (type **Secret**): the token from step 1.
   - `WEBHOOK_SECRET` (type **Secret**): 40 or so letters and digits. Only letters, digits, `_` and `-`
     are allowed; Telegram rejects anything else.
   - `ADMIN_ID` (type **Secret**): your numeric Telegram ID. Keep it out of the repository.
   - `APP_URL` (type **Text**, optional): where people download the app.
5. Go to **Storage & Databases** → **KV** → **Create**, name it `maximus-bot-store`. Back in the
   Worker's **Settings** → **Bindings** → **Add** → **KV namespace**, variable name `STORE`, pick
   `maximus-bot-store`.
6. Open `https://maximus-bot.<your-subdomain>.workers.dev/setup?secret=<WEBHOOK_SECRET>` once in a
   browser. It should show `"ok":true`.
7. In Telegram, send your bot `/addsub https://your-subscription-link`, then `/list` and `/configs` to check.
8. Open `https://maximus-bot.<your-subdomain>.workers.dev/sub` in a browser: it should show a long
   block of letters (your configurations in Base64). Send this address to the developer so it can be
   built into the app.

Telegram only calls the webhook with the secret header, so other callers get 403. Requests are
limited to 6 a minute per chat. The Worker fetches subscriptions from Cloudflare's network and falls
back to the jsDelivr, Statically and Githack copies of a GitHub-hosted file.

## Test

`node test.mjs`
