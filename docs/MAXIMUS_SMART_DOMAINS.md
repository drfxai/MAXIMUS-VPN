# Smart domains: your own Cloudflare subdomains for your servers

Smart domains give each of your servers a name on **your own domain** in **your own Cloudflare account**.
Each name gets the right Cloudflare setting for its connection type. The app makes the DNS records
through the Cloudflare API with a token you create. Nothing is made on anyone else's domain.

Where: **Panels → Cloudflare → Smart domains**. Without a token the screen shows how to make one and a
**Check token** button. Once the token works it shows your zone, the names you made, and a form for a new
name. **Forget token** deletes the token from the phone; the names stay in Cloudflare.

Code: `panels/cloudflare/SmartDomains.kt` (rules, pure), `panels/cloudflare/CloudflareDnsApi.kt` (API calls),
`panels/cloudflare/SmartNames.kt` (the saved names and the screen's text, pure), `panels/cloudflare/SmartDomainsStore.kt`
(token and names on the phone) and `ui/panels/SmartDomainsScreen.kt` (the screen).
Tests: `SmartDomainsTest`, `SmartNamesTest`.

The list of names is kept on the phone only: zone, full name, record type, address, port, use and proxy
setting. It holds no secrets. The app cannot read the zone's SSL/TLS mode with this token, so the screen
asks you to set Full (strict) yourself and never shows it as checked.

## Using domains safely

| Rule | Why |
|---|---|
| Use a domain you own, in your own Cloudflare account. | You control its records and certificates. The app never creates names on other people's domains and never sends a fake server name. |
| Give each use its own subdomain (one for CDN inbounds, one for REALITY, one for the DNS tunnel). | A name that gets blocked or reported takes only one service with it. You can retire it without touching the others. |
| **CDN names are proxied (orange cloud).** WebSocket, XHTTP, gRPC and HTTPUpgrade over TLS go through Cloudflare. | The server's address is not published in DNS. Clients reach Cloudflare's edge, and only Cloudflare reaches your server. |
| **Direct names are DNS only (grey cloud).** REALITY, Hysteria2, TUIC, WireGuard, SSH and the DNS tunnel's `ns` record. | Cloudflare cannot carry these protocols, so proxying them breaks them. A direct name publishes the server's address. Do not put a direct name and a CDN name on the same server if you want that server's address hidden. |
| CDN names only on Cloudflare's HTTPS ports: 443, 2053, 2083, 2087, 2096, 8443. | Cloudflare's proxy drops TLS on any other port. The app refuses other ports for CDN names. |
| Set SSL/TLS mode to **Full (strict)** on the zone, with a valid certificate on the server (Let's Encrypt or a Cloudflare Origin certificate). | "Flexible" sends traffic unencrypted from Cloudflare to your server. "Full" without "strict" accepts any certificate. |
| Labels are hard to guess (`cdn-k7m2qa`). The app suggests one and you can type your own. | Common names (`vpn`, `proxy`, `v2ray`) are the first ones scanners try. |
| No wildcard records. | A wildcard answers every name and hides mistakes. The app refuses `*`. |
| The token can only read zones and edit DNS, on the one domain you choose. | A leaked token can then only change that domain's DNS. It cannot touch Workers, other domains or the account. |
| Existing records are never overwritten. | If a name already exists with another address, as a CNAME, or as several records, the app reports a conflict and changes nothing. It changes a record only when it already points at the same server, and then only the proxy setting. |
| Removing deletes only the record the app made, and only if it still points at the same address. | A name you changed by hand afterwards is left alone. |

What the app does not do: domain fronting, using someone else's domain or Cloudflare account,
scanning Cloudflare for addresses, or turning off certificate checks. Clean-IP features that already
exist are unchanged.

## Connecting your domain to Cloudflare (once)

1. In the Cloudflare dashboard choose **Add a domain** and enter the domain you own. The Free plan is enough.
2. Cloudflare shows two name servers. At your registrar, replace the domain's name servers with those two.
   The zone becomes **Active** once the change spreads, usually within a few hours.
3. Under **SSL/TLS → Overview**, set the mode to **Full (strict)**.
4. Optional: under **SSL/TLS → Edge Certificates**, turn on **Always Use HTTPS** and set the minimum TLS
   version to 1.2.

## Making the API token

Smart domains makes the token in three taps:

1. **Create token.** **Create token in Cloudflare** opens Cloudflare's own token template page with
   everything filled in: the name **Maximus smart domains**, the permissions **Zone · Read** and
   **DNS · Edit** (permission group keys `zone` read and `dns` edit), and your zones as the resources.
   On Cloudflare you tap **Continue to summary**, then **Create Token**, and copy it.
2. **Paste it back.** When you come back to Maximus, it looks at the clipboard. If it holds something
   shaped like a Cloudflare token (40 characters of `A–Z`, `a–z`, `0–9`, `_` and `-`), it offers
   **Use copied token** with only the last four characters shown. One tap checks it. You can still
   paste it yourself with **Paste it yourself**.
3. **Confirm.** Once the token works, Maximus fills in the rest from the config selected in Nodes:
   - the domain: the only active zone, or the one you pick when the token sees several;
   - the server address and port from the config (the record is `A`/`AAAA` to that address, and the
     config keeps its own port);
   - the Cloudflare setting from the protocol: proxied for WebSocket, XHTTP, gRPC or HTTPUpgrade over
     TLS on one of Cloudflare's HTTPS ports; DNS only for REALITY, Hysteria2, TUIC, WireGuard, SSH and
     anything else;
   - a random, hard-to-guess subdomain (tap **change** for another).

   **Confirm** creates it the same way as the form below, so an existing record is never overwritten.
   **Edit details** opens the full form with these values. When no config is selected, or its
   address is a host name rather than an IP address, the form opens straight away.

Cloudflare does not let any app create a token by itself, so the last tap on Cloudflare's own page is
always yours. The token is kept encrypted on the phone, the same way as the Worker token. It is never
logged and never sent anywhere but `api.cloudflare.com`.

To make the token by hand instead: **My Profile → API Tokens → Create Token → Create Custom Token**,
permissions **Zone → Zone → Read** and **Zone → DNS → Edit**, zone resources **Include → Specific
zone →** your domain. You can also limit it to your own IP address and give it an expiry date.

The Worker token that Panels already uses has no DNS permission, so it cannot create these records.
That separation is deliberate.

## Making a smart subdomain

For each name, the app does the following:

1. Lists the zones the token can see and picks the one the name belongs to. The longest matching
   suffix wins, and active zones come first.
2. Checks the label: a single label of a–z, 0–9 and `-`, with no dots or wildcards.
3. Picks the record from what the name is for:
   - **Through Cloudflare (CDN):** `A`/`AAAA` record to your server, proxied. The port must be one of
     Cloudflare's HTTPS ports.
   - **Direct:** `A`/`AAAA` record to your server, DNS only.
   - **DNS tunnel name server:** `A`/`AAAA` record, DNS only. The `NS` delegation is the existing DNS
     tunnel wizard (`DnsDelegation`).
4. Reads the records already at that name, then either creates the record, changes only the proxy
   setting of the same record, leaves it as it is, or reports a conflict.
5. Records it creates carry the comment `Maximus`, so you can recognise them in the dashboard.

Proxied names take effect within a minute. DNS-only names can take up to the old record's TTL.

For Cloudflare Worker panels (BPB), use **Custom domain** on the panel card. That route is
unchanged: Cloudflare creates the record and the certificate for the Worker itself.

## When a name stops working

- First, run Smart Connect. It tries the remembered route, disguises and clean addresses before
  anything else.
- If only one name is blocked on a network, make a new label for the same server and switch the
  config to it. Remove the old name, or keep it for networks where it still works.
- If the server's address itself is blocked, a direct name cannot help, because any new name points at
  the same address. A CDN name on the same server can still work, because clients only reach Cloudflare.

## Not tested yet

The API calls are tested against a local mock of Cloudflare's API, not a live account. Nothing here has
been run on a phone.
