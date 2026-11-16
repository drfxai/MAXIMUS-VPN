"""Fail a release if its supposedly standalone APK has missing/wrong native ABIs."""
import sys
from pathlib import Path
import zipfile
path, requested = sys.argv[1:]
assets_root = Path(__file__).resolve().parents[1] / "app/src/main/assets"
expected_elf = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62)}
with zipfile.ZipFile(path) as apk:
    assert apk.testzip() is None, "Corrupt ZIP entry"
    assert {"AndroidManifest.xml", "classes.dex", "resources.arsc"} <= set(apk.namelist())
    for part in range(1, 7):
        asset = f"assets/resolvers/part_{part}.txt"
        expected = Path(__file__).resolve().parents[1] / "app/src/main" / asset
        assert apk.read(asset) == expected.read_bytes(), f"Resolver asset differs: {asset}"
    abis = {name.split("/")[1] for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")}
    assert abis == {"arm64-v8a"} if requested == "arm64-v8a" else {"arm64-v8a", "armeabi-v7a", "x86_64"} <= abis, abis
    psiphon_asset = "psiphon/public-config.json"
    assert apk.read(f"assets/{psiphon_asset}") == (assets_root / psiphon_asset).read_bytes(), "Bundled public Psiphon profile differs"
    # Check both the packaged path and the ELF header for each shipped engine.
    for abi in (["arm64-v8a"] if requested == "arm64-v8a" else ["arm64-v8a", "armeabi-v7a", "x86_64"]):
        for engine in ("aether", "serverless", "byedpi", "mihomo", "singbox", "psiphon", "dnstt", "cottendns", "soar", "slipstream", "lyrebird", "tor", "usque", "matrixtransport", "radiance", "amneziawg"):
            entry = f"lib/{abi}/lib{engine}.so"
            assert entry in apk.namelist(), f"{engine} engine missing for {abi}"
            with apk.open(entry) as library:
                header = library.read(20)
            assert len(header) == 20 and header[:4] == b"\x7fELF" and header[5] == 1, f"Invalid ELF: {entry}"
            assert (header[4], int.from_bytes(header[18:20], "little")) == expected_elf[abi], f"Wrong ELF ABI: {entry}"
    assert all(item.compress_type == zipfile.ZIP_DEFLATED for item in apk.infolist() if item.filename.endswith(".so")), "Expected extracted native libraries"
    print(path, "verified native ABIs:", sorted(abis))

