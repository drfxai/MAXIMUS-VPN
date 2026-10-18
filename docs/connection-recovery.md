# Connection recovery in Android build 31

This update addresses failures observed on an Android 15 mobile-data connection.

- A successful tunnel request remains healthy even when it exceeds the preferred latency. Three failed traffic checks still trigger recovery. A slow working route is no longer discarded solely for its latency.
- Fresh successful path evidence takes precedence over choosing a different protocol family. A selected fallback is described as a candidate until its traffic is verified.
- Recovery has a five-minute total search budget. Each Tor attempt can use up to three minutes, including bootstrap and repeated traffic verification. Other engine attempts are bounded to 90 seconds. Cancellation still stops the search.
- Isolated engine tests respect each engine's startup timeout instead of imposing a 45-second cap. Timeout results identify the attempted engine and are recorded as unsuccessful attempts.
- Direct WebTunnel bridges use poison-resistant endpoint resolution. The pinned lyrebird client's `addr` option changes only the dial destination; the HTTPS URL, HTTP Host, TLS server name and certificate verification remain intact. Private block-page answers are rejected. Explicit user `addr` overrides are retained; chained transports keep upstream DNS resolution.
- Bridge processes are cleaned up on startup interruption and on timeout. Cleanup preserves the interrupt flag, and the startup-output queue is bounded and released after negotiation.
- Verified connections update network memory, including manually selected Tor routes, so earlier failed probes do not keep the assessment at “nothing worked”.

A successful build cannot establish that a public bridge is reachable from every carrier. Snowflake rendezvous and bridge availability still depend on the network. Subscription HTTP 503 is a separate server-side failure; no client update can promise that the subscription service is available. Psiphon still requires valid authorized configuration. The unauthenticated emergency bridge and LAN mesh features remain disabled.

The WebTunnel `addr` behavior is checked against the pinned lyrebird revision `75ef9b2c1f18`, `transports/webtunnel/client.go`: `RemoteAddress` is overridden independently of the URL-derived TLS and HTTP identity.
