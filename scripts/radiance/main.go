// SPDX-License-Identifier: GPL-3.0-only
// Android entry point for Radiance in novpn mode.
package main

import (
    "context"
    "errors"
    "fmt"
    "net"
    "net/netip"
    "os"
    "os/signal"
    "syscall"

    "github.com/getlantern/radiance/backend"
    "github.com/getlantern/radiance/common/deviceid"
)

func run(ctx context.Context) error {
    if len(os.Args) == 2 && os.Args[1] == "--version" {
        fmt.Println("Radiance aced057665da")
        return nil
    }
    address, err := netip.ParseAddrPort(os.Getenv("RADIANCE_SOCKS_ADDRESS"))
    if err != nil || address.Addr() != netip.MustParseAddr("127.0.0.1") || address.Port() == 0 ||
        os.Getenv("MAXIMUS_SOCKS_USER") == "" || os.Getenv("MAXIMUS_SOCKS_PASS") == "" {
        return errors.New("missing authenticated loopback settings")
    }
    dataDir := os.Getenv("RADIANCE_DATA_PATH")
    if dataDir == "" {
        return errors.New("missing app-private Radiance data directory")
    }
    core, err := backend.NewLocalBackend(ctx, backend.Options{
        DataDir: dataDir, LogDir: dataDir, LogLevel: "error",
        DeviceID: deviceid.Get(dataDir), TelemetryConsent: false,
    })
    if err != nil {
        return errors.New("Radiance initialization failed")
    }
    defer core.Close()
    core.Start()
    if err := core.ConnectVPN(ctx, ""); err != nil {
        return classifyConnectError(err)
    }
    fmt.Println("MAXIMUS_RADIANCE_READY")
    <-ctx.Done()
    return nil
}

// Export only stable categories. Upstream errors may contain private routes or tokens.
func classifyConnectError(err error) error {
    var dnsErr *net.DNSError
    var netErr net.Error
    switch {
    case errors.Is(err, context.Canceled):
        return errors.New("Radiance connection was canceled")
    case errors.Is(err, context.DeadlineExceeded):
        return errors.New("Radiance connection timed out")
    case errors.As(err, &dnsErr):
        return errors.New("Radiance could not resolve a connection host")
    case errors.As(err, &netErr) && netErr.Timeout():
        return errors.New("Radiance connection timed out")
    case errors.Is(err, syscall.EADDRINUSE):
        return errors.New("Radiance loopback port is already in use")
    case errors.Is(err, os.ErrPermission):
        return errors.New("Radiance connection permission denied")
    default:
        return errors.New("Radiance could not start a Lantern route")
    }
}

func main() {
    ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
    defer stop()
    if err := run(ctx); err != nil {
        fmt.Fprintln(os.Stderr, err)
        os.Exit(1)
    }
}
