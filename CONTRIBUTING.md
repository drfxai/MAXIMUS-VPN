# Contributing

Thank you for helping improve MAXIMUS VPN. Read [LICENSE](LICENSE) first: public source access does not grant general permission to reuse, modify or redistribute the code. Obtain the required permission before contributing code or distributing modified builds.

## Report a bug

Search existing issues, then include the application version/build, Android version, device, network type, exact steps and the observed result. Distinguish a failed connection from a check that could not run. Remove private keys, tokens, subscription URLs, account details and server credentials from logs. Report vulnerabilities privately through [SECURITY.md](SECURITY.md).

## Propose a change

Keep changes focused. Explain the concrete problem, resulting behavior and checks performed. Preserve Android signing identity, user profiles, cancellation/cleanup behavior and authenticated transport validation. A handshake or open TCP port is not sufficient evidence of a working route.

Use the Gradle wrapper and run checks appropriate to the change; see [BUILDING.md](docs/BUILDING.md). Keep local build outputs, diagnostic logs, temporary migration scripts and signing material outside version control. Update the relevant user guide when behavior changes.

Screenshots must come from the application. Clearly separate promotional artwork from original captures and do not insert invented connectivity results, speed figures or certifications.
