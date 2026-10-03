# DNS testing in Logs

The Logs screen offers a local VPN DNS path probe. It requires CONNECTED state,
locates a VPN network with our TUN address, binds an unprotected diagnostic socket
to that network, and sends a random-name DNS question to 172.19.0.2:53. No default
network or public resolver fallback is attempted. The response must match the
transaction ID and complete question; truncated/error responses are inconclusive.
A random label under example.com avoids reusing a previous probe's cached answer.
The latency and time of the test appear in the diagnostic export. Results are
invalidated after connection/session/settings changes. If Android prevents this
app from accessing its VPN network, the test reports inconclusive rather than
probing the ISP. The app's own VPN exclusion can make this test unavailable.

This is local path evidence only: it cannot identify the internet-side recursive
resolver, certify that DoH was used, or rule out other applications' DNS leaks.
Successful IP-location requests cannot answer these questions; the old ipinfo
"isProtected=true" test has been removed.

## External observer required for an actual resolver test

A future observer must operate an authoritative DNS zone plus an authenticated
HTTPS API. The app obtains a short-lived random session and unique delegated
hostnames, resolves A/AAAA through the VPN-bound system DNS path, then polls the
observer over the same VPN network. The observer returns recursive resolver source
addresses seen for those names, observation times and query types. Sessions must
expire, be scoped to their nonce, limit request counts and avoid retaining logs.
The app must disclose the observer's endpoint and that test queries are sent there.
No credentials, app logs or profile endpoints should be uploaded.

Only observed queries count as evidence. No observations, timeouts, reconnection,
unsupported API or malformed responses are inconclusive. A configured provider URL
or resolver ASN alone is insufficient to classify traffic as safe. Compare observed
sources with the documented expected resolver policy; display unexpected sources
for review rather than claiming an ISP leak based on an unverified organization
name. An observer sees recursive egress, which may include forwarding chains; it
cannot prove absence of every leak or inspect arbitrary traffic from other apps.
Device uplink packet capture remains the separate validation for transport leaks.

No external observer is deployed or configured by this change. Internet-side leak
status and DoH runtime verification deliberately remain unmeasured.
