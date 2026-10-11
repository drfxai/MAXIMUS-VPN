# Optimal Node agent & Trade mode

Two connection intents sit side by side with Daily and GOD modes: an agent that gets you onto the
best working exit with one tap, and a mode that holds one exit perfectly still.

## Optimal Node agent

The home card ranks the saved servers by the active scoring profile (speed, latency, stability, packet
loss) and shows the **top three**, best first. One tap on **Switch to Optimal Node** connects to #1 and
then lets the connection look after itself:

1. **Connect to the best.** `SmartConnect.rankTopNodes` orders the healthy, supported nodes; the first
   is tried with a real request before the tunnel starts, so a dead one is never started blindly.
2. **Fall over in rank order.** The existing watchdog (`FailoverManager`) watches the live tunnel with
   real requests and, if it is disrupted or slow, switches to the next ranked node, then the one after.
3. **Revive as a last resort.** When every saved server and the built-in recovery engines fail, the
   agent runs **LAB Revive** (`ConfigRevival`) over the dead Cloudflare-fronted configs — ECH, fragment,
   fingerprint and clean-IP recipes — and connects whatever comes back. The winning recipe is saved to
   the recovery ledger, so the next connect uses it first. This step is time-bounded (`CONFIG_REVIVE_MS`)
   and, because each test is a real request that needs the core free, it briefly holds traffic while it
   works.

All of this is driven by the one button; the card shows the shortlist and the connection reports which
node it is on and why.

### Where it lives
- `vpn/smart/SmartConnect.kt` — `rankTopNodes(profiles, scoringProfile, count)` and `selectBestNode`.
- `ui/viewmodel/VpnViewModel.kt` — `topNodes` flow feeds the card and the shortlist.
- `ui/home/HomeScreen.kt` — `SmartRecommendationCard` shows #1 plus the fall-over order.
- `vpn/RayVpnService.kt` `connectLocked` — the connect ladder, with the new config-revival tier before
  the path gate.
- `vpn/smart/FailoverManager.kt` — the live-tunnel watchdog and cascade (unchanged here).

## Trade mode

Trading and similar uses need a **constant identity**: the same exit IP, location and configuration
every time. Trade mode is a third `OperationalMode` that pins the chosen config and turns off everything
that would move the exit:

- no smart racing, no auto-failover, no stealth alternates, no clean-IP swap, no LAB recovery;
- the connect path uses only the selected server (`OperatingModePolicy.lockExit` gates every switching
  step in `connectLocked`, and `apply()` forces `autoFailoverEnabled = false`);
- the home card becomes a **Trade card** showing the pinned server, the fixed-exit guarantee, and the
  steadiest configs to pin.

When the user adds their own server, its stable, high-quality form is offered as a Trade-locked profile
(and, using the LAB service check, a form verified to reach AI services such as Gemini and OpenAI).

### One honest limit
An Android app-level VPN **cannot read or change the device MAC address** — the OS does not expose it to
apps — so the MAC never changes under any mode, and Trade does not claim to control it. Trade guarantees
the parts the app does control: a fixed exit IP, a fixed exit location, and a fixed configuration with no
rotation.

### Where it lives
- `data/model/AppModels.kt` — `OperationalMode.TRADE`.
- `vpn/safety/OperatingModePolicy.kt` — the `TRADE` policy with `lockExit = true`.
- `vpn/RayVpnService.kt` `connectLocked` — `!policy.lockExit` guards on the race, recovery-copy, stealth
  and revival steps.
- `ui/home/HomeScreen.kt` — the three-way mode selector and `TradeModeCard`.

## Not verified
- Nothing here has been run on a phone. The ranking, the Trade policy and the mode gating are unit-tested
  only (`SmartConnectRankTest`, `OperatingModePolicyTest`); the connect-path and UI changes are not
  exercised by the JVM tests.
- The config-revival tier reuses the LAB Revive engine, which has not been verified against real
  Cloudflare or in Iran (the cloud build machine cannot reach Cloudflare).
