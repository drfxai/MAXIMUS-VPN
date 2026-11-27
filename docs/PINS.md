# Pinned native engine versions

`sing-box` is a separate GPL-3.0-or-later program downloaded at build time by
`scripts/engines/singbox.sh` and copied to `app/src/main/jniLibs/<abi>/libsingbox.so`.
No binary is committed. The pin in that script **is** the source of truth; this file records
the current and previous pin so a bad release can be rolled back without guesswork.

## sing-box

| release | role | ABI | upstream asset | SHA-256 |
|---|---|---|---|---|
| `1.14.5` | current | `arm64-v8a` | `sing-box-1.14.5-android-arm64.tar.gz` | `bab11cbc7bb26a51ee25c993ed1b477047ed717489035d6de05cf8bbc9b70787` |
| `1.14.5` | current | `armeabi-v7a` | `sing-box-1.14.5-android-arm.tar.gz` | `1df06beb61c3bd83213db62239dca28e0c4d524f9b2c3a2638db60da38d5d588` |
| `1.14.5` | current | `x86_64` | `sing-box-1.14.5-android-amd64.tar.gz` | `89715aee160c9f397206a8f8382ac72814454b2effbcdb132855345163cdb650` |
| `1.14.4` | previous | `arm64-v8a` | `sing-box-1.14.4-android-arm64.tar.gz` | `fb3a6c5197f4044f82c8fd75e01ce14c28b6abdd4c3df84bb987d41a962a320f` |
| `1.14.4` | previous | `armeabi-v7a` | `sing-box-1.14.4-android-arm.tar.gz` | `a89f48c4f2691bf37476308212175ba7b7315db5ca03c0bf9b20d15f26bc70e9` |
| `1.14.4` | previous | `x86_64` | `sing-box-1.14.4-android-amd64.tar.gz` | `e72a8eead1ace9ce84a4ed07dfbf364e87562b736463fc00967b845cf9b3b8ac` |

Sources: `https://github.com/SagerNet/sing-box/releases/tag/v1.14.5` and `.../tag/v1.14.4`.
The `1.14.5` rows were computed on 2026-11-27 by downloading the three assets and hashing
them locally; they match the digest GitHub reports for each asset. The `1.14.4` rows are the
previous current pin, kept as the rollback target.

`x86` is deliberately absent: the sing-box Android release has no `386` build in this set and
no engine script targets it, so `scripts/engine-abis.txt` does not list it and Gradle rejects
`-PtargetAbi=x86`.

## Which ABIs exist

`scripts/engine-abis.txt` is the single list. `scripts/build-engines.sh` uses it as its default
argument list and `app/build.gradle.kts` rejects any `-PtargetAbi` outside it with an error that
names the supported ABIs. `app/src/test/java/com/example/EngineAbiPinTest.kt` fails when the
sing-box hash table, that file and this document disagree.

## Rollback to the previous pin

No file edit needed; the script accepts the previous version by name:

```bash
SINGBOX_VERSION=1.14.4 bash scripts/build-engines.sh arm64-v8a armeabi-v7a x86_64
bash gradlew --no-daemon :app:testDebugUnitTest :app:assembleRelease
```

The build fails at `sha256sum --check` if the asset does not match the pinned hash, so a wrong
or swapped download cannot be installed. To make the rollback permanent, change the default in
`scripts/engines/singbox.sh` (`version="${SINGBOX_VERSION:-1.14.4}"`) and move the table rows in
this file; keep both releases listed until the next forward pin is verified.

## Making a new pin

1. Read the upstream release page and copy the digest of each `sing-box-<v>-android-{arm64,arm,amd64}.tar.gz` asset.
2. Add a `<v>:<abi>` row per ABI in `scripts/engines/singbox.sh`, keep the old version's rows.
3. Update the table above, keeping one `current` and one `previous` release.
4. Run `bash gradlew --no-daemon :app:testDebugUnitTest` (the pin test checks the three files agree)
   and a release build of all three ABIs.

## Not covered here

Other engines have their own pins (`scripts/engines/*.sh`, `scripts/fetch-libxray-android.sh`,
`PrivatePsiphonArtifact.kt`). This file records only the sing-box rollback path, because that is
the one whose pin previously had no previous-version record.
