# Panel and DNS field recovery — 26 October 2026

## Evidence

Build 94 user reports: installer-produced Cotten links rejected by Add Node; Hiddify files detected but import said not found; no prominent removal action; SOAR dependency install failed on an apt lock; installed DNS tools sometimes displayed pre-install stopped state. SOAR/Cotten real traffic is not verified. A later service failure remains possible and requires current server diagnostics.

Tor Snowflake carried real requests. Initial connection took 461253 ms; four mode changes preserved the Tor process and reverified in 2235–2689 ms. These are warm routing rebuilds, not cold-connect benchmarks or proof of lossless transitions. Two later health failures recovered without automatic failover. Worker subscription HTTP 503 is separate. Android TUN end-to-end, runtime DoH and external DNS leak observations remain unmeasured.

## Change

- Accept native Cotten method-5 WhiteDNS keys of exactly 32 or 64 hexadecimal characters, preserving credentials; support a single line-wrapped SOAR/Cotten share link without joining multiple links.
- Refresh service facts after DNS/Hysteria installation; failed refresh becomes unknown. Offer allowlisted DNS service start/stop and current systemd state plus bounded journal diagnostics after a failed phone test.
- Dependency installs wait up to 120 seconds for apt's native dpkg lock. Lock failures do not delete locks, kill package processes or launch an update retry. Require three consecutive active observations before server startup verification.
- Distinguish Hiddify files without registered units from absent files and SSH inspection failure. Include disabled Hiddify units in status. Expose diagnostics and removal even before importing detected files.
- Non-purging Hiddify removal stops/disables only Hiddify units and retains data. Without units, incomplete files are moved to a recoverable backup only when application/configuration data guards pass. No partial remote uninstaller is executed. Automatic purge is unsupported.
- Separate native process exit, missing readiness marker and proxy socket timeout; these diagnostics do not assert DNS or egress success.

## Validation and limits

Local synthetic shell execution passed apt-lock/no-update handling, partial Hiddify quarantine and data-preservation refusal. New JVM regressions exercise actual Add Node import round trips, invalid keys, Hiddify partial status/import, post-install facts, transient service crash, disabled units, and scoped removal. Android unit/lint results must be read from the corresponding PR checks before merge. No VPS mutation or physical-device verification has been performed by the assistant; installation, DNS delegation, resolver transport and authenticated end-to-end traffic must still be checked on the user's server/phone. No app version/code, signing identity, native source pin, TLS verification policy or production Worker configuration changes.

## Final source validation

PR131 head 85d6338015c74db8cf2b8a9fe5dda27033b4333e passed PR Checks 40336629087, push Checks 40336621118 and transport recovery 40336629147 / 40336620805. Checks include Android unit tests and lint, pinned server-tool build, Slipstream loopback traffic, bot/aggregator tests and hygiene. Hiddify post-install facts are also refreshed and regression-tested. Squash merge c020af02e2789dcef1d8beb9d380476aa10ef8a4 has exactly the tested tree 399ea4c05024a5257c08590893f637c69896e81d.

At 14:09 UTC, Google Public DNS returned A ns.f22raptor.online = 104.207.159.222, while NS t/tx.f22raptor.online returned SERVFAIL with a nameserver-no-response diagnostic. This is resolver-side evidence, not proof of packets reaching the VPS or a complete delegation diagnosis. No records were modified.

Signed release is requested separately; publication, packaging, signature and Android15 emulator results must be checked in that release run. Real VPS/phone SOAR and Cotten traffic remains unverified.
