# Public release checklist

## Required before publishing v1.0.0

- [ ] Choose and add the project license. The third-party notices do not license the application source.
- [ ] Add `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, and `RELEASE_KEY_PASSWORD` as GitHub Actions secrets.
- [ ] Confirm the keystore contains alias `upload` and matches the certificate used for any build that must update in place.
- [ ] Run Android CI successfully on `main`.
- [ ] Test the signed universal and ARM64 APKs on physical Android devices, including VPN traffic, DNS/IPv6 leak behavior, reconnect, failover, and always-on lockdown.
- [ ] Review the documented beta limitations in `README.md` and `SECURITY.md`.
- [ ] Run the **Signed Android release** workflow with tag `v1.0.0`.
- [ ] Download the release assets and verify `SHA256SUMS` independently.

## Recommended repository settings

- Enable private vulnerability reporting and Dependabot security updates.
- Protect `main`; require the Android CI check and reviewed pull requests.
- Prevent force pushes and branch deletion on `main`.
- Require approval for workflow runs from first-time contributors.
