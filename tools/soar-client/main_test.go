package main

import (
 "context"
 "crypto/ed25519"
 "crypto/rand"
 "io"
 "net"
 "testing"
 "time"
 "github.com/getlantern/soar"
)
func authenticate(t *testing.T, c net.Conn) {
 t.Helper(); c.SetDeadline(time.Now().Add(10*time.Second))
 c.Write([]byte{5,1,2}); b:=make([]byte,2); if _,err:=io.ReadFull(c,b);err!=nil || b[1]!=2 {t.Fatal("method negotiation",err,b)}
 c.Write([]byte{1,1,'u',1,'p'});if _,err:=io.ReadFull(c,b);err!=nil || b[1]!=0 {t.Fatal("authentication",err,b)}
}
func TestEncryptedSoarTransferThroughAuthenticatedSocks(t *testing.T) {
 pub,priv,err:=ed25519.GenerateKey(rand.Reader);if err!=nil{t.Fatal(err)}
 pc,err:=net.ListenPacket("udp","127.0.0.1:0");if err!=nil{t.Fatal(err)};defer pc.Close()
 ctx,cancel:=context.WithCancel(context.Background());defer cancel()
 targets:=make(chan string,1)
 go soar.Serve(ctx,pc,soar.ServerConfig{Zone:"t.example.com",PrivateKey:priv,Dial:func(ctx context.Context,network,address string)(net.Conn,error){
  targets<-address
  a,b:=net.Pipe();go func(){defer b.Close();io.Copy(b,b)}();return a,nil
 }})
 cl,err:=soar.NewClient(soar.Config{Zone:"t.example.com",ServerKey:pub,Resolvers:[]string{pc.LocalAddr().String()},Discovery:soar.Discovery{Enabled:false}})
 if err!=nil{t.Fatal(err)};defer cl.Close()
 a,b:=net.Pipe();defer b.Close();go serve(ctx,a,cl,config{Username:"u",Password:"p",Tuning:tuning{DialTimeoutSeconds:5}})
 authenticate(t,b)
 host:="never-resolve-on-device.example"; req:=append([]byte{5,1,0,3,byte(len(host))},[]byte(host)...);req=append(req,1,187)
 b.Write(req);reply:=make([]byte,10);if _,err:=io.ReadFull(b,reply);err!=nil || reply[1]!=0{t.Fatal("CONNECT",err,reply)}
 payload:=[]byte("verified encrypted round trip");b.Write(payload);got:=make([]byte,len(payload));if _,err:=io.ReadFull(b,got);err!=nil||string(got)!=string(payload){t.Fatal("transfer",err)}
 select {case target:=<-targets:if target!=host+":443"{t.Fatal(target)};case <-time.After(time.Second):t.Fatal("missing intended target")}
}
func TestNoAuthenticationAndUDPDoNotDial(t *testing.T) {
 for _,udp:=range []bool{false,true}{
  ctx,cancel:=context.WithCancel(context.Background())
  a,b:=net.Pipe();go serve(ctx,a,nil,config{Username:"u",Password:"p"})
  b.SetDeadline(time.Now().Add(time.Second))
  if udp {authenticate(t,b);b.Write([]byte{5,3,0,1});r:=make([]byte,10);io.ReadFull(b,r);if r[1]!=7{t.Fatal(r)}} else {
   b.Write([]byte{5,1,0});r:=make([]byte,2);io.ReadFull(b,r);if r[1]!=255{t.Fatal(r)}
  }
  b.Close();cancel()
 }
}
