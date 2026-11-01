#!/usr/bin/env python3
"""ADB pixel screenshots of *running* MAXIMUS VPN on Android 15 emulator.

The build must be assembled from the checked-out main commit, not downloaded from
the older V1.0.4 release. Never fabricate VPN connected states or private VPS data.
"""
import hashlib
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.drfxai.maximusvpn"
OUT = Path("docs/media/screenshots")
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args, binary=False, check=True):
    p = subprocess.run(["adb", *args], capture_output=True, text=not binary, timeout=90)
    if check and p.returncode:
        raise RuntimeError(f"adb {args[:3]}: {p.stderr[:400]}")
    return p.stdout

def hierarchy():
    for _ in range(5):
        adb("shell", "uiautomator", "dump", "--compressed", "/sdcard/maximus-capture.xml", check=False)
        raw = adb("shell", "cat", "/sdcard/maximus-capture.xml", check=False)
        if "<hierarchy" in raw:
            return ET.fromstring(raw[raw.index("<?xml"): ] if "<?xml" in raw else raw)
        time.sleep(1)
    raise RuntimeError("Android UI hierarchy unavailable")

def locate(label):
    root = hierarchy()
    nodes = [n for n in root.iter("node") if n.get("text") == label or n.get("content-desc") == label]
    for node in nodes:
        coords = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
        if len(coords) == 4 and coords[2] > coords[0] and coords[3] > coords[1]:
            return ((coords[0] + coords[2]) // 2, (coords[1] + coords[3]) // 2)
    return None

def contains(label):
    root = hierarchy()
    return any(label in (n.get("text") or "") for n in root.iter("node"))

def tap(label):
    pos = locate(label)
    if pos is None:
        return False
    adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
    time.sleep(2)
    return True

def require(label):
    if not tap(label):
        raise RuntimeError("Navigation missing visible text: " + label)

def relaunch():
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/com.example.MainActivity")
    time.sleep(9)
    if contains("Don't show again"):
        tap("Don't show again")
        time.sleep(2)

def goto_panels():
    if not tap("Panels"):
        raise RuntimeError("Cannot enter Panels bottom navigation")
    if not contains("Server + Cloudflare + Tunnel control center"):
        raise RuntimeError("Panels screen not reached")

def navigate_horizontal(target, direction):
    # 1080x2400 @420 dpi: the Panels segmented selector sits at y~260.
    for _ in range(7):
        if locate(target):
            require(target)
            return
        if direction == "left":
            adb("shell", "input", "swipe", "900", "255", "130", "255", "360")
        else:
            adb("shell", "input", "swipe", "130", "255", "900", "255", "360")
        time.sleep(1)
    raise RuntimeError("Cannot find segmented tab: " + target)

def capture(name, verified_label):
    if not contains(verified_label):
        raise RuntimeError(f"Target view does not contain {verified_label!r}: {name}")
    time.sleep(2)
    png = adb("exec-out", "screencap", "-p", binary=True)
    if not png.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("ADB screencap failed for " + name)
    path = OUT / (name + ".png")
    path.write_bytes(png)
    return {"screen": name, "file": path.name,
            "sha256": hashlib.sha256(png).hexdigest(),
            "bytes": len(png), "verified_label": verified_label}

def main():
    # Fresh Android emulators have no previous app data; pm clear can be denied by
    # emulator image policy and must not block a real screenshot run.
    adb("shell", "pm", "clear", PACKAGE, check=False)
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)
    adb("shell", "wm", "size", "1080x2400")
    adb("shell", "wm", "density", "420")
    for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        adb("shell", "settings", "put", "global", k, "0")
    relaunch()
    goto_panels()
    navigate_horizontal("Smart Manager", "left")
    screenshots = [capture("smart-manager", "MAXIMUS  /  INFRASTRUCTURE")]

    # Force-stop only; preserve the already dismissed onboarding dialog.
    relaunch()
    goto_panels()
    navigate_horizontal("Install", "right")
    # Hysteria2 follows DNS tunnel tools. Scroll the real Install viewport.
    for _ in range(12):
        if locate("Psiphon Server"):
            break
        adb("shell", "input", "swipe", "570", "1840", "570", "610", "460")
        time.sleep(1)
    require("Psiphon Server")
    screenshots.append(capture("psiphon-server", "Private Psiphon Server"))

    version = adb("shell", "dumpsys", "package", PACKAGE)
    metadata = {
        "source": "Actual Android emulator screenshots from installed, locally built debug APK",
        "source_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "package": PACKAGE,
        "versionName": re.findall(r"versionName=(\S+)", version)[0],
        "versionCode": re.findall(r"versionCode=(\d+)", version)[0],
        "buildType": "debug (latest source; NOT the older published V1.0.4 APK)",
        "android": adb("shell", "getprop", "ro.build.version.release").strip(),
        "device": "Android emulator, x86_64, 1080x2400",
        "privacy": "Clean install; no private VPS, user accounts or VPN connections",
        "capture": "adb exec-out screencap -p; original pixels, no retouching",
        "screens": screenshots,
    }
    (OUT / "capture-smart-psiphon.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(metadata, indent=2, ensure_ascii=False))

if __name__ == "__main__":
    try:
        main()
    except Exception:
        try:
            (OUT / "capture-smart-psiphon-failure.png").write_bytes(
                adb("exec-out", "screencap", "-p", binary=True, check=False))
            print(adb("logcat", "-d", "-b", "crash", check=False)[-3500:])
            print(ET.tostring(hierarchy(), encoding="unicode")[:4500])
        except Exception:
            pass
        raise
