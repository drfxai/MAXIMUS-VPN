import com.example.vpn.dns.DnsMessageValidator;
import java.util.Arrays;
import java.util.Random;

public class DnsMessageValidatorCheck {
    private static int checks;
    private static void check(boolean ok) {
        checks++;
        if (!ok) throw new AssertionError("check " + checks);
    }
    private static byte[] query() {
        return new byte[]{0x12,0x34,1,0,0,1,0,0,0,0,0,0,1,'a',3,'c','o','m',0,0,1,0,1};
    }
    private static byte[] answer(byte[] q) {
        byte[] r = Arrays.copyOf(q, q.length + 16);
        r[2] = (byte)0x81; r[3] = (byte)0x80; r[7] = 1;
        byte[] rr = {(byte)0xc0,12,0,1,0,1,0,0,0,60,0,4,1,1,1,1};
        System.arraycopy(rr,0,r,q.length,rr.length);
        return r;
    }
    public static void main(String[] args) {
        byte[] q = query(), r = answer(q);
        check(DnsMessageValidator.wellFormed(q,q.length));
        check(DnsMessageValidator.isResponseTo(q,r,r.length));
        byte[] x = r.clone(); x[13]='b'; check(!DnsMessageValidator.isResponseTo(q,x,x.length));
        x=r.clone(); x[20]=28; check(!DnsMessageValidator.isResponseTo(q,x,x.length));
        x=r.clone(); x[0]++; check(!DnsMessageValidator.isResponseTo(q,x,x.length));
        x=r.clone(); x[13]='A'; check(DnsMessageValidator.isResponseTo(q,x,x.length));
        x=r.clone(); x[q.length+1]=(byte)q.length; check(!DnsMessageValidator.wellFormed(x,x.length));
        x=r.clone(); x[q.length+11]=16; check(!DnsMessageValidator.wellFormed(x,x.length));
        x=r.clone(); x[6]=127; check(!DnsMessageValidator.wellFormed(x,x.length));
        check(!DnsMessageValidator.wellFormed(r,r.length+1));
        check(!DnsMessageValidator.wellFormed(r,-1));
        check(!DnsMessageValidator.isResponseTo(q,q,q.length));
        for (int n=0;n<r.length;n++) check(!DnsMessageValidator.wellFormed(r,n));
        Random random=new Random(42);
        for(int i=0;i<20000;i++) {
            byte[] packet=new byte[random.nextInt(1024)]; random.nextBytes(packet);
            // Malformed untrusted packets must never throw or loop indefinitely.
            DnsMessageValidator.wellFormed(packet,packet.length);
            DnsMessageValidator.isResponseTo(q,packet,packet.length);
            checks++;
        }
        System.out.println("PASS: " + checks + " DNS framing, matching and malformed-packet checks");
    }
}
