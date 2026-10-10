// maximus-socks is the small SOCKS5 server behind the DNS tunnel (dnstt-server forwards each tunnel
// stream to it). It listens on a loopback address only, takes no login (dnstt-client cannot send
// one) and supports CONNECT only. It refuses destinations on the server itself or on private
// networks, so the tunnel can reach the internet but not the server's own services (an admin panel
// on 127.0.0.1, a cloud metadata address, the provider's internal network).
package main

import (
	"context"
	"encoding/binary"
	"errors"
	"flag"
	"io"
	"log"
	"net"
	"net/netip"
	"strconv"
	"time"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:1080", "loopback address to listen on")
	flag.Parse()
	host, _, err := net.SplitHostPort(*listen)
	if err != nil {
		log.Fatalf("bad -listen: %v", err)
	}
	if ip, err := netip.ParseAddr(host); err != nil || !ip.IsLoopback() {
		log.Fatalf("-listen must be a loopback address, got %q", host)
	}
	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("listening on %s", *listen)
	for {
		c, err := ln.Accept()
		if err != nil {
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				continue
			}
			log.Fatal(err)
		}
		go serve(c)
	}
}

// blocked reports addresses the tunnel must never reach.
func blocked(ip netip.Addr) bool {
	ip = ip.Unmap()
	return !ip.IsValid() || ip.IsLoopback() || ip.IsPrivate() || ip.IsUnspecified() ||
		ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsMulticast() ||
		cgnat.Contains(ip) || benchmark.Contains(ip)
}

var (
	cgnat     = netip.MustParsePrefix("100.64.0.0/10")
	benchmark = netip.MustParsePrefix("198.18.0.0/15")
)

const (
	repSuccess     = 0x00
	repFailure     = 0x01
	repNotAllowed  = 0x02
	repUnreachable = 0x04
	repCmdNotSup   = 0x07
	repAddrNotSup  = 0x08
)

func reply(c net.Conn, code byte) {
	c.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
}

func serve(c net.Conn) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(30 * time.Second))
	buf := make([]byte, 262)
	// Greeting: VER NMETHODS METHODS...
	if _, err := io.ReadFull(c, buf[:2]); err != nil || buf[0] != 5 {
		return
	}
	n := int(buf[1])
	if _, err := io.ReadFull(c, buf[:n]); err != nil {
		return
	}
	noAuth := false
	for _, m := range buf[:n] {
		if m == 0 {
			noAuth = true
		}
	}
	if !noAuth {
		c.Write([]byte{5, 0xff})
		return
	}
	c.Write([]byte{5, 0})
	// Request: VER CMD RSV ATYP DST.ADDR DST.PORT
	if _, err := io.ReadFull(c, buf[:4]); err != nil || buf[0] != 5 {
		return
	}
	if buf[1] != 1 {
		reply(c, repCmdNotSup)
		return
	}
	var host string
	switch buf[3] {
	case 1:
		if _, err := io.ReadFull(c, buf[:4]); err != nil {
			return
		}
		host = netip.AddrFrom4([4]byte(buf[:4])).String()
	case 4:
		if _, err := io.ReadFull(c, buf[:16]); err != nil {
			return
		}
		host = netip.AddrFrom16([16]byte(buf[:16])).String()
	case 3:
		if _, err := io.ReadFull(c, buf[:1]); err != nil {
			return
		}
		l := int(buf[0])
		if _, err := io.ReadFull(c, buf[:l]); err != nil {
			return
		}
		host = string(buf[:l])
	default:
		reply(c, repAddrNotSup)
		return
	}
	if _, err := io.ReadFull(c, buf[:2]); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(buf[:2])

	// Resolve here and dial the checked address, so a name cannot point the tunnel inward.
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
	cancel()
	if err != nil || len(ips) == 0 {
		reply(c, repUnreachable)
		return
	}
	var target netip.Addr
	for _, ip := range ips {
		if !blocked(ip) {
			target = ip.Unmap()
			break
		}
	}
	if !target.IsValid() {
		reply(c, repNotAllowed)
		return
	}
	up, err := net.DialTimeout("tcp", net.JoinHostPort(target.String(), strconv.Itoa(int(port))), 15*time.Second)
	if err != nil {
		reply(c, repUnreachable)
		return
	}
	defer up.Close()
	reply(c, repSuccess)
	c.SetDeadline(time.Time{})
	done := make(chan struct{}, 2)
	go func() { io.Copy(up, c); closeWrite(up); done <- struct{}{} }()
	go func() { io.Copy(c, up); closeWrite(c); done <- struct{}{} }()
	<-done
	<-done
}

func closeWrite(c net.Conn) {
	if t, ok := c.(*net.TCPConn); ok {
		t.CloseWrite()
	}
}
