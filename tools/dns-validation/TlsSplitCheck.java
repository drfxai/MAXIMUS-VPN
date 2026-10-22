import com.example.vpn.tls.TlsHelloSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.net.ssl.*;

public class TlsSplitCheck {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("check "+checks); }
    private static SSLContext fixture(String path) throws Exception {
        KeyStore ks=KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(Base64.getMimeDecoder().decode(Files.readAllBytes(Path.of(path)))),"testfixture".toCharArray());
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(ks,"testfixture".toCharArray());
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(ks);
        SSLContext c=SSLContext.getInstance("TLS"); c.init(km.getKeyManagers(),tm.getTrustManagers(),null); return c;
    }
    private static boolean handshake(SSLContext serverContext, SSLContext clientContext, String host) throws Exception {
        try(SSLServerSocket server=(SSLServerSocket)serverContext.getServerSocketFactory().createServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(4000);
            Thread thread=new Thread(()->{
                try(SSLSocket s=(SSLSocket)server.accept()) {
                    s.setSoTimeout(4000); s.startHandshake();
                    if(s.getInputStream().read()==42) { s.getOutputStream().write(43); s.getOutputStream().flush(); }
                } catch(Exception expectedOnRejectedClient) {}
            }); thread.setDaemon(true); thread.start();
            boolean ok=false;
            try(Socket raw=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());
                SSLSocket tls=(SSLSocket)new TlsHelloSocketFactory(clientContext.getSocketFactory(),32,0).createSocket(raw,host,server.getLocalPort(),true)) {
                tls.setSoTimeout(4000);
                SSLParameters params=tls.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS"); tls.setSSLParameters(params);
                tls.startHandshake(); tls.getOutputStream().write(42); tls.getOutputStream().flush(); ok=tls.getInputStream().read()==43;
            } catch(SSLException expectedOnRejectedCertificate) {}
            thread.join(5000); check(!thread.isAlive()); return ok;
        }
    }
    public static void main(String[] args) throws Exception {
        final List<Integer> sizes=new ArrayList<>(); ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        OutputStream sink=new OutputStream(){ public void write(int b){bytes.write(b);} public void write(byte[] b,int o,int n){ sizes.add(n);bytes.write(b,o,n);} };
        byte[] hello=new byte[513]; new Random(5).nextBytes(hello); hello[0]=22;hello[1]=3;
        OutputStream split=TlsHelloSocketFactory.splitFirstHandshake(sink,32,0); split.write(hello);
        check(Arrays.equals(hello,bytes.toByteArray())); check(sizes.size()>1); check(Collections.max(sizes)<=32);
        sizes.clear(); split.write(hello); check(sizes.size()==1);
        final int[] written={0};
        OutputStream failing=new OutputStream(){public void write(int b) throws IOException {throw new IOException();} public void write(byte[] b,int o,int n)throws IOException{written[0]++; if(written[0]==2)throw new IOException("stop");}};
        try { TlsHelloSocketFactory.splitFirstHandshake(failing,32,0).write(hello); throw new AssertionError(); } catch(IOException expected) { check(written[0]==2); }
        SSLContext c=fixture(args[0]);
        check(handshake(c,c,"127.0.0.1"));
        check(!handshake(c,c,"wrong.invalid"));
        check(!handshake(c,SSLContext.getDefault(),"127.0.0.1"));
        System.out.println("PASS: "+checks+" TLS split checks, including real TLS, host-name rejection and trust rejection");
    }
}
