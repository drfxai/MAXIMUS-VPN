# MAXIMUS Smart Manager — staged implementation and safety contract

Status: **Phase 1 / draft implementation — NOT production-ready**.

## Implemented in this PR

- Panels → Smart Manager: six compact sections, using the existing `ServersViewModel` and encrypted `ServerStore`. VPS addition, SSH refresh, per-engine installation review and Cloudflare setup launch the existing real workflows.
- Read-only candidate admission engine (`SmartManagerPlanner`). Reports installed/discovered, blocked, inspection-required, wizard-eligible and unverified states without claiming a completed deployment.
- Fail-closed port inventory: `ServerFacts` now reports whether `ss` is available; a missing socket inventory blocks new engine admission. Interface IPv4/IPv6 addresses, Docker availability and presence of a Psiphon systemd unit are inspected without installing software.
- New server survey fields round-trip through `ServerJson` inside the existing Android Keystore-encrypted server store. Legacy records safely default to unknown socket state.
- Conservative bundle validation: refuses DNS tunnel co-location on one public port 53, refuses Hiddify co-location, and refuses unimplemented private Psiphon activation.
- JVM tests cover stale inspections, externally occupied DNS ports, cross-engine conflicts, Hiddify isolation and the Psiphon verification gate.

These checks are advisory: port availability can change between inspections, so **no install can rely exclusively on a cached plan**. Existing installers remain individually responsible for rechecking host state.

## Current execution model

`Panels → Smart Manager → Server` calls the existing SSH host-key-pinned server flow. The dashboard reads authenticated survey facts and is never populated with simulated metrics. `Installation` offers only the existing single-tool approval wizard for candidates permitted by the planner. `Nodes` reports **stored profile references**, not necessarily verified tunnels; manually imported nodes are not miscounted. `Monitoring` presents the last observed systemd service state, not assumed remote egress.

`Cloudflare` uses the existing `TokenTemplate.SMART_DOMAINS` template for `zone/read` and `dns/edit` on user-reviewed zone scopes. The create-token action opens the **official** Cloudflare dashboard. It never programmatically creates a token or modifies DNS by itself. See https://developers.cloudflare.com/fundamentals/api/how-to/account-owned-token-template/.

## Phase 2 — secure transaction coordinator (NOT implemented)

Before offering a full-bundle confirmation, implement server-side durable operation IDs and journals, per-protocol/per-address socket bindings (including IPv6 v6only behavior), advisory locks with expiry, optimistic state versioning, a second preflight check and atomic service activation. Persist idempotent mutation checkpoints and inverse rollback commands. A preview must include affected systemd units, files, firewall ports, DNS changes, ownership and backup hashes. Never run curl-pipe-shell scripts or overwrite unrelated units. Remote installation requires explicit approval **after** displaying the final plan.

Do not allow unattended root recovery operations without an explicit policy. Preserve SSH and Android VPN lockdown/kill-switch behavior.

## Phase 3 — private Psiphon (NOT implemented)

The upstream Psiphon Tunnel Core repository provides a `psiphond generate` flow and a `server-entry.dat` value for client `TargetServerEntry`: https://github.com/Psiphon-Labs/psiphon-tunnel-core#running-psiphon.

Before adding Panels → Psiphon Server as an installable action:

1. Pin an upstream source commit or verified artifact SHA-256 and audit GPL-3.0 distribution obligations.
2. Build an isolated `psiphond` systemd unit with restricted filesystem permissions; select ports on the **fresh server snapshot**, never from assumptions.
3. Generate a server-specific entry and private client JSON; keep bootstrap separate from existing public Psiphon settings and never reuse third-party SponsorId or signing material.
4. Extend `PsiphonSidecar` with a tested **private mode**. The existing sidecar currently explicitly requires network settings and is therefore not proof that private bootstrap works.
5. Verify a `Tunnels` notice and actual data transfer through the intended VPS, enforce Android VPN security, and only then mark the node `Verified`/`Available`.
6. Implement safe backup, update, key rotation, removal, and recovery tests; only then enable standalone Psiphon installation.

A successful psiphond process start is not a successful client tunnel.

## Phase 4 — DNS gateway and Nodes (NOT implemented)

Do not assume CoreDNS forwarding supports every DNSTT, SOAR, CottenDNS and CottenMax transport. First validate authoritative delegation, protocol-specific DNS packet payloads, truncation, EDNS behavior, fragmentation, rate limits and real concurrent sessions. If any engine fails, assign a separate public IP or VPS; never share public 53 by silently intercepting traffic. Hiddify remains isolated on a secondary server.

After successful transport validation, add a lifecycle-aware `Nodes` reconciler with provenance (VPS/service/config version), credential-safe import, syntactic checks, phone-based traffic validation, deduplication and revocation. Integrate only verified nodes into Smart Connect. Include Wi-Fi/cellular-specific history and common VPS failure-domain awareness.

## Verified local checks (source-exact, not Android integration tests)

- The exact Git blob `ServerFacts.kt` (`dbea4c665727ee9167195e6fceb11b49b38933f6`) compiled with the available Kotlin 1.9 compiler; its extracted SSH inspection script passed `sh -n`.
- The exact Git blob `SmartManagerPlanner.kt` (`dbc10f8dddeb5dc998d8479b0c5d518da6d0f204`) compiled alongside `ServerFacts`, `ManagedServer` and `ServerToolCatalog` in a standalone JVM smoke harness.
- Six smoke assertions passed covering single DNS admission, unavailable socket inventory, occupied UDP 53, DNS bundle rejection, Hiddify isolation and private-Psiphon gating.
- These are **not** substitutes for Android Gradle CI, the JUnit suite, a real VPS, or a real VPN tunnel. No emulator screenshot was taken.

## Release acceptance matrix

| Area | Phase 1 status |
|---|---|
| Six Smart Manager UI sections | Implemented in source; emulator rendering not yet verified |
| SSH host-key pinning and existing installer navigation | Reuses existing workflows; live VPS test not run |
| Port 53 admission and conservative bundle refusal | Covered by added JVM unit tests (CI result pending) |
| Cloudflare token template link | Existing integration reused; dashboard click not device-tested |
| DNS record mutations | Not performed |
| Psiphon standalone install | Not implemented |
| Private Psiphon client bootstrap and traffic test | Not implemented |
| Multi-engine DNS gateway compatibility | Not verified — deployment blocked |
| Automatic Nodes sync and Smart Connect eligibility | Not implemented |
| Android build, lint, instrumentation | CI/emulator evidence required before release |
| Actual phone/foldable/tablet screenshots | Not yet produced |

**Approval policy:** A draft PR is a review artifact only. Do not merge, release an APK, change live Cloudflare DNS or install on any user VPS without separate explicit consent. All test results must be reported from actual workflow runs or devices; missing evidence must remain marked unverified.
