# Mode switching and DNS-engine restart repair

Baseline: main fa9a75b3a4e28685cf91dcedc5cd025b93fb8f45, versionName 1.0.2 / versionCode 37. Baseline Checks 40196788688, transport checks 40196788536 and release 40196788574 succeeded. Version and signing are unchanged.

## Confirmed defects and changes

- A live mode change called the entire connection search and stopped the working sidecar. Reviewed standalone Tor, SOAR and CottenDNS adapters now retain their live process, loopback credentials and carrier when the profile and network match a connected session. Xray/TUN routing is rebuilt under the new mode and traffic is freshly checked. This is NOT zero-packet-loss hot reload: a short protected forwarding interruption remains. Unknown engines, chains, changed networks/profiles and dead/unverified engines cannot use this optimization. Entering Ghost Rider through a non-Tor engine retains the existing full selection path.
- SOAR and CottenDNS rejected an existing config file, breaking repeated preparation in the persistent service work directory. Configs are now written to owner-only temporary files, synced and renamed. Stale symlinks are refused. Both adapters have a second-start regression test with changed local credentials and port.
- Tor bridge DNS used GOD_MODE equality and missed GHOST_RIDER. It now uses the common operating-mode policy.
- Sidecar logging truncated before redaction; redaction now precedes display truncation. Panel error/log output also goes through the common redactor.
- LAB auto-application now checks the current session and physical network again before staging a verified candidate. This is a boundary guard, not a claim of global atomic network-generation protection.
- A delayed post-connect subscription refresh now checks its originating attempt ID.

## Review scope and limits

Read current service/Smart Connect flow, failover manager, LAB controller, panel manager, common sidecar lifecycle and Tor/SOAR/CottenDNS adapters. This is not an exhaustive audit of every engine or server installer. No guarantee of zero bugs is possible.

Remaining: actual per-bridge cooldown/circuit-aware scheduling, unified watchdog decisions, exact Android TUN and external DNS leak proof, native-engine crash/device tests, all-engine mode-transition matrix, panel install/rollback on real servers, UI benchmarks. HTTP 503 remains a subscription-service fault requiring its own investigation. Log aggregation does not suppress Tor's retries.

## Validation

New unit coverage exercises both DNS-engine repeated preparations and mode-reuse refusal conditions. CI results must be read from the final PR head; no device, field, censorship or continuity timing results are claimed. The user's 138638ms mode-switch report is field evidence of the original defect, not a measurement of this patch.
