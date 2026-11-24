# Pinned native engine versions

`sing-box` is a separate GPL-3.0-or-later program downloaded at build time by
`scripts/engines/singbox.sh` and copied to `app/src/main/jniLibs/<abi>/libsingbox.so`.
No binary is committed. The pin in that script **is** the source of truth; this file records
the current and previous pin so a bad release can be rolled back without guesswork.

## sing-box

| release | role | ABI | upstream asset | SHA-256 |
|---|---|---|---|---|
| `1.14.4` | current | `arm64-v8a` | `sing-box-1.14.4-android-arm64.tar.gz` | `fb3a6c5197f4044f82c8fd75e01ce14c28b6abdd4c3df84bb987d41a962a320f` |
| `1.14.4` | current | `armeabi-v7a` | `sing-box-1.14.4-android-arm.tar.gz` | `a89f48c4f2691bf37476308212175ba7b7315db5ca03c0bf9b20d15f26bc70e9` |
| `1.14.4` | current | `x86_64` | `sing-box-1.14.4-android-amd64.tar.gz` | `e72a8eead1ace9ce84a4ed07dfbf364e87562b736463fc00967b845cf9b3b8ac` |
| `1.14.3` | previous | `arm64-v8a` | `sing-box-1.14.3-android-arm64.tar.gz` | `149569b5dbf01958e6598beb3a13078d6fc0e353ef06f4aff85f5f28bc23593b` |
| `1.14.3` | previous | `armeabi-v7a` | `sing-box-1.14.3-android-arm.tar.gz` | `0c0df4fbf85656896d5b604e9eb4e556c1a3f68dd615b0ece3f9f6bd5adab5d6` |
| `1.14.3` | previous | `x86_64` | `sing-box-1.14.3-android-amd64.tar.gz` | `a84a5aea86ad0588b76e23051e9e59405a7ed52358acfb9710cfaa055fe60e4a` |

Sources: `https://github.com/SagerNet/sing-box/releases/tag/v1.14.4` and `.../tag/v1.14.3`.
The `1.14.3` rows were re-computed on 2026-11-24 by downloading the three assets and hashing
them locally; they match the digest GitHub reports for each asset. The `1.14.4` rows match the
digests of the assets currently pinned in `scripts/engines/singbox.sh`.

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
SINGBOX_VERSION=1.14.3 bash scripts/build-engines.sh arm64-v8a armeabi-v7a x86_64
bash gradlew --no-daemon :app:testDebugUnitTest :app:assembleRelease
```

The build fails at `sha256sum --check` if the asset does not match the pinned hash, so a wrong
or swapped download cannot be installed. To make the rollback permanent, change the default in
`scripts/engines/singbox.sh` (`version="${SINGBOX_VERSION:-1.14.3}"`) and move the table rows in
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
