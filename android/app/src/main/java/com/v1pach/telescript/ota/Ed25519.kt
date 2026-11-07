package com.v1pach.telescript.ota

import java.math.BigInteger
import java.security.MessageDigest

/** Minimal Ed25519 signature verification (RFC 8032) on plain JDK classes; works on every Android API level. */
object Ed25519 {
    private val P = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
    private val L = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
    private val D = (BigInteger.valueOf(-121665) * BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val I = BigInteger.TWO.modPow((P - BigInteger.ONE).shiftRight(2), P)
    private val BY = (BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P)).mod(P)
    private val BX: BigInteger = recoverX(BY, false)!!
    private val B = Pt(BX, BY)

    private class Pt(val x: BigInteger, val y: BigInteger)

    private fun recoverX(y: BigInteger, sign: Boolean): BigInteger? {
        if (y >= P) return null
        val y2 = y * y
        val x2 = ((y2 - BigInteger.ONE) * (D * y2 + BigInteger.ONE).mod(P).modInverse(P)).mod(P)
        if (x2.signum() == 0) return if (sign) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)).shiftRight(3), P)
        if ((x * x - x2).mod(P).signum() != 0) x = (x * I).mod(P)
        if ((x * x - x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != sign) x = P - x
        return x
    }

    private fun add(a: Pt, b: Pt): Pt {
        val t = (D * a.x * b.x * a.y * b.y).mod(P)
        val x = ((a.x * b.y + b.x * a.y) * (BigInteger.ONE + t).mod(P).modInverse(P)).mod(P)
        val y = ((a.y * b.y + a.x * b.x) * (BigInteger.ONE - t).mod(P).modInverse(P)).mod(P)
        return Pt(x, y)
    }

    private fun mul(p: Pt, k: BigInteger): Pt {
        var r = Pt(BigInteger.ZERO, BigInteger.ONE)
        var q = p
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) r = add(r, q)
            q = add(q, q)
        }
        return r
    }

    private fun le(b: ByteArray, off: Int = 0, len: Int = b.size - off): BigInteger =
        BigInteger(1, b.copyOfRange(off, off + len).reversedArray())

    private fun decode(b: ByteArray): Pt? {
        val y = le(b).clearBit(255)
        val x = recoverX(y, (b[31].toInt() and 0x80) != 0) ?: return null
        return Pt(x, y)
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decode(publicKey) ?: return false
        val r = decode(signature.copyOfRange(0, 32)) ?: return false
        val s = le(signature, 32, 32)
        if (s >= L) return false
        val md = MessageDigest.getInstance("SHA-512")
        md.update(signature, 0, 32); md.update(publicKey); md.update(message)
        val h = le(md.digest()).mod(L)
        val left = mul(B, s)
        val right = add(r, mul(a, h))
        return left.x == right.x && left.y == right.y
    }
}
