package com.yet.sqlcipher

import kotlin.test.Test
import kotlin.test.assertEquals

class HexEncodedTest {

    @Test
    fun encodesLowercasePairs() {
        assertEquals("", ByteArray(0).hexEncoded())
        assertEquals("00ff10ab", byteArrayOf(0x00, 0xFF.toByte(), 0x10, 0xAB.toByte()).hexEncoded())
    }

    @Test
    fun thirtyTwoByteKeyIsSixtyFourChars() {
        val key = ByteArray(32) { it.toByte() }
        val hex = key.hexEncoded()
        assertEquals(64, hex.length)
        assertEquals("000102030405060708090a0b0c0d0e0f", hex.take(32))
    }
}
