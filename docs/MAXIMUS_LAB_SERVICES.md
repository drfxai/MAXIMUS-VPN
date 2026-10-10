# LAB: Service check

LAB's **Service check** page opens the sites people actually use and reports what came back. A ping or a 204 test can pass on a path where Gemini refuses the exit's country or the filter's page answers instead of YouTube; this page catches those.

## What it checks

Gemini, Google AI Studio, ChatGPT, YouTube, Telegram Web and X. Each gets one ordinary GET of its front page (browser User-Agent, redirects followed, 10 s connect/read timeouts). The exit address and country come from Cloudflare's `/cdn-cgi/trace`.

## Verdicts

| Verdict | When |
|---|---|
| Filtered | the answer came from `peyvandha.ir` or 10.10.34.34-36, or the page embeds it |
| Region blocked | HTTP 451, region wording in the page title or in an error answer, or a redirect to a `sorry` / `unsupported` page |
| Works | HTTP 2xx-3xx, or a Cloudflare bot check (the site was reached) |
| Refused | 403 or another error status |
| No connection | the request failed (timeout, reset, DNS) |

Region wording inside a working page does not count, because the sites ship those strings in their bundled translations.

## Limits

- The check runs from the app. With the VPN on it goes through the connected config when Xray runs it; configs run by a separate engine (Psiphon, Tor, DNS tunnel) leave the app's own traffic outside the tunnel, so the check then shows the direct network.
- It is signed out. A site that decides the region from the account (Gemini can) may still differ for the user.
- Nothing about the user or their configs is sent; results stay on the phone.

## Files

- `vpn/lab/ServiceCheck.kt`: services, verdicts, the run.
- `ui/lab/LabServices.kt`: the page.
- `NetworkLabViewModel.checkServices`: the OkHttp wiring.
- Tests: `ServiceCheckTest`.

## Not verified

Not tested on a phone or against the real sites from Iran.
