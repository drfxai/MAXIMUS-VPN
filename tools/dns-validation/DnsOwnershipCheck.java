import com.example.vpn.dns.DnsMessageValidator;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

/** Real DNS frames: unrelated owners, compressed CNAMEs, loops and malformed alias RDATA. */
public class DnsOwnershipCheck {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("ownership check " + checks);
    }
    private static byte[] name(String host) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String label : host.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes.length); out.write(bytes);
        }
        out.write(0); return out.toByteArray();
    }
    private static byte[] query() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x12,0x34,1,0,0,1,0,0,0,0,0,0});
        out.write(name("a.example")); out.write(new byte[]{0,1,0,1});
        return out.toByteArray();
    }
    private static byte[] record(String owner, int type, byte[] data) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(name(owner)); out.writeShort(type); out.writeShort(1); out.writeInt(60);
        out.writeShort(data.length); out.write(data); return bytes.toByteArray();
    }
    private static byte[] response(byte[] query, byte[]... records) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = query.clone(); header[2]=(byte)0x81; header[3]=(byte)0x80;
        header[7]=(byte)records.length; out.write(header);
        for (byte[] r : records) out.write(r);
        return out.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        byte[] q = query(), ip = {1,1,1,1};
        byte[] direct = response(q, record("A.EXAMPLE",1,ip));
        check(Arrays.equals(ip,DnsMessageValidator.addressAnswers(q,direct,direct.length).get(0)));
        byte[] unrelated = response(q,record("other.example",1,ip));
        check(DnsMessageValidator.addressAnswers(q,unrelated,unrelated.length).isEmpty());
        byte[] alias = record("a.example",5,name("b.example"));
        byte[] chain = response(q,record("b.example",1,ip),alias);
        check(Arrays.equals(ip,DnsMessageValidator.addressAnswers(q,chain,chain.length).get(0)));
        byte[] compressed = response(q,record("b.example",5,new byte[]{(byte)0xc0,12}),record("a.example",1,ip));
        // An unrelated compressed alias does not stop the real question's direct address.
        check(DnsMessageValidator.addressAnswers(q,compressed,compressed.length).size()==1);
        byte[] cycle = response(q,alias,record("b.example",5,new byte[]{(byte)0xc0,12}));
        check(DnsMessageValidator.addressAnswers(q,cycle,cycle.length).isEmpty());
        byte[] conflict = response(q,alias,record("a.example",5,name("c.example")),record("b.example",1,ip));
        check(DnsMessageValidator.addressAnswers(q,conflict,conflict.length).isEmpty());
        byte[] mixed = response(q,alias,record("a.example",1,ip));
        check(DnsMessageValidator.addressAnswers(q,mixed,mixed.length).isEmpty());
        byte[] malformed = response(q,record("a.example",5,new byte[]{(byte)0xc0}));
        check(!DnsMessageValidator.wellFormed(malformed,malformed.length));
        byte[] trailing = response(q,record("a.example",5,new byte[]{(byte)0xc0,12,0}));
        check(!DnsMessageValidator.wellFormed(trailing,trailing.length));
        byte[] truncated = direct.clone(); truncated[2] |= 2;
        check(DnsMessageValidator.addressAnswers(q,truncated,truncated.length).isEmpty());
        byte[] servfail = direct.clone(); servfail[3] |= 2;
        check(DnsMessageValidator.addressAnswers(q,servfail,servfail.length).isEmpty());
        Random rnd = new Random(73);
        for (int i=0;i<5000;i++) {
            byte[] data = chain.clone(); data[rnd.nextInt(data.length)] ^= (byte)(1+rnd.nextInt(255));
            DnsMessageValidator.addressAnswers(q,data,data.length); checks++;
        }
        System.out.println("PASS: " + checks + " DNS ownership, CNAME and mutation checks");
    }
}
