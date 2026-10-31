package main

import (
    "encoding/binary"
    "fmt"
    "net"
    "strings"
    "testing"
    "time"
)

func makeQuery(zone string) []byte {
    b:=make([]byte,12)
    binary.BigEndian.PutUint16(b[0:2],0x92AF)
    binary.BigEndian.PutUint16(b[2:4],0x0100)
    binary.BigEndian.PutUint16(b[4:6],1)
    for _,part:=range strings.Split(zone,".") {
        b=append(b,byte(len(part)))
        b=append(b,[]byte(part)...)
    }
    b=append(b,0,0,1,0,1)
    return b
}

func TestValidateRejectsOverlappingDomainsAndPublicBackend(t *testing.T) {
    for _,c:=range []Config{
        {Listen:"127.0.0.1:53000",Routes:[]Route{
            {Zone:"t.example.org",Upstream:"127.0.0.1:5301"},
            {Zone:"example.org",Upstream:"127.0.0.1:5302"}}},
        {Listen:"127.0.0.1:53000",Routes:[]Route{
            {Zone:"t.example.org",Upstream:"8.8.8.8:53"}}},
        {Listen:"127.0.0.1:53000",Routes:[]Route{
            {Zone:"t.example.org",Upstream:"127.0.0.1:5301"},
            {Zone:"s.example.org",Upstream:"127.0.0.1:5301"}}},
    } {
        if validate(&c)==nil {t.Fatal("unsafe configuration accepted:",c)}
    }
    good:=Config{Listen:"127.0.0.1:53000",Routes:[]Route{
        {Zone:"t.example.org",Upstream:"127.0.0.1:5301"},
        {Zone:"s.example.org",Upstream:"127.0.0.1:5302"},
    }}
    if err:=validate(&good);err!=nil {t.Fatal(err)}
}

func TestOnlyAuthoritativeSuffixMatches(t *testing.T) {
    c:=Config{Routes:[]Route{{Zone:"t.example.org",Upstream:"127.0.0.1:5301"}}}
    if got:=c.upstreamFor("a.t.example.org");got!="127.0.0.1:5301" {t.Fatal(got)}
    if got:=c.upstreamFor("not-t.example.org");got!="" {t.Fatal("wrong domain matched",got)}
    if got:=c.upstreamFor("t.example.org.attacker.invalid");got!="" {t.Fatal("suffix confusion",got)}
}

func TestUnknownZoneReturnsRefusedAndNeverForwards(t *testing.T) {
    c:=Config{Routes:[]Route{{Zone:"t.example.org",Upstream:"127.0.0.1:5301"}}}
    query:=makeQuery("other.example.org")
    result:=c.forward(query)
    if len(result)==0 || (binary.BigEndian.Uint16(result[2:4])&0x000F)!=5 {
        t.Fatalf("expected REFUSED, got %v",result)
    }
    if result[0]!=query[0] || result[1]!=query[1] {t.Fatal("transaction ID changed")}
}

func TestUnknownOrMalformedDNSIsNeverAccepted(t *testing.T) {
    c:=Config{}
    if got:=c.forward(make([]byte,10));len(got)>0 {t.Fatal("short query emitted reply")}
    bad:=makeQuery("t.example.org")
    bad[12]=0xC0
    if got:=c.forward(bad);len(got)>0 {t.Fatal("DNS compression pointer accepted")}
}

func TestPrivateBackendCarriesDNSPacketAndNoPublicResolver(t *testing.T) {
    backend,err:=net.ListenUDP("udp",&net.UDPAddr{IP:net.ParseIP("127.0.0.1"),Port:0})
    if err!=nil {t.Fatal(err)}
    defer backend.Close()
    done:=make(chan error,1)
    go func(){
        buf:=make([]byte,4096)
        _=backend.SetDeadline(time.Now().Add(2*time.Second))
        n,client,err:=backend.ReadFromUDP(buf)
        if err!=nil {done<-err;return}
        response:=append([]byte(nil),buf[:n]...)
        response[2]|=0x80
        _,err=backend.WriteToUDP(response,client)
        done<-err
    }()
    c:=Config{Listen:"127.0.0.1:0",Routes:[]Route{{
        Zone:"t.example.org",Upstream:fmt.Sprintf("127.0.0.1:%d",backend.LocalAddr().(*net.UDPAddr).Port),
    }}}
    query:=makeQuery("encoded-payload.t.example.org")
    reply:=c.forward(query)
    if err:=<-done;err!=nil {t.Fatal(err)}
    if len(reply)!=len(query) || reply[0]!=query[0] || reply[1]!=query[1] || reply[2]&0x80==0 {
        t.Fatalf("backend transport failed: %v",reply)
    }
}
