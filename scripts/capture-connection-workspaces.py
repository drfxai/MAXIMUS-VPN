#!/usr/bin/env python3
"""Exercise the real connection tabs and app theme on a clean emulator; no VPN/VPS mutations."""
import importlib.util
import json
import time
from pathlib import Path

spec = importlib.util.spec_from_file_location('capture_helpers', Path(__file__).with_name('capture-smart-psiphon.py'))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)
ui.OUT = Path('app/build/workspace-captures')
ui.OUT.mkdir(parents=True, exist_ok=True)


def scroll_to(label):
    for _ in range(8):
        if ui.locate(label):
            return
        ui.adb('shell', 'input', 'swipe', '550', '1850', '550', '760', '350')
        time.sleep(.8)
    raise AssertionError('Missing control: ' + label)


def main():
    ui.adb('shell', 'pm', 'clear', ui.PACKAGE, check=False)
    ui.adb('shell', 'pm', 'grant', ui.PACKAGE, 'android.permission.POST_NOTIFICATIONS', check=False)
    ui.adb('shell', 'wm', 'size', '1080x2400')
    ui.adb('shell', 'wm', 'density', '420')
    ui.relaunch()
    captures = []
    for theme in ('Dark', 'Light'):
        ui.require('Tunnel')
        ui.require(theme + ' Theme')
        for link, title, name in [('Aether / WARP', 'Aether WARP', 'aether'),
                                  ('Serverless Direct', 'Serverless Direct', 'direct')]:
            ui.require('LAB')
            scroll_to(link)
            ui.require(link)
            captures.append(ui.capture(name + '-' + theme.lower(), title))
            ui.require('Advanced')
            scroll_to('Outbound')
            if not ui.contains('Outbound'):
                raise AssertionError('Exit-node controls missing')
            ui.require('Diagnostics')
            scroll_to('HTTPS traffic')
            if not ui.contains('Pending'):
                raise AssertionError('Unmeasured access must not appear verified')
            ui.require('Connect')
            ui.adb('shell', 'input', 'keyevent', '4')
            time.sleep(2)
        ui.goto_panels()
        ui.navigate_horizontal('Smart Manager', 'left')
        captures.append(ui.capture('smart-manager-' + theme.lower(), 'Connect your first VPS'))
        ui.require('Add VPS')
        # The form must be reachable; do not populate credentials or inspect a remote host.
        time.sleep(1)
        ui.adb('shell', 'input', 'keyevent', '4')
        time.sleep(1)
        ui.adb('shell', 'input', 'keyevent', '4')
        time.sleep(1)
    (ui.OUT / 'captures.json').write_text(json.dumps(captures, indent=2))
    print('PASS: Aether, Direct and Smart Manager navigation in dark and light themes')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        (ui.OUT / 'failure.png').write_bytes(ui.adb('exec-out', 'screencap', '-p', binary=True, check=False))
        (ui.OUT / 'failure.xml').write_text(ui.ET.tostring(ui.hierarchy(), encoding='unicode'))
        print(ui.adb('logcat', '-d', '-b', 'crash', check=False)[-3500:])
        raise
