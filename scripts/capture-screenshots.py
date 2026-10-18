#!/usr/bin/env python3
"""Capture unmodified screens from the published APK on a clean Android emulator."""
import hashlib
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = 'com.drfxai.maximusvpn'
OUT = Path('docs/media/screenshots')
OUT.mkdir(parents=True, exist_ok=True)


def adb(*args, binary=False, check=True):
    return subprocess.run(['adb', *args], capture_output=True, text=not binary,
                          check=check, timeout=45).stdout


def tree():
    for _ in range(3):
        adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/window.xml', check=False)
        raw = adb('shell', 'cat', '/sdcard/window.xml', check=False)
        if '<hierarchy' in raw:
            return ET.fromstring(raw[raw.index('<?xml'):] if '<?xml' in raw else raw)
        time.sleep(2)
    raise RuntimeError('Could not capture the Android UI hierarchy')


def tab(label):
    root = tree()
    nodes = [n for n in root.iter('node') if label in [n.get('text'), n.get('content-desc')]]
    if not nodes:
        print(ET.tostring(root, encoding='unicode'))
        (OUT / 'capture-failure.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
        print(adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/com.example.MainActivity'))
        print(adb('logcat', '-d', '-b', 'crash'))
        raise RuntimeError('Navigation label missing: ' + label)
    n = max(nodes, key=lambda n: int(re.findall(r'\d+', n.get('bounds'))[1]))
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(3)
    root = tree()
    # A selected ancestor identifies the actual navigation destination.
    def selected(node, active=False):
        active = active or node.get('selected') == 'true'
        return (active and label in [node.get('text'), node.get('content-desc')]) or any(selected(c, active) for c in node)
    if not selected(root):
        raise RuntimeError('Destination was not selected: ' + label)


adb('shell', 'pm', 'clear', PACKAGE)
adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
adb('shell', 'wm', 'size', '1080x2400')
adb('shell', 'wm', 'density', '420')
adb('shell', 'settings', 'put', 'global', 'window_animation_scale', '0')
adb('shell', 'settings', 'put', 'global', 'transition_animation_scale', '0')
adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '0')
adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/com.example.MainActivity')
time.sleep(12)
# Dismiss the normal first-run community dialog without opening any external links.
for node in tree().iter('node'):
    if node.get('text') == "Don't show again":
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
        adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(2)
        break
shots = []
for label, name in [('Tunnel', 'tunnel'), ('Nodes', 'nodes'), ('LAB', 'lab'), ('Panels', 'panels')]:
    tab(label)
    png = adb('exec-out', 'screencap', '-p', binary=True)
    if not png.startswith(b'\x89PNG\r\n\x1a\n'):
        raise RuntimeError('Invalid screenshot: ' + name)
    (OUT / (name + '.png')).write_bytes(png)
    shots.append({'screen': label, 'file': name + '.png', 'sha256': hashlib.sha256(png).hexdigest()})
version = adb('shell', 'dumpsys', 'package', PACKAGE)
metadata = {'package': PACKAGE, 'version': re.findall(r'versionName=(\S+)', version)[0],
            'versionCode': re.findall(r'versionCode=(\d+)', version)[0],
            'android': adb('shell', 'getprop', 'ro.build.version.release').strip(),
            'device': 'Android emulator, x86_64, 1080x2400',
            'state': 'Clean install with app-provided public nodes; no private accounts, user-supplied profiles or active VPN',
            'capture': 'adb exec-out screencap -p; original pixels, no UI retouching', 'screens': shots}
(OUT / 'capture.json').write_text(json.dumps(metadata, indent=2) + '\n')
print(json.dumps(metadata, indent=2))
