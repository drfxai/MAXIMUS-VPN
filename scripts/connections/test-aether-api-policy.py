#!/usr/bin/env python3
"""Execute the patched Rust guard that prevents replaying an account POST."""
import pathlib
import subprocess
import sys
import tempfile

source = (pathlib.Path(sys.argv[1]) / 'aether/src/https.rs').read_text()
start = source.index('fn maximus_api_tls_retry(')
end = source.index('async fn exchange(', start)
# The same guard/test code compiled into Aether, isolated from its Android linker.
with tempfile.TemporaryDirectory() as tmp:
    root = pathlib.Path(tmp)
    unit = root / 'policy.rs'
    unit.write_text('#[derive(Debug)] enum AetherError { Tls(String), Api(String) }\n' + source[start:end])
    subprocess.run(['rustc', '--edition=2024', '--test', str(unit), '-o', str(root / 'policy')], check=True)
    subprocess.run([str(root / 'policy')], check=True)
