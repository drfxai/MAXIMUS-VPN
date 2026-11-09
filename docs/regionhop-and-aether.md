# Aether account HTTPS and RegionHop

Aether's native TLS handshake can fail even when Android TLS reaches the same
Cloudflare API via Direct OOB. Auto/OOB account requests now use a per-session,
authenticated loopback adapter to Android TLS over that same SOCKS route. The
adapter accepts only the two Cloudflare account hosts and registration paths,
keeps CA/hostname verification, and forwards HTTP status/body/Retry-After without
redirecting or replaying requests. Explicit ECH remains native. WARP carrier
traffic never uses this account adapter.

Reference comparison: PattN `my-releases` AetherArguments.cs and PattNG
`my-releases` AetherCoreManager.kt/AetherIdentityManager.kt, whose Aether submodule
is patterniha/Aether d58f9d568083129ce2c767c85054021eb3ee6a38. Carrier-specific TLS
options and explicit two-hop scans follow those protocol distinctions. This
implementation retains MAXIMUS certificate verification and does not adopt the
upstream account client's unconditional disabled verification.

RegionHop is optional after 3X-UI, reachable from the server detail page and
Smart Manager Services. It installs v1.27.0 from reviewed source commit
`d5798d80f77fa6f40406ba30886d810c3c462147`; scripts, units and release bundle hashes
are pinned in `assets/regionhop/pins.json`. The installer refuses existing
installations, binds the panel to loopback and controls it over pinned SSH.
Legacy range-wide firewall migration/deletion is omitted on a fresh installation
so unrelated rules remain. RegionHop's dedicated per-location SOCKS chain is used.

Import Psiphon.config with the file picker: it is validated and encrypted on the
phone. Installation approval sends it to the chosen VPS over SSH. Deployment
identifiers, API tokens and config files are not included in repository assets.
The same imported settings supply the Android Psiphon client. Runtime-owned
listener, platform and upstream fields override imported desktop settings.

Create a country location, use its start/stop/restart/test/remove controls, then
synchronize `regionhop-*` outbounds using the same VPS loopback 3X-UI URL and an
API token. The user assigns inbounds/users in 3X-UI Routing; existing user routing
rules are not rewritten. A server proxy test does not prove Android tunnel
traffic or availability of every advertised country.

Private psiphond provisioning additionally requires the service's MainPID to own
the selected TCP listener. Active UFW installations get an installation-specific
rule which is removed on rollback/uninstall; existing allow rules remain.
External cloud firewall reachability still requires measurement from the phone.

Validation includes authenticated adapter/header/body tests, TLS trust/hostname
rejection tests, RegionHop HTTP control tests and an ephemeral systemd panel
installation. The latter uses a prerequisite executable fixture, not a full
3X-UI deployment or real Psiphon country connectivity. Physical Irancell traffic
and personal VPS deployment require device/server validation.
