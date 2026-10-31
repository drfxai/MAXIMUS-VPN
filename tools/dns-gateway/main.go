// MAXIMUS experimental DNS tunnel gateway.
// This is NOT a general DNS resolver. Unknown zones are REFUSED; only configured
// private loopback backends receive packets. Do not deploy to a public port before
// real DNSTT/SOAR/Cotten compatibility and TCP/53 requirements are tested.
package main

import (
    "encoding/binary"
    "encoding/json"
    "errors"
    "flag"
    "fmt"
    "log"
    "net"
    "os"
    "strings"
    "time"
)

type Route struct {
    Zone string `json:"zone"`
    Upstream string `json:"upstream"`
}
type Config struct {
    Listen string `json:"listen"`
    Routes []Route `json:"routes"`
}

const (
    maxPacket = 4096
    maxConcurrent = 128
    upstreamTimeout = 2 * time.Second
)

func domain(name string) (string,error) {
    n := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(name)), ".")
    if len(n) < 4 || len(n) > 253 { return "",errors.New("invalid DNS zone length") }
    parts:=strings.Split(n,".")
    if len(parts)<2 {return "",errors.New("expected an authoritative DNS suffix")}
    for _,s:=range parts {
        if len(s)==0 || len(s)>63 || s[0]=='-' || s[len(s)-1]=='-' {return "",errors.New("invalid DNS label")}
        for _,c:=range s { if !((c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='-') {
            return "",errors.New("invalid DNS label character")
        } }
    }
    return n,nil
}

func validate(c *Config) error {
    if len(c.Routes)==0 || len(c.Routes)>16 {return errors.New("routes must contain 1–16 zones")}
    listener,err:=net.ResolveUDPAddr("udp",c.Listen)
    if err!=nil || listener==nil || listener.Port<1 {return errors.New("invalid UDP listener")}
    for i:=range c.Routes {
        zone,err:=domain(c.Routes[i].Zone)
        if err!=nil {return err}
        c.Routes[i].Zone=zone
        host,_,err:=net.SplitHostPort(c.Routes[i].Upstream)
        if err!=nil {return errors.New("invalid backend socket")}
        dest,err:=net.ResolveUDPAddr("udp",c.Routes[i].Upstream)
        if err!=nil || dest==nil || dest.Port<1 || net.ParseIP(host)==nil || !dest.IP.IsLoopback() {
            return errors.New("backends must be explicit local loopback UDP sockets")
        }
        for j:=0;j<i;j++ {
            prev:=c.Routes[j].Zone
            if zone==prev || strings.HasSuffix(zone,"."+prev) || strings.HasSuffix(prev,"."+zone) {
                return errors.New("overlapping DNS zones cannot be delegated independently")
            }
            if c.Routes[j].Upstream==c.Routes[i].Upstream {
                return errors.New("distinct DNS zones must use separate backend sockets")
            }
        }
    }
    return nil
}

func questionName(packet []byte)(string,error) {
    if len(packet)<17 {return "",errors.New("short query")}
    flags:=binary.BigEndian.Uint16(packet[2:4])
    if flags&0x8000!=0 || flags&0x7800!=0 {return "",errors.New("not a standard DNS request")}
    if binary.BigEndian.Uint16(packet[4:6])!=1 {return "",errors.New("one DNS question required")}
    pos:=12
    var names []string
    for {
        if pos>=len(packet) {return "",errors.New("truncated DNS question")}
        size:=int(packet[pos]);pos++
        if size==0 {break}
        if size>63 || pos+size>len(packet) {return "",errors.New("unsupported compressed/invalid question")}
        names=append(names,string(packet[pos:pos+size]))
        pos+=size
        if len(names)>64 {return "",errors.New("DNS question too deep")}
    }
    if pos+4>len(packet) {return "",errors.New("question type/class missing")}
    name:=strings.ToLower(strings.Join(names,"."))
    if name=="" {return "",errors.New("root query is not a tunnel")}
    return name,nil
}

func (c Config) upstreamFor(query string) string {
    // Suffix comparison includes a dot boundary; malformed string prefixes never match.
    for _,route:=range c.Routes {
        if query==route.Zone || strings.HasSuffix(query,"."+route.Zone) {return route.Upstream}
    }
    return ""
}

// A refusal has no answers and never forwards unknown domains.
func errorResponse(query []byte, rcode uint16) []byte {
    if len(query)<12 {return nil}
    out:=append([]byte(nil),query...)
    flags:=binary.BigEndian.Uint16(query[2:4])
    binary.BigEndian.PutUint16(out[2:4],0x8000|(flags&0x0100)|(rcode&0x000F))
    binary.BigEndian.PutUint16(out[6:8],0)
    binary.BigEndian.PutUint16(out[8:10],0)
    binary.BigEndian.PutUint16(out[10:12],0)
    return out
}

func (c Config) forward(packet []byte) []byte {
    name,err:=questionName(packet)
    if err!=nil {return nil} // malformed UDP packets are dropped
    backend:=c.upstreamFor(name)
    if backend=="" {return errorResponse(packet,5)} // REFUSED, never recurse
    target,err:=net.ResolveUDPAddr("udp",backend)
    if err!=nil {return errorResponse(packet,2)}
    upstream,err:=net.DialUDP("udp",nil,target)
    if err!=nil {return errorResponse(packet,2)}
    defer upstream.Close()
    _=upstream.SetDeadline(time.Now().Add(upstreamTimeout))
    if _,err=upstream.Write(packet);err!=nil {return errorResponse(packet,2)}
    buf:=make([]byte,maxPacket)
    n,err:=upstream.Read(buf)
    if err!=nil || n<12 {return errorResponse(packet,2)}
    // DialUDP authenticates the loopback reply source, and the DNS transaction ID
    // prevents accepting unrelated data returned by an incorrect backend.
    if buf[0]!=packet[0] || buf[1]!=packet[1] || buf[2]&0x80==0 {
        return errorResponse(packet,2)
    }
    return append([]byte(nil),buf[:n]...)
}

func serve(c Config) error {
    addr,_:=net.ResolveUDPAddr("udp",c.Listen)
    listener,err:=net.ListenUDP("udp",addr)
    if err!=nil {return err}
    defer listener.Close()
    tokens:=make(chan struct{},maxConcurrent)
    log.Printf("Maximus experimental DNS gateway listening on %s (%d dedicated zones)",c.Listen,len(c.Routes))
    for {
        packet:=make([]byte,maxPacket)
        n,client,err:=listener.ReadFromUDP(packet)
        if err!=nil {return err}
        data:=packet[:n]
        select {
        case tokens<-struct{}{}:
            go func(){
                defer func(){<-tokens}()
                if response:=c.forward(data);len(response)>0 {
                    _,_=listener.WriteToUDP(response,client)
                }
            }()
        default:
            if resp:=errorResponse(data,2);len(resp)>0 {
                _,_=listener.WriteToUDP(resp,client)
            }
        }
    }
}

func main(){
    cfgPath:=flag.String("config","","Path to reviewed JSON configuration")
    flag.Parse()
    if *cfgPath=="" {log.Fatal("config path required")}
    raw,err:=os.ReadFile(*cfgPath)
    if err!=nil {log.Fatal(err)}
    if len(raw)>64*1024 {log.Fatal("configuration is too large")}
    var c Config
    if err=json.Unmarshal(raw,&c);err!=nil {log.Fatal(err)}
    if err=validate(&c);err!=nil {log.Fatal(fmt.Errorf("unsafe configuration: %w",err))}
    if err=serve(c);err!=nil {log.Fatal(err)}
}
