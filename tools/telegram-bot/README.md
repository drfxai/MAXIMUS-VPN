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

## Signed subscription (`/sub.signed`, `/vip.signed`)

With the secret `OFFICIAL_SIGNING_KEY` (ECDSA P-256, PKCS#8 PEM) the Worker also serves the
subscriptions as signed envelopes that any mirror can copy unchanged. The app then accepts the official
subscription only when signed, and tries independent mirrors when the Worker is blocked. Setup, mirrors
and key handling: [docs/SUBSCRIPTIONS.md](../../docs/SUBSCRIPTIONS.md).

## Free list status from Telegram

`/statusfree` shows the published free list (count, age, signature, rejection reasons), `/sources` each
source's result, `/iranstatus` what is known about Iran (always "unknown" for now: the list is built on
GitHub's servers outside Iran and phones keep their own results private), `/health` the bot's own parts
and `/diagnostics` the last problems the bot recorded (without links, tokens or keys). `/refreshfree`
asks Yes/No and then starts the free list builder on GitHub; it needs a `GH_TOKEN` secret (a
fine-grained GitHub token for this repository with "Actions: read and write").

## Admin panel (Telegram Mini App)

After `/setup`, the bot's menu button in your own chat opens the panel (or send `/panel`). It has
Dashboard, Configs (Free/VIP: add, delete, clear, copy a link on request), Sources, Validation, Iran
Health, Gemini AI, Diagnostics and Settings. The page itself holds no data; every request carries the
init data Telegram signs for the Mini App, and the Worker checks that signature against `BOT_TOKEN`,
refuses data older than an hour and accepts only the `ADMIN_ID` account. Deleting and starting a
build ask first, and the server refuses them without that confirmation. Server addresses and
credentials are never shown in the lists.

## AI assistant (optional)

Save a Gemini API key (from aistudio.google.com) in the panel's Gemini AI tab, or send `/setkey <key>`
(or `/setkey` and then the key as the next message). The bot deletes your message, stores the key only
encrypted (AES-GCM) in `STORE`, and checks it with Google straight away. Neither the panel, the chat,
the logs nor the app ever receive the key again: only "Configured" and its last four characters
(••••ABCD). Set a `KEY_ENCRYPTION_SECRET` secret (any long random string) to encrypt it; without one the
encryption key is derived from `BOT_TOKEN`, and changing the token means saving the Gemini key again.
A key saved by an older version as plain text is encrypted the first time it is used.

From then on you can write normally, in Persian or English: "show the VIP list", "add these to VIP"
followed by links, "move 3 to free", "delete the German free servers". `/ai` shows the status,
`/model` shows or changes the model (default `gemini-3.8-flash`; "Gemini 3.8 Flash" works too). A model
is only accepted when Google lists it for your key; otherwise you get an error and the model stays as
it was, never a different one. `/reset` forgets the conversation, `/delkey` removes the key. A
`GEMINI_API_KEY` secret on the Worker is used only when no key was saved.

Gemini is advisory: it never sees config links or web addresses (they are replaced by placeholders and
the lists it reads show only protocol and name), deleting always needs your Yes, and it has no say in
which configs the free list publishes. Only the `ADMIN_ID` account can use it.

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
   - `KEY_ENCRYPTION_SECRET` (type **Secret**, recommended): 40 or so random letters and digits; encrypts
     the saved Gemini key.
   - `GH_TOKEN` (type **Secret**, optional): lets `/refreshfree` and the panel start the free list build.
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
