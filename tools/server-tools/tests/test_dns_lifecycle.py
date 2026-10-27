"""Execute the actual embedded shell preflight with controlled network observations."""
import pathlib
import re
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
SOURCE = (ROOT / 'app/src/main/java/com/example/panels/servers/DnsServerScripts.kt').read_text()
PREFLIGHT = SOURCE.split('internal val LISTENER_PREFLIGHT = """', 1)[1].split('""".trimIndent()', 1)[0].replace('§', '$')

class DnsLifecycle(unittest.TestCase):
    def run_shell(self, mock, call):
        return subprocess.run(['sh', '-c', mock + '\n' + PREFLIGHT + '\n' + call], text=True, capture_output=True)

    def test_exact_binding(self):
        for proto in ['udp', 'tcp']:
            for address, expected in [('127.0.0.53%lo:53', 0), ('127.0.0.54:53', 0), ('[::1]:53', 0),
                                      ('192.0.2.2:53', 0), ('192.0.2.1:53', 23), ('192.0.2.1%eth0:53', 23),
                                      ('0.0.0.0:53', 23), ('*:53', 23), ('[::]:53', 23), ('127.0.0.53:5353', 0)]:
                with self.subTest(proto=proto, address=address):
                    result = self.run_shell(f"DNS_BIND=192.0.2.1\nss() {{ echo 'UNCONN 0 0 {address} 0.0.0.0:*'; }}", f'require_free_listener {proto} 53')
                    self.assertEqual(expected, result.returncode, result.stderr)

    def test_inspection_failure_is_not_free(self):
        result = self.run_shell('DNS_BIND=192.0.2.1\nss() { return 1; }', 'require_free_listener udp 53')
        self.assertEqual(23, result.returncode)

    def test_bind_selection(self):
        for ssh, addresses, expected in [
            ('198.51.100.2 4000 192.0.2.1 22', ['192.0.2.1', '192.0.2.2'], 0),
            ('', ['192.0.2.1'], 0), ('', ['192.0.2.1', '192.0.2.2'], 23), ('', [], 23),
            ('', ['127.0.0.53'], 23), ('', ['999.1.1.1'], 23)]:
            lines = '\n'.join('2: eth0 inet '+a+'/24 scope global eth0' for a in addresses)
            r = self.run_shell(f"SSH_CONNECTION='{ssh}'\nip() {{ printf '%s\\n' '{lines}'; }}", 'select_dns_bind')
            self.assertEqual(expected, r.returncode, r.stderr)

    def test_nat_collision(self):
        for rule, expected in [('', 0), ('-A PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5300', 23),
                               ('-A PREROUTING -p udp --dport 5353 -j REDIRECT --to-ports 5300', 0)]:
            r = self.run_shell(f"SUDO=\niptables() {{ echo '{rule}'; }}\nnft() {{ :; }}", 'require_no_dns_redirect')
            self.assertEqual(expected, r.returncode, r.stderr)

    def test_cotten_pins_both_transports_to_same_address(self):
        self.assertIn('UDP_HOST = \\"§DNS_BIND\\"', SOURCE)
        self.assertIn('TCP_IPV6_ENABLED = false', SOURCE)

if __name__ == '__main__':
    unittest.main()
