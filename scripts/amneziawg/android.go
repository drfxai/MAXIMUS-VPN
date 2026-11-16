// SPDX-License-Identifier: MIT
// Attach the pinned AmneziaWG core to an Android VpnService TUN.
package main

import "C"
import (
    "github.com/amnezia-vpn/amneziawg-go/v3/conn"
    "github.com/amnezia-vpn/amneziawg-go/v3/device"
    "github.com/amnezia-vpn/amneziawg-go/v3/tun"
    "golang.org/x/sys/unix"
)

var active *device.Device

//export maximusAwgStart
func maximusAwgStart(fd C.int, config *C.char) C.int {
    if active != nil {
        unix.Close(int(fd))
        return -1
    }
    link, _, err := tun.CreateUnmonitoredTUNFromFD(int(fd))
    if err != nil {
        unix.Close(int(fd))
        return -2
    }
    next := device.NewDevice(link, conn.NewStdNetBind(), device.NewLogger(device.LogLevelSilent, ""))
    if err := next.IpcSet(C.GoString(config)); err != nil {
        next.Close()
        return -3
    }
    next.DisableSomeRoamingForBrokenMobileSemantics()
    if err := next.Up(); err != nil {
        next.Close()
        return -4
    }
    active = next
    return 0
}

//export maximusAwgSocket
func maximusAwgSocket(ipv6 C.int) C.int {
    if active == nil {
        return -1
    }
    bind, ok := active.Bind().(conn.PeekLookAtSocketFd)
    if !ok {
        return -1
    }
    var socket int
    var err error
    if ipv6 != 0 {
        socket, err = bind.PeekLookAtSocketFd6()
    } else {
        socket, err = bind.PeekLookAtSocketFd4()
    }
    if err != nil {
        return -1
    }
    return C.int(socket)
}

//export maximusAwgStop
func maximusAwgStop() {
    if active != nil {
        active.Close()
        active = nil
    }
}

func main() {}
