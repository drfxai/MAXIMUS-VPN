package main
import (
    "testing"
    "time"
)
func TestJitterBounds(t *testing.T) {
    interval := 260 * time.Millisecond
    seen := make(map[time.Duration]bool)
    for i:=0; i<1000; i++ {
        d:=jitterDelay(interval)
        if d<0 || d>interval/4 { t.Fatal("jitter outside bounds", d) }
        seen[d]=true
    }
    if len(seen)<2 { t.Fatal("jitter is constant") }
    if jitterDelay(0)!=0 { t.Fatal("uncapped mode delayed") }
}
func TestPacerNeverSendsAboveCap(t *testing.T) {
    p:=newQueryPacer(4)
    for i:=0;i<4;i++ {
        before:=time.Now()
        p.wait()
        if i>0 && time.Since(before)<250*time.Millisecond { t.Fatal("pacer exceeded 4 QPS") }
    }
}
