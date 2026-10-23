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
    private static boolean handshake(SSLContext serverContext, SSLContext clientContext, String host, boolean records) throws Exception {
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
                SSLSocket tls=(SSLSocket)new TlsHelloSocketFactory(clientContext.getSocketFactory(),32,0,records).createSocket(raw,host,server.getLocalPort(),true)) {
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
        for(boolean records : new boolean[]{false,true}) {
            check(handshake(c,c,"127.0.0.1",records));
            check(!handshake(c,c,"wrong.invalid",records));
            check(!handshake(c,SSLContext.getDefault(),"127.0.0.1",records));
        }
        // Feed a record one byte at a time; recover the same handshake payload from the output records.
        byte[] payload=new byte[513]; new Random(7).nextBytes(payload); payload[0]=1;
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();
        OutputStream records=new com.example.vpn.tls.ClientHelloRecords(encoded,32);
        byte[] h={22,3,1,2,1}; for(byte b:h) records.write(b);
        for(byte b:payload) records.write(b);
        ByteArrayInputStream in=new ByteArrayInputStream(encoded.toByteArray()); ByteArrayOutputStream recovered=new ByteArrayOutputStream();
        while(in.available()>0) {
            byte[] header=in.readNBytes(5); check(header.length==5 && header[0]==22);
            int n=((header[3]&255)<<8)|(header[4]&255); check(n>0 && n<=32);
            recovered.write(in.readNBytes(n));
        }
        check(Arrays.equals(payload,recovered.toByteArray()));
        int before=encoded.size(); records.write(payload); check(encoded.size()==before+payload.length);
        System.out.println("PASS: "+checks+" TLS split checks, including real TLS, host-name rejection and trust rejection");
    }
}
