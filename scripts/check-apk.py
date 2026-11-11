"""Fail a release if its supposedly standalone APK has missing/wrong native ABIs."""
import sys
from pathlib import Path
import zipfile
path, requested = sys.argv[1:]
with zipfile.ZipFile(path) as apk:
    assert apk.testzip() is None, "Corrupt ZIP entry"
    assert {"AndroidManifest.xml", "classes.dex", "resources.arsc"} <= set(apk.namelist())
    for part in range(1, 7):
        asset = f"assets/resolvers/part_{part}.txt"
        expected = Path(__file__).resolve().parents[1] / "app/src/main" / asset
        assert apk.read(asset) == expected.read_bytes(), f"Resolver asset differs: {asset}"
    abis = {name.split("/")[1] for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")}
    assert abis == {"arm64-v8a"} if requested == "arm64-v8a" else {"arm64-v8a", "armeabi-v7a", "x86_64"} <= abis, abis
    # The engine programs ship for every 64-bit ABI the app is tested on.
    for abi in (["arm64-v8a"] if requested == "arm64-v8a" else ["arm64-v8a", "armeabi-v7a", "x86_64"]):
        for engine in ("aether", "serverless", "byedpi", "mihomo", "singbox", "psiphon", "dnstt", "cottendns", "soar", "slipstream", "lyrebird", "tor", "usque", "matrixtransport"):
            assert f"lib/{abi}/lib{engine}.so" in apk.namelist(), f"{engine} engine missing for {abi}"
    assert all(item.compress_type == zipfile.ZIP_DEFLATED for item in apk.infolist() if item.filename.endswith(".so")), "Expected extracted native libraries"
    print(path, "verified native ABIs:", sorted(abis))

