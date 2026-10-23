package com.example.vpn.experimental;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Checks the RFC 6455 server challenge; a bare HTTP 101 is not a WebSocket handshake. */
public final class WebSocketUpgrade {
    private WebSocketUpgrade() {}
    public static boolean valid(InputStream in, String key) throws IOException {
        Map<String,String> headers=new HashMap<>();
        int total=0;
        for(int count=0;count<64;count++) {
            ByteArrayOutputStream line=new ByteArrayOutputStream();
            for(;;) {
                int b=in.read();
                if(b<0 || ++total>16384 || line.size()>4096) return false;
                if(b=='\n') break;
                line.write(b);
            }
            String raw=line.toString("US-ASCII");
            if(!raw.endsWith("\r")) return false;
            raw=raw.substring(0,raw.length()-1);
            if(raw.isEmpty()) {
                try {
                    String expected=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    return expected.equals(headers.get("sec-websocket-accept")) &&
                        "websocket".equalsIgnoreCase(headers.get("upgrade")) &&
                        Arrays.stream(headers.getOrDefault("connection","").split(",")).anyMatch(v->v.trim().equalsIgnoreCase("upgrade")) &&
                        !headers.containsKey("sec-websocket-extensions") && !headers.containsKey("sec-websocket-protocol");
                } catch(java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
            }
            int colon=raw.indexOf(':');
            if(colon<=0 || Character.isWhitespace(raw.charAt(0))) return false;
            String name=raw.substring(0,colon).toLowerCase(Locale.ROOT);
            String value=raw.substring(colon+1).trim();
            if(!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+")) return false;
            if(headers.put(name,value)!=null) return false;
        }
        return false;
    }
}
