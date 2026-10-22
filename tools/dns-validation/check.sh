#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
java com.sun.tools.javac.Main -d "$classes" "$root/app/src/main/java/com/example/vpn/dns/DnsMessageValidator.java" "$root/tools/dns-validation/DnsMessageValidatorCheck.java"
java -cp "$classes" DnsMessageValidatorCheck
java com.sun.tools.javac.Main -cp "$classes" -d "$classes" "$root/tools/dns-validation/DnsOwnershipCheck.java"
java -cp "$classes" DnsOwnershipCheck

java com.sun.tools.javac.Main -d "$classes" "$root/app/src/main/java/com/example/vpn/tls/TlsHelloSocketFactory.java" "$root/tools/dns-validation/TlsSplitCheck.java"
java -cp "$classes" TlsSplitCheck "$root/app/src/test/resources/doh-test-fixture.p12.base64"
