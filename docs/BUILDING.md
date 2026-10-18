# Build MAXIMUS VPN

Read [LICENSE](../LICENSE) before building or redistributing the source. The project uses a permission-based, non-commercial source-available license. Upstream components have separate license obligations in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

## Android app

Use JDK 17, Android SDK platform **36.1**, build-tools **36.0.0** and the checked-in Gradle wrapper. Minimum runtime: Android 7.0 (API 24).

```bash
bash scripts/fetch-libxray-android.sh
bash gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug
bash gradlew --no-daemon :app:assembleDebug
# Optional ARM64-only development build:
bash gradlew --no-daemon -PtargetAbi=arm64-v8a :app:assembleDebug
```

The Xray fetch script verifies its pinned runtime. Other engine executables are built separately; a debug APK without them cannot exercise those engines. See `scripts/build-engines.sh` and `.github/workflows/release.yml` for the complete native toolchains, SDK/NDK versions and all supported ABIs. Native builds are intended for Linux with the Android NDK installed and require network access to verified upstream sources.

```bash
bash scripts/build-engines.sh arm64-v8a armeabi-v7a x86_64
```

Psiphon additionally requires valid project network settings. Missing runtimes or settings must remain unavailable rather than being reported as successful connections.

## Signing and release

Release signing uses the existing project key through repository Actions secrets. Never commit a keystore, private key, provider credential or populated environment file. Configure release secrets privately; do not substitute a new signing key for an existing installation.

The release pipeline runs unit tests, release lint, APK packaging checks, signature verification, checksums and an Android 15 install/launch check. ARM64 packaging is verified independently; physical-device and carrier tests remain distinct from emulator results.

Publishing is an explicit action: dispatch the release workflow with the matching tag, or update `release-version.txt` / `release-request.txt`. Documentation and artwork edits do not publish an APK. Existing public download availability is preserved while replacements are uploaded.

## Repository checks

```bash
python3 scripts/check-repo-hygiene.py
python3 -m unittest discover -s tools/aggregator/tests
node --test tools/telegram-bot/test.mjs
```

## Screenshots

The `Repository screenshots` workflow installs the official APK, verifies its checksum and captures a clean emulator installation. `scripts/capture-screenshots.py` records original PNGs with capture metadata. No credentials or fabricated connection results are seeded. Updating that script triggers a fresh capture; the workflow can also be dispatched manually.
