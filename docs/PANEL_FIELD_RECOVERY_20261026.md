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
