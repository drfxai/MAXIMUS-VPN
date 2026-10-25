// MAXIMUS adapter for Apache-2.0 github.com/getlantern/soar.
// Only the explicit resolver list is used. Destination names are sent through
// Soar, never resolved by Android. CONNECT only; no direct fallback.
package main

import (
 "context"
 "crypto/subtle"
 "encoding/base64"
 "encoding/binary"
 "encoding/json"
 "errors"
 "flag"
 "io"
 "log"
 "net"
 "net/netip"
 "os"
 "os/signal"
 "strconv"
 "syscall"
 "time"
 "github.com/getlantern/soar"
)

type tuning struct {
 Transport string
 SafeQName, MaxQName, MaxAnswer, HoldMs, MaxInflight, MaxPathWindow, MaxStreams int
 StreamWindowKiB, SendBufferKiB, SessionIdleSeconds, DialTimeoutSeconds int
}
type config struct {
 Domain, PublicKey, Listen, Username, Password string
 Resolvers []string
 Tuning tuning
}
type dialer interface { DialContext(context.Context, string, string) (net.Conn, error) }
func load(path string) (config, error) {
 var c config
 f, err := os.Open(path); if err != nil { return c, err }; defer f.Close()
 d := json.NewDecoder(io.LimitReader(f, 65537))
 d.DisallowUnknownFields()
 if err = d.Decode(&c); err != nil { return c, err }
 var extra any
 if err = d.Decode(&extra); err != io.EOF { return c, errors.New("trailing configuration") }
 addr, err := netip.ParseAddrPort(c.Listen)
 if err != nil || !addr.Addr().IsLoopback() || addr.Port()==0 { return c, errors.New("loopback listener required") }
 if len(c.Username)<1 || len(c.Username)>255 || len(c.Password)<1 || len(c.Password)>255 { return c, errors.New("SOCKS credentials required") }
 if len(c.Resolvers)<1 || len(c.Resolvers)>64 { return c, errors.New("explicit resolvers required") }
 for _, r := range c.Resolvers {
  a, err := netip.ParseAddrPort(r)
  if err != nil || a.Port()==0 || a.Addr().IsUnspecified() || a.Addr().IsLoopback() || a.Addr().IsMulticast() || a.Addr().IsLinkLocalUnicast() { return c, errors.New("invalid resolver") }
 }
 return c, nil
}
func main() {
 path := flag.String("config", "", "private configuration file"); flag.Parse()
 c, err := load(*path); if err != nil { log.Fatal("invalid SOAR configuration") }
 key, err := base64.StdEncoding.DecodeString(c.PublicKey)
 if err != nil || len(key)!=32 { log.Fatal("invalid SOAR public key") }
 t := c.Tuning
 client, err := soar.NewClient(soar.Config{Zone:c.Domain, ServerKey:key, Resolvers:c.Resolvers,
  Discovery:soar.Discovery{Enabled:false}, TCP:t.Transport,
  SafeQName:t.SafeQName, MaxQName:t.MaxQName, MaxAnswer:t.MaxAnswer,
  Hold:time.Duration(t.HoldMs)*time.Millisecond, MaxInflight:t.MaxInflight, MaxPathWindow:t.MaxPathWindow,
  MaxStreams:t.MaxStreams, StreamWindow:uint64(t.StreamWindowKiB)*1024, SendBuffer:t.SendBufferKiB*1024,
  SessionIdle:time.Duration(t.SessionIdleSeconds)*time.Second})
 if err != nil { log.Fatal("SOAR initialization failed") }; defer client.Close()
 ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM); defer stop()
 ln, err := net.Listen("tcp", c.Listen); if err != nil { log.Fatal("SOAR listener failed") }; defer ln.Close()
 go func(){ <-ctx.Done(); ln.Close(); client.Close() }()
 log.Print("SOAR local proxy ready; traffic unverified")
 slots := make(chan struct{}, 128)
 for {
  conn, err := ln.Accept(); if err != nil { return }
  select { case slots<-struct{}{}: go func(){ defer func(){<-slots}(); serve(ctx, conn, client, c) }()
   default: conn.Close() }
 }
}
func reply(c net.Conn, code byte) { c.Write([]byte{5,code,0,1,0,0,0,0,0,0}) }
func serve(ctx context.Context, c net.Conn, d dialer, cfg config) {
 defer c.Close()
 c.SetDeadline(time.Now().Add(10*time.Second))
 b:=make([]byte,260)
 if _,e:=io.ReadFull(c,b[:2]); e!=nil || b[0]!=5 { return }
 n:=int(b[1]); if _,e:=io.ReadFull(c,b[:n]); e!=nil { return }
 auth:=false; for _,m:=range b[:n] { if m==2 {auth=true} }
 if !auth {c.Write([]byte{5,255});return}; c.Write([]byte{5,2})
 if _,e:=io.ReadFull(c,b[:2]);e!=nil || b[0]!=1 {return}
 n=int(b[1]);if _,e:=io.ReadFull(c,b[:n]);e!=nil{return};user:=string(b[:n])
 if _,e:=io.ReadFull(c,b[:1]);e!=nil{return};n=int(b[0])
 if _,e:=io.ReadFull(c,b[:n]);e!=nil{return};pass:=string(b[:n])
 if subtle.ConstantTimeCompare([]byte(user),[]byte(cfg.Username)) & subtle.ConstantTimeCompare([]byte(pass),[]byte(cfg.Password)) != 1 {c.Write([]byte{1,1});return}
 c.Write([]byte{1,0})
 if _,e:=io.ReadFull(c,b[:4]);e!=nil || b[0]!=5 || b[2]!=0{return}
 if b[1]!=1 {reply(c,7);return}
 var host string
 switch b[3] {
 case 1: if _,e:=io.ReadFull(c,b[:4]);e!=nil{return};host=net.IP(b[:4]).String()
 case 4: if _,e:=io.ReadFull(c,b[:16]);e!=nil{return};host=net.IP(b[:16]).String()
 case 3: if _,e:=io.ReadFull(c,b[:1]);e!=nil{return};n=int(b[0]);if n==0{return};if _,e:=io.ReadFull(c,b[:n]);e!=nil{return};host=string(b[:n])
 default:reply(c,8);return
 }
 if _,e:=io.ReadFull(c,b[:2]);e!=nil{return};port:=binary.BigEndian.Uint16(b[:2]);if port==0 {reply(c,1);return}
 timeout:=time.Duration(cfg.Tuning.DialTimeoutSeconds)*time.Second
 if timeout<5*time.Second || timeout>120*time.Second {timeout=90*time.Second}
 c.SetDeadline(time.Now().Add(timeout))
 dc,cancel:=context.WithTimeout(ctx,timeout)
 up,err:=d.DialContext(dc,"tcp",net.JoinHostPort(host,strconv.Itoa(int(port))));cancel()
 if err!=nil {reply(c,4);return};defer up.Close()
 reply(c,0);c.SetDeadline(time.Time{})
 done:=make(chan struct{},2)
 pump := func(dst, src net.Conn) {
  _, err := io.Copy(dst, src)
  if err == nil {
   if half, ok := dst.(interface{ CloseWrite() error }); ok { err = half.CloseWrite() } else { err = dst.Close() }
  }
  if err != nil { c.Close(); up.Close() }
  done <- struct{}{}
 }
 go pump(up,c)
 go pump(c,up)
 select{case <-ctx.Done():c.Close();up.Close();return;case <-done:}
 // Preserve a response after the application half-closes its request.
 select{case <-ctx.Done():case <-done:}
 c.Close();up.Close()
}
