package maximus_test

import (
    "bufio"
    "encoding/json"
    "fmt"
    "io"
    "net"
    "net/http"
    "net/http/httptest"
    "os"
    "os/exec"
    "path/filepath"
    "testing"
    "time"

    "github.com/xtls/xray-core/core"
    "github.com/xtls/xray-core/features/dns"
    xproxy "golang.org/x/net/proxy"
)

// A separate process matches Android: Xray's system dialer uses process-global DNS.
// Two instances in one process would overwrite each other's resolver.
func TestBuiltinSidecarProcess(t *testing.T) {
    config := os.Getenv("MAXIMUS_TEST_SOCKS_CONFIG")
    if config == "" { t.Skip("child process only") }
    server, err := core.StartInstance("json", []byte(config))
    if err != nil { t.Fatal(err) }
    defer server.Close()
    _, _ = io.Copy(io.Discard, os.Stdin)
}

// Exercise the generated Android host configuration, not only the sidecar port.
// A browser's DNS answer must be restored to its domain before the SOCKS hop.
func TestBuiltinHostDomainHandoff(t *testing.T) {
    target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        if r.Host != "maximus-app.invalid" { t.Errorf("wrong host %q", r.Host) }
        _, _ = io.WriteString(w, "maximus-domain-handoff")
    }))
    defer target.Close()
    targetPort := target.Listener.Addr().(*net.TCPAddr).Port
    freePort := func() int {
        listener, err := net.Listen("tcp", "127.0.0.1:0")
        if err != nil { t.Fatal(err) }
        port := listener.Addr().(*net.TCPAddr).Port
        _ = listener.Close()
        return port
    }
    innerPort := freePort()
    innerJSON := fmt.Sprintf(`{
        "inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"socks",
          "settings":{"auth":"password","accounts":[{"user":"test-user","pass":"test-pass"}]}}],
        "dns":{"hosts":{"maximus-app.invalid":"127.0.0.1"}},
        "outbounds":[{"protocol":"freedom","settings":{"domainStrategy":"UseIP"}}]
    }`, innerPort)
    inner := exec.Command(os.Args[0], "-test.run=^TestBuiltinSidecarProcess$")
    inner.Env = append(os.Environ(), "MAXIMUS_TEST_SOCKS_CONFIG="+innerJSON)
    input, err := inner.StdinPipe()
    if err != nil { t.Fatal(err) }
    inner.Stdout, inner.Stderr = os.Stdout, os.Stderr
    if err := inner.Start(); err != nil { t.Fatal(err) }
    defer func() { _ = input.Close(); _ = inner.Process.Kill(); _ = inner.Wait() }()
    deadline := time.Now().Add(5*time.Second)
    for {
        socket, dialErr := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", innerPort), 100*time.Millisecond)
        if dialErr == nil { _ = socket.Close(); break }
        if time.Now().After(deadline) { t.Fatalf("sidecar did not listen: %v", dialErr) }
        time.Sleep(20*time.Millisecond)
    }

    dir := filepath.Join(filepath.Dir(os.Getenv("MAXIMUS_SERVERLESS_FIXTURES")), "host-fixtures")
    files, err := filepath.Glob(filepath.Join(dir, "*.json"))
    if err != nil || len(files) != 2 { t.Fatalf("expected both host fixtures: %v %v", files, err) }
    for _, file := range files {
        t.Run(filepath.Base(file), func(t *testing.T) {
            data, err := os.ReadFile(file)
            if err != nil { t.Fatal(err) }
            var config map[string]any
            if err := json.Unmarshal(data, &config); err != nil { t.Fatal(err) }
            first := config["outbounds"].([]any)[0].(map[string]any)
            first["settings"].(map[string]any)["servers"].([]any)[0].(map[string]any)["port"] = innerPort
            // The SOCKS adapter uses the same fakedns sniffer the Android TUN installs.
            outerPort := freePort()
            config["inbounds"] = []any{map[string]any{
                "listen":"127.0.0.1", "port":outerPort, "protocol":"socks",
                "settings":map[string]any{"auth":"noauth"},
                "sniffing":map[string]any{"enabled":true, "destOverride":[]string{"fakedns"}, "routeOnly":false},
            }}
            data, _ = json.Marshal(config)
            outer, err := core.StartInstance("json", data)
            if err != nil { t.Fatal(err) }
            defer outer.Close()
            start := time.Now()
            client := outer.GetFeature(dns.ClientType()).(dns.Client)
            ips, _, err := client.LookupIP("maximus-app.invalid", dns.IPOption{IPv4Enable:true, FakeEnable:true})
            _, fakePool, _ := net.ParseCIDR("198.18.0.0/15")
            if err != nil || len(ips) == 0 || !fakePool.Contains(ips[0]) {
                t.Fatalf("application DNS did not use the host FakeDNS pool: %v %v", ips, err)
            }
            if time.Since(start) > time.Second { t.Fatal("app DNS waited for an external resolver") }
            dialer, err := xproxy.SOCKS5("tcp", fmt.Sprintf("127.0.0.1:%d", outerPort), nil, &net.Dialer{Timeout:3*time.Second})
            if err != nil { t.Fatal(err) }
            socket, err := dialer.Dial("tcp", net.JoinHostPort(ips[0].String(), fmt.Sprint(targetPort)))
            if err != nil { t.Fatal(err) }
            defer socket.Close()
            _ = socket.SetDeadline(time.Now().Add(3*time.Second))
            _, err = fmt.Fprintf(socket, "GET / HTTP/1.1\r\nHost: maximus-app.invalid\r\nConnection: close\r\n\r\n")
            if err != nil { t.Fatal(err) }
            response, err := http.ReadResponse(bufio.NewReader(socket), nil)
            if err != nil { t.Fatal(err) }
            defer response.Body.Close()
            body, err := io.ReadAll(response.Body)
            if err != nil { t.Fatal(err) }
            if string(body) != "maximus-domain-handoff" {
                t.Fatalf("domain was not restored across the authenticated sidecar hop: %q", body)
            }
        })
    }
}
