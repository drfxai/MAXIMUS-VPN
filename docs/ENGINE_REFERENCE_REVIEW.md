# Engine and connectivity review

The Android client keeps its own VPN service and uses its existing native engines and profile formats. The following upstream sources were checked for the parts relevant to MAXIMUS.

| Reference | Use in MAXIMUS |
| --- | --- |
| [Serverless-for-Iran](https://github.com/patterniha/Serverless-for-Iran) | The v65 profiles and the matching Patterniha Xray runtime v26.11.23 are pinned (first added in #237). Direct mode keeps its explicit selection because its exit is the user's ISP. |
| [PattN](https://github.com/patterniha/PattN) and [PattNG](https://github.com/patterniha/PattNG) | References for per-profile core selection and separating native transport from the client. MAXIMUS retains its existing Xray and sing-box adapters. |
| [WhiteAestherMobile](https://github.com/WhiteDNS/WhiteAestherMobile), [WhiteAesther](https://github.com/WhiteDNS/WhiteAesther) and [RIX-AETHER](https://github.com/RIX-REZA/RIX-AETHER) | References for adaptive transport selection, remembered routes and managing dependent hops. MAXIMUS reuses fresh verified-engine evidence and retains the chain's requested mode during probes. A result from an old network cannot authorize the early Aether handoff. |
| [WhiteDNS-Android](https://github.com/WhiteDNS/WhiteDNS-Android) and [CottenDNS](https://github.com/WhiteDNS/CottenDNS) | Android behavior is a reference. The separate CottenDNS CLI moves to `ac8e86ed83a5006ab320468daaebe759d9574da6` for DNS cache and UDP lifecycle fixes. The strict DoH/DoT patch remains in the build. |
| [Soar-Server](https://github.com/WhiteDNS/Soar-Server) | The server-panel workflow is a reference for testing actual traffic and maintaining the endpoint contract. MAXIMUS continues to use its existing Soar adapter and panel tooling. |
| [ZedSecure v3.1.8](https://github.com/CluvexStudio/ZedSecure/releases/tag/v3.1.8) | Reference for keeping each profile's settings with its chosen core. Automatic recovery now respects a subscription's automatic-selection exclusions. |
| [LML-VPN v1.0.2-preview](https://github.com/nukesamp-crypto/LML-VPN/releases/tag/v1.0.2-preview) | Reference for Psiphon integration. With the current link's UDP still usable or unmeasured, Smart Connect lets Psiphon select a transport. A measured UDP failure selects the TCP-only preset. |
| [UAC-SNI-Spoofer-Android](https://github.com/Floxu1/UAC-SNI-Spoofer-Android) | MAXIMUS already has an attributed Matrix integration. The short recovery round now accounts for the engines inside a chain, so two candidates sharing one engine cannot consume two slots. |
| [edgetunnel](https://github.com/cmliu/edgetunnel) | Server-side WebSocket/VLESS profiles are handled through the existing client importer. This change does not deploy a Worker. |
| [TWP](https://github.com/Qorvhex/TWP) | Telegram's MTProto proxy is app-specific. It is outside the system-wide VPN recovery pool. |
| [Aras-GP](https://github.com/ArasTey/Aras-GP) and [Hashem Panel](https://github.com/pdnczone/hashem-panel) | Server-management references for scoped health evidence. Recovery probes now discard ledger updates when the network differs from the one they started on. |
| [Byp](https://github.com/devlsam/Byp) | Reference for network-change handling and separate per-app routing. Its Windows runtime is platform-specific. |

Lantern / Radiance is now an independent candidate in Smart Connect recovery when the runtime is installed. It must pass the same application traffic checks as the other engines.
