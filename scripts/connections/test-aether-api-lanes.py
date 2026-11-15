#!/usr/bin/env python3
"""Compile and run the std-only part of the Aether account API lanes (MAXIMUS patch) alone.

Usage: test-aether-api-lanes.py [patched-aether-source-dir]
Without an argument it tests scripts/connections/aether/maximus_api_lane.rs from this checkout.
"""
import os
import pathlib
import subprocess
import sys
import tempfile

here = pathlib.Path(__file__).resolve().parent
if len(sys.argv) > 1:
    source = (pathlib.Path(sys.argv[1]) / 'aether/src/maximus_api_lane.rs').read_text()
else:
    source = (here / 'aether/maximus_api_lane.rs').read_text()
start = source.index('// MAXIMUS-PURE-BEGIN')
end = source.index('// MAXIMUS-PURE-END', start)
pure = source[start:end]

TESTS = r'''
#[cfg(test)]
mod lane_tests {
    use super::*;

    fn hello(sni: &str) -> Vec<u8> {
        let mut buf = vec![0x16, 0x03, 0x01, 0x00, 0x80, 0x01];
        buf.extend(std::iter::repeat(7u8).take(60));
        buf.extend_from_slice(sni.as_bytes());
        buf.extend(std::iter::repeat(9u8).take(40));
        buf
    }

    #[test]
    fn methods_keep_uac_android_order_and_names() {
        let names: Vec<&str> = METHOD_ORDER.iter().map(|m| m.name()).collect();
        assert_eq!(names, ["disoob-byte0-ttl1", "oob-insni+5-ttl1", "oob-byte0-nottl",
            "disoob-byte0-ttl1-plain", "disoob-byte0-ttl1-split"]);
        assert!(!OobMethod::OobByte0NoTtl.uses_ttl1());
        assert!(OobMethod::DisoobByte0Ttl1.uses_ttl1());
        assert_eq!(OobMethod::DisoobByte0Ttl1.pause_ms(), 50);
        assert_eq!(OobMethod::OobByte0NoTtl.pause_ms(), 30);
    }

    #[test]
    fn a_method_list_overrides_only_with_known_names() {
        assert_eq!(methods_from(Some("oob-byte0-nottl, nope")), vec![OobMethod::OobByte0NoTtl]);
        assert_eq!(methods_from(Some("nope")), METHOD_ORDER.to_vec());
        assert_eq!(methods_from(None), METHOD_ORDER.to_vec());
    }

    #[test]
    fn the_in_sni_split_lands_five_bytes_into_the_name() {
        let buf = hello(OOB_API_HOST);
        let at = find_sni(&buf, OOB_API_HOST).unwrap();
        assert_eq!(split_offset(&buf, OobMethod::OobInSni5Ttl1, OOB_API_HOST), at + 5);
        assert_eq!(split_offset(&buf, OobMethod::DisoobByte0Ttl1, OOB_API_HOST), 0);
        // No name in the hello: byte 0 + 5, still inside the buffer.
        assert_eq!(split_offset(&buf, OobMethod::OobInSni5Ttl1, "absent.example"), 5);
        // Out of range falls back to 0, never past the end.
        assert_eq!(split_offset(&[1, 2, 3], OobMethod::OobInSni5Ttl1, "x"), 0);
    }

    #[test]
    fn cloudflare_ranges_are_recognised_and_others_are_not() {
        for ip in OOB_EDGES_V4.iter().chain(OOB_EDGES_V6.iter()).chain(RELAY_EDGES.iter()) {
            assert!(is_cloudflare_ip(ip.parse().unwrap()), "{ip}");
        }
        for ip in ["1.1.1.1", "8.8.8.8", "10.0.0.1", "2001:db8::1", "185.1.2.3"] {
            assert!(!is_cloudflare_ip(ip.parse().unwrap()), "{ip}");
        }
        assert!(is_cloudflare_ip("104.24.4.6".parse().unwrap()));
        assert!(is_cloudflare_ip("2a06:98c7::1".parse().unwrap()));
        assert!(!is_cloudflare_ip("2a06:98c8::1".parse().unwrap()));
    }

    #[test]
    fn a_relay_is_an_https_name_with_a_plain_path() {
        assert_eq!(parse_relay("https://warp-api.me.workers.dev/k3y_abc"),
            Some(RelayTarget { host: "warp-api.me.workers.dev".into(), path: "/k3y_abc".into() }));
        assert_eq!(parse_relay(" https://Relay.Example.com:443 ").unwrap(),
            RelayTarget { host: "relay.example.com".into(), path: "/".into() });
        for bad in ["http://a.workers.dev/", "https://a.workers.dev:8443/", "https://user@a.workers.dev/",
            "https://104.21.1.2/", "https://localhost/", "https://a.workers.dev/x?y=1", "https://a.workers.dev/../x",
            "https://-a.workers.dev/", "https://a.workers.dev/x y", ""] {
            assert_eq!(parse_relay(bad), None, "{bad}");
        }
    }

    #[test]
    fn the_plan_races_direct_relay_and_staggered_oob_lanes() {
        let lanes = plan(true, true, &METHOD_ORDER, None);
        assert_eq!(lanes[0].label, "direct");
        assert_eq!(lanes[0].delay_ms, 0);
        assert_eq!(lanes.iter().filter(|l| l.label.starts_with("relay@")).count(), 4);
        assert!(lanes.iter().filter(|l| l.label.starts_with("relay@")).all(|l| l.delay_ms == 0));
        let first = lanes.iter().find(|l| l.label == "oob:disoob-byte0-ttl1/tls12@v4").unwrap();
        assert_eq!(first.delay_ms, OOB_FIRST_MS);
        assert!(lanes.iter().any(|l| l.label == "oob:disoob-byte0-ttl1/tls12@v6"));
        assert_eq!(lanes.iter().find(|l| l.label == "oob:oob-insni+5-ttl1/tls12@v4").unwrap().delay_ms, OOB_FIRST_MS + OOB_STEP_MS);
        assert_eq!(lanes.last().unwrap().label, "oob:disoob-byte0-ttl1/tls13@v4");
        assert_eq!(lanes.len(), 1 + 4 + 2 * METHOD_ORDER.len() + 1);
        assert!(lanes.iter().all(|l| printable_label(&l.label)));
        assert!(lanes.iter().all(|l| l.delay_ms <= 2_500));
    }

    #[test]
    fn the_last_winner_runs_alone_first() {
        let lanes = plan(true, false, &METHOD_ORDER, Some("oob:oob-byte0-nottl/tls12@v6"));
        let won = lanes.iter().find(|l| l.label == "oob:oob-byte0-nottl/tls12@v6").unwrap();
        assert_eq!(won.delay_ms, 0);
        assert!(lanes.iter().filter(|l| l.label != won.label).all(|l| l.delay_ms >= WINNER_HEAD_START_MS));
        // A winner the plan no longer has changes nothing.
        assert_eq!(plan(true, false, &METHOD_ORDER, Some("relay@v4")), plan(true, false, &METHOD_ORDER, None));
    }

    #[test]
    fn without_oob_or_relay_only_direct_runs() {
        assert_eq!(plan(false, false, &METHOD_ORDER, None).len(), 1);
        assert!(plan(false, true, &METHOD_ORDER, None).iter().all(|l| !l.label.starts_with("oob:")));
    }

    #[test]
    fn handshake_budget_is_clamped() {
        assert_eq!(handshake_ms(None), 7_000);
        assert_eq!(handshake_ms(Some("100")), 2_000);
        assert_eq!(handshake_ms(Some("99999")), 15_000);
        assert_eq!(handshake_ms(Some("x")), 7_000);
    }

    #[test]
    fn labels_never_carry_addresses_or_hosts() {
        assert!(printable_label("oob:oob-insni+5-ttl1/tls12@v6"));
        assert!(!printable_label("relay 104.21.1.2"));
        assert!(!printable_label("RELAY@x.workers.dev?"));
    }
}
'''

with tempfile.TemporaryDirectory() as tmp:
    root = pathlib.Path(tmp)
    unit = root / 'lanes.rs'
    unit.write_text('#![allow(dead_code)]\nuse std::net::IpAddr;\n' + pure + TESTS)
    command = ['rustc', '--edition=2021', '--test', str(unit), '-o', str(root / 'lanes')]
    command += os.environ.get('MAXIMUS_RUSTC_FLAGS', '').split()
    subprocess.run(command, check=True)
    subprocess.run([str(root / 'lanes')], check=True)
print('Aether account API lane plan tests passed')
