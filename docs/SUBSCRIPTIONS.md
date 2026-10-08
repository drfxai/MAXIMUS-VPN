# Official subscription: signed copies and independent mirrors

The official subscriptions (`/sub` and `/vip` on the Telegram bot's Worker) are the control plane: they
deliver new servers. They must not depend on one address, and above all not on the one the VPN is
using. A Cloudflare Worker is a single failure domain:

- on a filtered network the `workers.dev` name is often blocked (SNI filtering);
- through a VPN whose exit is itself a Cloudflare Worker, the request fails as well (a Worker cannot
  open Cloudflare addresses).

The free list is public and uses GitHub and its CDN copies. The official configs are not published
there. Instead, the Worker signs its subscription, and any number of independent hosts can serve the
signed copy unchanged.

## How it works

`GET /sub.signed` (and `/vip.signed`) returns one JSON envelope built from a single snapshot:

```json
{"manifest": "{\"version\":1,\"id\":\"official-sub\",\"sequence\":1760000000000,\"expires\":1760604800000,\"sha256\":\"…\",\"bytes\":1234}",
 "signature": "<base64 DER ECDSA P-256 / SHA-256 over the manifest string>",
 "payload": "<the usual base64 subscription>"}
```

The app, when its build carries the public key:

- **Where it fetches from.** It fetches `<address>.signed` from the Worker first, then from every mirror
  (staggered, first good answer wins).
- **What it checks.** It accepts a copy only when all of these hold:
  - the signature verifies;
  - `id` is the subscription asked for (a `/vip` copy cannot stand in for `/sub`);
  - the payload's SHA-256 matches;
  - `expires` is in the future;
  - `sequence` is not lower than the highest one already accepted on this phone (anti-rollback).
- **How it replaces servers.** A verified list replaces that subscription's servers in one database
  transaction. Servers the admin removed are deleted. The server in use, the selected one and
  favourites are always kept.
- **When every address fails.** The servers on the phone stay as they are (last known good), and the
  connection is never touched.

Without the public key in the build, the plain `/sub` is used exactly as before. A build therefore
never loses its subscription because the Worker does not sign yet.

## Setting it up

1. **Create the signing key** on your own computer (keep the private key off the repository):

       openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out official-signing.pem
       openssl pkey -in official-signing.pem -pubout -outform DER | base64 -w0 > official-public.txt

2. **Worker:** add `OFFICIAL_SIGNING_KEY` as a secret, with the whole content of
   `official-signing.pem`, BEGIN and END lines included (Workers → maximus-bot → Settings → Variables →
   Secret). Deploy the updated `worker.js`. Then check that `https://<worker>/sub.signed` returns the
   envelope.

3. **Mirrors:** on any HTTPS host that is not on Cloudflare (a VPS, object storage, another CDN), copy
   the signed file every 30 minutes. For example, on a Linux server with a web root at
   `/var/www/html`:

       mkdir -p /var/www/html/maximus
       ( crontab -l; echo '*/30 * * * * curl -fsS https://<worker>/sub.signed -o /var/www/html/maximus/sub.signed.tmp && mv /var/www/html/maximus/sub.signed.tmp /var/www/html/maximus/sub.signed' ) | crontab -

   Add a second line for `vip.signed` if VIP is used. A mirror cannot change the content (the app would
   refuse it). An old copy is refused once a newer one was seen, and every copy expires after seven
   days, so a mirror that stops updating simply stops being used.

4. **App build:** in GitHub → Settings → Secrets and variables → Actions → **Variables**, add:
   - `OFFICIAL_PUBLIC_KEY`: the content of `official-public.txt`;
   - `OFFICIAL_MIRRORS`: comma-separated base addresses, e.g. `https://mirror1.example/maximus`. The
     app appends `/sub.signed` and `/vip.signed`.

   Both values are public. The private key exists only in the Worker secret. Run the release workflow
   after step 2: from then on the app accepts only signed copies. Installs that already have the
   subscription get the mirrors at their next refresh.

Order matters: deploy the signing Worker (step 2) before releasing a build with the key (step 4).
Otherwise that build refuses the unsigned list, keeps its last known servers and waits.
