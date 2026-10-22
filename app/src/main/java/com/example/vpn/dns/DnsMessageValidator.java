package com.example.vpn.dns;


/** Bounded DNS framing and question matching. This is not DNSSEC validation. */
public final class DnsMessageValidator {
    private DnsMessageValidator() {}

    private static int u16(byte[] b, int p) {
        return ((b[p] & 255) << 8) | (b[p + 1] & 255);
    }

    private static final class Name {
        final String text;
        final int end;
        Name(String text, int end) { this.text = text; this.end = end; }
    }

    private static Name name(byte[] b, int size, int start) {
        int p = start, end = -1, wireLength = 1, steps = 0;
        StringBuilder text = new StringBuilder();
        while (p < size && ++steps <= 128) {
            int n = b[p] & 255;
            if (n == 0) return new Name(text.toString(), end < 0 ? p + 1 : end);
            if ((n & 192) == 192) {
                if (p + 1 >= size) return null;
                int target = ((n & 63) << 8) | (b[p + 1] & 255);
                // Compression refers to an earlier name, never a header or itself.
                if (target < 12 || target >= p) return null;
                if (end < 0) end = p + 2;
                p = target;
            } else {
                if (n > 63 || p + 1 + n > size || (wireLength += n + 1) > 255) return null;
                // Length prefix preserves label boundaries even for arbitrary label octets.
                text.append(n).append(':');
                for (int j = 1; j <= n; j++) {
                    int c = b[p + j] & 255;
                    text.append((char)(c >= 'A' && c <= 'Z' ? c + 32 : c));
                }
                text.append('/');
                p += n + 1;
            }
        }
        return null;
    }

    public static boolean wellFormed(byte[] b, int size) {
        if (size < 12 || size > b.length || size > 65535) return false;
        int p = 12, questions = u16(b, 4);
        if (questions > 16) return false;
        for (int i = 0; i < questions; i++) {
            Name n = name(b, size, p);
            if (n == null || n.end + 4 > size) return false;
            p = n.end + 4;
        }
        int records = u16(b, 6) + u16(b, 8) + u16(b, 10);
        if (records > (size - p) / 11) return false;
        for (int i = 0; i < records; i++) {
            Name n = name(b, size, p);
            if (n == null || n.end + 10 > size) return false;
            p = n.end;
            int type = u16(b, p), length = u16(b, p + 8);
            p += 10;
            if (p + length > size || (type == 1 && length != 4) || (type == 28 && length != 16)) return false;
            if (type == 5) {
                Name target = name(b, size, p);
                if (target == null || target.end != p + length) return false;
            }
            p += length;
        }
        return p == size;
    }

    public static boolean isResponseTo(byte[] query, byte[] response, int size) {
        if (!wellFormed(query, query.length) || !wellFormed(response, size)) return false;
        if (query[0] != response[0] || query[1] != response[1] || (query[2] & 128) != 0 ||
            (response[2] & 128) == 0 || (query[2] & 120) != (response[2] & 120) ||
            u16(query, 4) != u16(response, 4)) return false;
        int q = 12, r = 12;
        for (int i = 0; i < u16(query, 4); i++) {
            Name qn = name(query, query.length, q), rn = name(response, size, r);
            if (!qn.text.equals(rn.text) || u16(query, qn.end) != u16(response, rn.end) ||
                u16(query, qn.end + 2) != u16(response, rn.end + 2)) return false;
            q = qn.end + 4; r = rn.end + 4;
        }
        return true;
    }

    /**
     * IN A/AAAA answers belonging to the question, following at most 16 CNAMEs. Unrelated
     * answer/additional records never become endpoint addresses. This validates structure and
     * ownership, not DNSSEC or the honesty of the resolver. Returned arrays are defensive copies.
     */
    public static java.util.List<byte[]> addressAnswers(byte[] query, byte[] response, int size) {
        java.util.List<byte[]> none = java.util.Collections.emptyList();
        if (!isResponseTo(query, response, size) || u16(query, 4) != 1 ||
                (response[2] & 2) != 0 || (response[3] & 15) != 0) return none;
        Name question = name(query, query.length, 12);
        int type = u16(query, question.end);
        if ((type != 1 && type != 28) || u16(query, question.end + 2) != 1) return none;
        java.util.Map<String, String> aliases = new java.util.HashMap<>();
        java.util.Map<String, java.util.List<byte[]>> addresses = new java.util.HashMap<>();
        Name echoed = name(response, size, 12);
        int p = echoed.end + 4;
        for (int i = 0; i < u16(response, 6); i++) {
            Name owner = name(response, size, p);
            p = owner.end;
            int rrType = u16(response, p), rrClass = u16(response, p + 2), length = u16(response, p + 8);
            p += 10;
            if (rrClass == 1 && rrType == 5) {
                Name target = name(response, size, p);
                String previous = aliases.put(owner.text, target.text);
                if (previous != null && !previous.equals(target.text)) return none;
            } else if (rrClass == 1 && rrType == type) {
                addresses.computeIfAbsent(owner.text, k -> new java.util.ArrayList<>())
                    .add(java.util.Arrays.copyOfRange(response, p, p + length));
            }
            p += length;
        }
        String current = question.text;
        java.util.Set<String> visited = new java.util.HashSet<>();
        for (int hops = 0; hops <= 16; hops++) {
            if (!visited.add(current)) return none;
            String target = aliases.get(current);
            if (target == null) return addresses.getOrDefault(current, none);
            // A CNAME owner cannot also own an address RRset.
            if (addresses.containsKey(current)) return none;
            current = target;
        }
        return none;
    }
}
