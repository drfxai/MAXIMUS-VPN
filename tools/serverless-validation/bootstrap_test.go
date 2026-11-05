package maximus_test

import (
    "encoding/json"
    "encoding/pem"
    "io"
    "net/http"
    "net/http/httptest"
    "net/url"
    "os"
    "path/filepath"
    "sync/atomic"
    "testing"

    "github.com/xtls/xray-core/core"
    "github.com/xtls/xray-core/features/dns"
    _ "github.com/xtls/xray-core/main/distro/all"
    "golang.org/x/net/dns/dnsmessage"
)

// The normal traffic smoke test uses a literal target IP, which never exercises
// the DNS bootstrap. Resolve a non-existent public name through local HTTPS/2
// resolvers instead: one fails, the other must answer without system DNS.
func TestMaximusDohBootstrapAndFallback(t *testing.T) {
    var failed, answered atomic.Int32
    bad := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        failed.Add(1)
        w.WriteHeader(http.StatusServiceUnavailable)
    }))
    good := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        data, err := io.ReadAll(io.LimitReader(r.Body, 4096))
        if err != nil { t.Error(err); return }
        var question dnsmessage.Message
        if err := question.Unpack(data); err != nil { t.Error(err); return }
        if len(question.Questions) != 1 { t.Error("missing DNS question"); return }
        answer := dnsmessage.Message{
            Header: dnsmessage.Header{ID: question.ID, Response: true, RecursionAvailable: true},
            Questions: question.Questions,
            Answers: []dnsmessage.Resource{{
                Header: dnsmessage.ResourceHeader{Name: question.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60},
                Body: &dnsmessage.AResource{A: [4]byte{203, 0, 113, 42}},
            }},
        }
        body, err := answer.Pack()
        if err != nil { t.Error(err); return }
        answered.Add(1)
        w.Header().Set("Content-Type", "application/dns-message")
        _, _ = w.Write(body)
    }))
    for _, server := range []*httptest.Server{bad, good} {
        server.EnableHTTP2 = true
        server.StartTLS()
        defer server.Close()
    }
    ca := filepath.Join(t.TempDir(), "roots.pem")
    var bundle []byte
    for _, server := range []*httptest.Server{bad, good} {
        bundle = append(bundle, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: server.Certificate().Raw})...)
    }
    if err := os.WriteFile(ca, bundle, 0600); err != nil { t.Fatal(err) }
    t.Setenv("SSL_CERT_FILE", ca)
    files, err := filepath.Glob(filepath.Join(os.Getenv("MAXIMUS_SERVERLESS_FIXTURES"), "*.json"))
    if err != nil || len(files) != 3 { t.Fatalf("expected three production fixtures: %v %v", files, err) }
    for _, file := range files {
        t.Run(filepath.Base(file), func(t *testing.T) {
            data, err := os.ReadFile(file)
            if err != nil { t.Fatal(err) }
            var config map[string]any
            if err := json.Unmarshal(data, &config); err != nil { t.Fatal(err) }
            // Preserve production routing, masks and DNS settings; relocate only
            // the upstreams and their pinned bootstrap IPs to our local servers.
            d := config["dns"].(map[string]any)
            servers := d["servers"].([]any)
            for i, server := range []*httptest.Server{bad, good} {
                u, _ := url.Parse(server.URL)
                // httptest's certificate includes example.com, not bootstrap.invalid.
                servers[i].(map[string]any)["address"] = "https+one://example.com:" + u.Port() + "/dns-query"
            }
            d["hosts"] = map[string]any{"example.com": []string{"127.0.0.1"}}
            delete(config, "inbounds")
            data, err = json.Marshal(config)
            if err != nil { t.Fatal(err) }
            instance, err := core.StartInstance("json", data)
            if err != nil { t.Fatal(err) }
            defer instance.Close()
            beforeBad, beforeGood := failed.Load(), answered.Load()
            resolver := instance.GetFeature(dns.ClientType()).(dns.Client)
            ips, _, err := resolver.LookupIP("maximus-probe.invalid", dns.IPOption{IPv4Enable: true})
            if err != nil || len(ips) != 1 || ips[0].String() != "203.0.113.42" {
                t.Fatalf("bootstrap and fallback failed: %v %v", ips, err)
            }
            if failed.Load() <= beforeBad || answered.Load() <= beforeGood {
                t.Fatal("both failed primary and successful fallback must be exercised")
            }
        })
    }
}
