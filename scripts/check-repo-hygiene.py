#!/usr/bin/env python3
"""Refuses a tree that carries a key, a token or an assistant's name.

Keys and tokens belong in the repository's Actions secrets or in Cloudflare, never in a file here.
"""
from __future__ import annotations

import re
import subprocess
import sys

SKIP_DIRS = ("app/libs/", "app/build/", "build/", ".git/", "tools/censorsim/results/")
SKIP_SUFFIX = (".png", ".jpg", ".jpeg", ".webp", ".ttf", ".otf", ".aar", ".jar", ".keystore", ".jks", ".base64")
SELF = "scripts/check-repo-hygiene.py"

PATTERNS = [
    ("a private key", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----")),
    ("a Telegram bot token", re.compile(r"\b\d{8,12}:[A-Za-z0-9_-]{30,}\b")),
    ("a Cloudflare API token", re.compile(r"\b(?:CLOUDFLARE|CF)_API_TOKEN\s*[:=]\s*['\"]?[A-Za-z0-9_-]{30,}")),
    ("a Google API key", re.compile(r"\bAIza[0-9A-Za-z_-]{30,}\b")),
    ("a GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b")),
    ("an AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("an assistant's name", re.compile(r"(?i)\b(?:claude|anthropic|copilot|chatgpt)\b")),
]


def files() -> list[str]:
    out = subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout
    return [f for f in out.splitlines()
            if not f.startswith(SKIP_DIRS) and not f.endswith(SKIP_SUFFIX) and f != SELF]


def main() -> int:
    problems = []
    for path in files():
        try:
            with open(path, encoding="utf-8", errors="strict") as handle:
                lines = handle.readlines()
        except (UnicodeDecodeError, OSError):
            continue
        for number, line in enumerate(lines, 1):
            if len(line) > 4096:
                continue
            for what, pattern in PATTERNS:
                if pattern.search(line):
                    problems.append(f"{path}:{number}: {what}")
    for problem in problems:
        print(problem)
    print(f"{len(files())} files checked, {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
