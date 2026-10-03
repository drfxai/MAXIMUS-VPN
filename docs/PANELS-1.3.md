# Panels, Cloudflare Worker Wizard and Clean IP dashboard — 1.3.0

## UI architecture

The Panels destination is split into three compact surfaces:

- **Servers** — catalog-driven server-panel installation and management. 3X-UI is the
  first installer. Saved panels expose installation state, access information,
  authenticated health metrics and one-click Reality configuration creation.
- **Cloudflare** — BPB-style Worker wizard. A Cloudflare bootstrap token created from
  the **Create Additional Tokens** template is used only to create a scoped 30-day
  deployment token. Maximus then creates the KV namespace, deploys the BPB Worker,
  publishes the workers.dev endpoint and stores the resulting private panel metadata
  and management token encrypted with Android Keystore.
- **Clean IP** — live Cloudflare edge scan with progress, ETA, current candidate,
  pause/resume/stop controls, rolling logs and ranked results. Every result can be
  added as a new preserved profile or added and connected immediately when Android
  VPN permission is already granted.

The installer surface uses `PanelCatalog` so future server panels or Cloudflare
applications can be added without redesigning the navigation and dashboard shell.

## Cloudflare token flow

Cloudflare does not allow an application to mint the first API token without an
already-authorized bootstrap token. The user creates that one bootstrap token in
Cloudflare using the **Create Additional Tokens** template. Maximus never persists
the bootstrap token.

The smart installer creates a child token with these permissions scoped to the
selected account:

- Workers Scripts Write
- Workers KV Storage Write
- Account Settings Read

The child token expires after 30 days. It is stored only inside encrypted Android
storage and, as required by the current BPB upstream design, embedded in the private
Worker settings used by BPB administration. DNS Write, Cloudflare Pages Write and
other broader privileges are deliberately not requested by default; future catalog
items that require them should request separate explicit scopes.

## Clean IP behavior

The scanner still accepts only Cloudflare-backed VLESS WebSocket/TLS profiles with
explicit Host and SNI. It samples at most 500 unique addresses from Cloudflare's
published IPv4 ranges and performs three transport probes per candidate, four
candidates concurrently. A candidate needs at least two successful probes.

The dashboard now exposes:

- tested/total progress and ETA
- current candidate address
- reachable count
- pause/resume and stop
- rolling diagnostic log
- ranked latency/jitter results
- **Add** to preserve the original config and save an IP variant
- **Add & Connect** to save/select the variant and start the VPN when permission is
  already granted
- bulk save of ranked candidates with the best result selected

A network change or VPN activation invalidates the scan. Results expire after ten
minutes. This is transport reachability/latency testing and is not a censorship,
reputation or end-to-end authentication guarantee.

## Home telemetry

The first screen no longer shows the previous multi-line live-connection card. While
connected it shows only a compact live indicator containing the VPN-observed country
flag, two-letter country code and current HTTPS ping. This preserves the requested
live information without crowding the home screen.

## Security notes

SSH panel installation continues to require a pinned SHA-256 host key. SSH passwords
are not persisted. Panel credentials and management tokens are stored through the
existing Android Keystore-backed secure storage.

Public-IP and DNS leak prevention depends on complete tunnel capture and Android
Always-on VPN / Block connections without VPN. A VPN cannot hide a device MAC address
from the local Wi-Fi or Ethernet link because MAC addresses are local link-layer
metadata.
