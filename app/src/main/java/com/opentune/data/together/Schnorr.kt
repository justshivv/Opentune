package com.opentune.data.together

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP-340 Schnorr signatures over secp256k1, which is what Nostr relays
 * check every event against. Written out here rather than pulled in as a
 * native library: rooms sign a handful of small events a minute, so plain
 * [BigInteger] arithmetic is fast enough and adds nothing to the APK.
 */
object Schnorr {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val THREE = BigInteger.valueOf(3)
    private val SEVEN = BigInteger.valueOf(7)
    private val random = SecureRandom()

    /** A point in Jacobian coordinates; z = 0 is the point at infinity. */
    private class Jac(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        val infinite get() = z.signum() == 0
    }

    private class Affine(val x: BigInteger, val y: BigInteger)

    private val G = Jac(GX, GY, BigInteger.ONE)
    private val INF = Jac(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private fun double(a: Jac): Jac {
        if (a.infinite || a.y.signum() == 0) return INF
        val ysq = a.y.multiply(a.y).mod(P)
        val s = BigInteger.valueOf(4).multiply(a.x).multiply(ysq).mod(P)
        val m = THREE.multiply(a.x).multiply(a.x).mod(P)
        val x = m.multiply(m).subtract(s.shiftLeft(1)).mod(P)
        val y = m.multiply(s.subtract(x)).subtract(BigInteger.valueOf(8).multiply(ysq).multiply(ysq)).mod(P)
        val z = a.y.multiply(a.z).shiftLeft(1).mod(P)
        return Jac(x, y, z)
    }

    private fun add(a: Jac, b: Jac): Jac {
        if (a.infinite) return b
        if (b.infinite) return a
        val z1z1 = a.z.multiply(a.z).mod(P)
        val z2z2 = b.z.multiply(b.z).mod(P)
        val u1 = a.x.multiply(z2z2).mod(P)
        val u2 = b.x.multiply(z1z1).mod(P)
        val s1 = a.y.multiply(b.z).multiply(z2z2).mod(P)
        val s2 = b.y.multiply(a.z).multiply(z1z1).mod(P)
        if (u1 == u2) return if (s1 == s2) double(a) else INF
        val h = u2.subtract(u1).mod(P)
        val r = s2.subtract(s1).mod(P)
        val hh = h.multiply(h).mod(P)
        val hhh = h.multiply(hh).mod(P)
        val v = u1.multiply(hh).mod(P)
        val x = r.multiply(r).subtract(hhh).subtract(v.shiftLeft(1)).mod(P)
        val y = r.multiply(v.subtract(x)).subtract(s1.multiply(hhh)).mod(P)
        val z = a.z.multiply(b.z).multiply(h).mod(P)
        return Jac(x, y, z)
    }

    private fun mul(p: Jac, k: BigInteger): Jac {
        var r = INF
        var q = p
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) r = add(r, q)
            q = double(q)
        }
        return r
    }

    private fun affine(p: Jac): Affine? {
        if (p.infinite) return null
        val zi = p.z.modInverse(P)
        val zi2 = zi.multiply(zi).mod(P)
        return Affine(p.x.multiply(zi2).mod(P), p.y.multiply(zi2).multiply(zi).mod(P))
    }

    private fun liftX(x: BigInteger): Affine? {
        if (x >= P) return null
        val c = x.modPow(THREE, P).add(SEVEN).mod(P)
        val y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.multiply(y).mod(P) != c) return null
        return Affine(x, if (y.testBit(0)) P.subtract(y) else y)
    }

    private fun bytes32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    private fun int(b: ByteArray) = BigInteger(1, b)

    private fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val t = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray())
        val md = MessageDigest.getInstance("SHA-256")
        md.update(t); md.update(t)
        parts.forEach(md::update)
        return md.digest()
    }

    /** A new private key: 32 random bytes in range. */
    fun newPrivateKey(): ByteArray {
        while (true) {
            val b = ByteArray(32).also(random::nextBytes)
            val d = int(b)
            if (d.signum() > 0 && d < N) return b
        }
    }

    /** The x-only public key for [privateKey]. */
    fun publicKey(privateKey: ByteArray): ByteArray = bytes32(affine(mul(G, int(privateKey)))!!.x)

    /** Signs the 32-byte [message]; [aux] is fresh randomness, fixed only in tests. */
    fun sign(message: ByteArray, privateKey: ByteArray, aux: ByteArray = ByteArray(32).also(random::nextBytes)): ByteArray {
        require(message.size == 32 && aux.size == 32)
        val d0 = int(privateKey)
        require(d0.signum() > 0 && d0 < N) { "bad key" }
        val pub = affine(mul(G, d0))!!
        val d = if (pub.y.testBit(0)) N.subtract(d0) else d0
        val px = bytes32(pub.x)
        val t = bytes32(d.xor(int(taggedHash("BIP0340/aux", aux))))
        val k0 = int(taggedHash("BIP0340/nonce", t, px, message)).mod(N)
        require(k0.signum() != 0)
        val r = affine(mul(G, k0))!!
        val k = if (r.y.testBit(0)) N.subtract(k0) else k0
        val rx = bytes32(r.x)
        val e = int(taggedHash("BIP0340/challenge", rx, px, message)).mod(N)
        return rx + bytes32(k.add(e.multiply(d)).mod(N))
    }

    fun verify(message: ByteArray, publicKey: ByteArray, signature: ByteArray): Boolean {
        if (message.size != 32 || publicKey.size != 32 || signature.size != 64) return false
        val pt = liftX(int(publicKey)) ?: return false
        val r = int(signature.copyOfRange(0, 32))
        val s = int(signature.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = int(taggedHash("BIP0340/challenge", signature.copyOfRange(0, 32), publicKey, message)).mod(N)
        val sum = affine(add(mul(G, s), mul(Jac(pt.x, pt.y, BigInteger.ONE), N.subtract(e)))) ?: return false
        return !sum.y.testBit(0) && sum.x == r
    }
}
