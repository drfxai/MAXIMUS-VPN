package com.example.vpn.tls;

import java.io.*;
import java.util.Arrays;

/** Reframes the first ClientHello into TLS records. Handshake bytes and transcript are unchanged. */
public final class ClientHelloRecords extends OutputStream {
    private final OutputStream out;
    private final int chunk;
    private final byte[] header = new byte[5];
    private int headers, used;
    private byte[] body;
    private boolean done, failed;
    public ClientHelloRecords(OutputStream out, int chunk) {
        if (chunk < 16 || chunk > 1024) throw new IllegalArgumentException("Invalid record size");
        this.out = out; this.chunk = chunk;
    }
    @Override public synchronized void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }
    @Override public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        if (failed) throw new IOException("TLS record stream failed; close the connection");
        try {
            while (length > 0) {
                if (done) { out.write(bytes,offset,length); return; }
                if (headers < 5) {
                    int n = Math.min(5-headers,length);
                    System.arraycopy(bytes,offset,header,headers,n); headers+=n; offset+=n; length-=n;
                    if (headers < 5) return;
                    int size=((header[3]&255)<<8)|(header[4]&255);
                    if (header[0]!=22 || header[1]!=3 || size<4 || size>16384) {
                        done=true; out.write(header); continue;
                    }
                    body=new byte[size];
                }
                int n=Math.min(body.length-used,length);
                System.arraycopy(bytes,offset,body,used,n); used+=n; offset+=n; length-=n;
                if (used==body.length) {
                    done=true; // A partial socket failure must never replay the handshake.
                    if (body[0]!=1) { out.write(header); out.write(body); }
                    else for(int p=0;p<body.length;p+=chunk) {
                        int count=Math.min(chunk,body.length-p);
                        byte[] h=Arrays.copyOf(header,5); h[3]=(byte)(count>>>8); h[4]=(byte)count;
                        out.write(h); out.write(body,p,count); out.flush();
                    }
                    body=null;
                }
            }
        } catch(IOException e) { failed=true; throw e; }
    }
    @Override public synchronized void flush() throws IOException {
        // Never flush an incomplete record header/body as a malformed record.
        out.flush();
    }
    @Override public synchronized void close() throws IOException { out.close(); }
}
