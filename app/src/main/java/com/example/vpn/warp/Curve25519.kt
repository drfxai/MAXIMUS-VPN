package com.example.vpn.warp

import java.math.BigInteger
import java.security.SecureRandom

/**
 * WireGuard key pairs (X25519, RFC 7748). Android has no X25519 key generator before API 33, so the
 * Montgomery ladder is done here with [BigInteger]. It runs once per WARP registration, on a key that
 * never leaves the device, so its speed and non-constant timing do not matter here.
 */
object Curve25519 {
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val BASE_POINT = ByteArray(32).also { it[0] = 9 }

    /** 32 random bytes clamped the way WireGuard clamps a private key. */
    fun newPrivateKey(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(32).also { random.nextBytes(it) }.let(::clamp)

    /** The public key of [privateKey]. */
    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE_POINT)

    fun clamp(key: ByteArray): ByteArray {
        require(key.size == 32) { "A Curve25519 key is 32 bytes" }
        return key.copyOf().also {
            it[0] = (it[0].toInt() and 248).toByte()
            it[31] = ((it[31].toInt() and 127) or 64).toByte()
        }
    }

    /** X25519(scalar, u) of RFC 7748 section 5. */
    fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        require(u.size == 32) { "A Curve25519 point is 32 bytes" }
        val k = littleEndian(clamp(scalar))
        val x1 = littleEndian(u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val bit = if (k.testBit(t)) 1 else 0
            if (swap xor bit == 1) {
                x2 = x3.also { x3 = x2 }
                z2 = z3.also { z3 = z2 }
            }
            swap = bit
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) {
            x2 = x3
            z2 = z3
        }
        return toLittleEndian(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P))
    }

    private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun toLittleEndian(value: BigInteger): ByteArray {
        val big = value.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, big.size)) out[i] = big[big.size - 1 - i]
        return out
    }
}
