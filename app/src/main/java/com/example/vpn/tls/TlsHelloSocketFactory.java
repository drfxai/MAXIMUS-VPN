package com.example.vpn.tls;

import java.io.*;
import java.net.*;
import javax.net.ssl.SSLSocketFactory;

/** Splits the first outbound TLS handshake write without changing bytes or TLS authentication.
 * These are application writes, not a promise about physical TCP segment boundaries.
 */
public final class TlsHelloSocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory tls;
    private final int chunk, delayMs;
    private final boolean records;
    public TlsHelloSocketFactory(SSLSocketFactory tls, int chunk, int delayMs) {
        this(tls,chunk,delayMs,false);
    }
    public TlsHelloSocketFactory(SSLSocketFactory tls, int chunk, int delayMs, boolean records) {
        this.records=records;
        if (chunk < 16 || chunk > 1024 || delayMs < 0 || delayMs > 20)
            throw new IllegalArgumentException("Invalid TLS split limits");
        this.tls = tls; this.chunk = chunk; this.delayMs = delayMs;
    }
    public static OutputStream splitFirstHandshake(OutputStream out, int chunk, int delayMs) {
        if (chunk < 16 || chunk > 1024 || delayMs < 0 || delayMs > 20)
            throw new IllegalArgumentException("Invalid TLS split limits");
        return new FilterOutputStream(out) {
            private boolean first = true;
            @Override public synchronized void write(byte[] b, int off, int len) throws IOException {
                if (off < 0 || len < 0 || off > b.length - len) throw new IndexOutOfBoundsException();
                if (len == 0) return;
                boolean split = first && len >= 5 && b[off] == 22 && b[off+1] == 3;
                first = false;
                if (!split) { out.write(b,off,len); return; }
                // No replay after partial failure: the TLS connection must be closed by its caller.
                for (int p=0;p<len;p+=chunk) {
                    out.write(b,off+p,Math.min(chunk,len-p)); out.flush();
                    if (delayMs > 0 && p+chunk < len) {
                        try { Thread.sleep(delayMs); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("TLS split interrupted"); }
                    }
                }
            }
            @Override public void write(int b) throws IOException { write(new byte[]{(byte)b},0,1); }
        };
    }
    private Socket wrap(Socket socket) throws IOException { return new StreamSocket(socket,chunk,delayMs,records); }
    @Override public Socket createSocket(Socket socket,String host,int port,boolean close) throws IOException {
        try { return tls.createSocket(wrap(socket),host,port,close); }
        catch (IOException | RuntimeException e) { if (close) socket.close(); throw e; }
    }
    @Override public String[] getDefaultCipherSuites() { return tls.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return tls.getSupportedCipherSuites(); }
    // HTTPS clients normally use the layered overload above. Preserve normal verification in every overload.
    @Override public Socket createSocket(String h,int p) throws IOException { return createSocket(new Socket(h,p),h,p,true); }
    @Override public Socket createSocket(InetAddress h,int p) throws IOException { return createSocket(new Socket(h,p),h.getHostAddress(),p,true); }
    @Override public Socket createSocket(String h,int p,InetAddress local,int lp) throws IOException { return createSocket(new Socket(h,p,local,lp),h,p,true); }
    @Override public Socket createSocket(InetAddress h,int p,InetAddress local,int lp) throws IOException { return createSocket(new Socket(h,p,local,lp),h.getHostAddress(),p,true); }

    private static final class StreamSocket extends Socket {
        private final Socket raw;
        private final OutputStream out;
        StreamSocket(Socket raw,int chunk,int delay,boolean records) throws IOException {
            // No independent SocketImpl/FD: stream-based TLS providers must use the wrapped streams.
            super((SocketImpl)null);
            this.raw=raw; this.out=records ? new ClientHelloRecords(raw.getOutputStream(),chunk) : splitFirstHandshake(raw.getOutputStream(),chunk,delay);
        }
        @Override public InputStream getInputStream() throws IOException { return raw.getInputStream(); }
        @Override public OutputStream getOutputStream() { return out; }
        @Override public synchronized void close() throws IOException { raw.close(); }
        @Override public boolean isConnected() { return raw.isConnected(); }
        @Override public boolean isClosed() { return raw.isClosed(); }
        @Override public boolean isBound() { return raw.isBound(); }
        @Override public InetAddress getInetAddress() { return raw.getInetAddress(); }
        @Override public InetAddress getLocalAddress() { return raw.getLocalAddress(); }
        @Override public int getPort() { return raw.getPort(); }
        @Override public int getLocalPort() { return raw.getLocalPort(); }
        @Override public SocketAddress getRemoteSocketAddress() { return raw.getRemoteSocketAddress(); }
        @Override public SocketAddress getLocalSocketAddress() { return raw.getLocalSocketAddress(); }
        @Override public void connect(SocketAddress a, int t) throws IOException { raw.connect(a,t); }
        @Override public void connect(SocketAddress a) throws IOException { raw.connect(a); }
        @Override public void bind(SocketAddress a) throws IOException { raw.bind(a); }
        @Override public void setSoLinger(boolean on,int seconds) throws SocketException { raw.setSoLinger(on,seconds); }
        @Override public int getSoLinger() throws SocketException { return raw.getSoLinger(); }
        @Override public void setReceiveBufferSize(int n) throws SocketException { raw.setReceiveBufferSize(n); }
        @Override public int getReceiveBufferSize() throws SocketException { return raw.getReceiveBufferSize(); }
        @Override public void setSendBufferSize(int n) throws SocketException { raw.setSendBufferSize(n); }
        @Override public int getSendBufferSize() throws SocketException { return raw.getSendBufferSize(); }
        @Override public void setSoTimeout(int t) throws SocketException { raw.setSoTimeout(t); }
        @Override public int getSoTimeout() throws SocketException { return raw.getSoTimeout(); }
        @Override public void setTcpNoDelay(boolean v) throws SocketException { raw.setTcpNoDelay(v); }
        @Override public boolean getTcpNoDelay() throws SocketException { return raw.getTcpNoDelay(); }
        @Override public void setKeepAlive(boolean v) throws SocketException { raw.setKeepAlive(v); }
        @Override public boolean getKeepAlive() throws SocketException { return raw.getKeepAlive(); }
        @Override public void shutdownInput() throws IOException { raw.shutdownInput(); }
        @Override public void shutdownOutput() throws IOException { raw.shutdownOutput(); }
        @Override public boolean isInputShutdown() { return raw.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return raw.isOutputShutdown(); }
    }
}
