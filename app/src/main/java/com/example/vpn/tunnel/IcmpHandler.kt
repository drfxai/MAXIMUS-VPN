package com.example.vpn.tunnel

import com.example.vpn.packet.IPv4Header
import com.example.vpn.packet.PacketBuilder
import com.example.xray.XrayLogManager

class IcmpHandler(
    private val sendToTun: (ByteArray) -> Unit,
    private val onTraffic: (sent: Long, received: Long) -> Unit
) {

    fun handleIcmpPacket(
        ipHeader: IPv4Header,
        packetData: ByteArray,
        length: Int
    ) {
        // Bound the reply to the declared IPv4 length; ignore trailing TUN bytes.
        val ipHeaderLength = ipHeader.ihl * 4
        val packetLength = ipHeader.totalLength
        if (packetLength < ipHeaderLength + 8 || packetLength > length) return
        val reply = PacketBuilder.buildIcmpReply(packetData, packetLength)
        if (reply != null) {
            onTraffic(packetLength.toLong(), reply.size.toLong())
            sendToTun(reply)
        }
    }
}
