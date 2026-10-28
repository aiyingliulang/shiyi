package com.shiyi.app

import com.shiyi.app.backup.BackupCrypto
import com.shiyi.app.backup.CsvExporter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class BackupCryptoTest {
    private val iter = 1000 // 测试用低迭代；正式为 600,000

    private fun enc(data: ByteArray, pw: String = "correct horse", hint: String = "马"): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream(data), out, pw.toCharArray(), hint, iter)
        return out.toByteArray()
    }

    private fun dec(b: ByteArray, pw: String = "correct horse"): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.decrypt(ByteArrayInputStream(b), out, pw.toCharArray())
        return out.toByteArray()
    }

    @Test fun roundTripSizes() {
        for (size in listOf(0, 1, 1000, (1 shl 20) - 1, 1 shl 20, (1 shl 20) + 1, 3 * (1 shl 20) + 77)) {
            val data = Random(size).nextBytes(size)
            assertArrayEquals("size=$size", data, dec(enc(data)))
        }
    }

    @Test fun headerHint() {
        val b = enc("hello".toByteArray(), hint = "生日")
        assertEquals("生日", BackupCrypto.readHeader(ByteArrayInputStream(b)).hint)
    }

    @Test(expected = BackupCrypto.WrongPasswordOrCorrupted::class)
    fun wrongPassword() { dec(enc("secret".toByteArray()), "wrong") }

    @Test(expected = BackupCrypto.WrongPasswordOrCorrupted::class)
    fun tamperedByte() {
        val b = enc(Random(1).nextBytes(5000))
        b[b.size - 20] = (b[b.size - 20].toInt() xor 1).toByte()
        dec(b)
    }

    @Test(expected = BackupCrypto.WrongPasswordOrCorrupted::class)
    fun truncatedAtChunkBoundary() {
        val data = Random(2).nextBytes(2 * (1 shl 20) + 10)
        val b = enc(data)
        // 去掉最后一块（4 字节长度 + 10+16 字节密文）
        dec(b.copyOf(b.size - (4 + 10 + 16)))
    }

    @Test(expected = BackupCrypto.WrongPasswordOrCorrupted::class)
    fun tamperedHintIsDetected() {
        val b = enc("data".toByteArray(), hint = "AAAA")
        val s = String(b, Charsets.ISO_8859_1).replace("AAAA", "BBBB")
        dec(s.toByteArray(Charsets.ISO_8859_1))
    }

    @Test(expected = BackupCrypto.NotABackup::class)
    fun notABackup() { dec("PK\u0003\u0004 random zip".toByteArray()) }

    @Test fun ciphertextDiffersEachTime() {
        val d = "same".toByteArray()
        assertNotEquals(enc(d).toList(), enc(d).toList())
    }

    @Test fun csvEscaping() {
        assertEquals("\"a\"\"b\"", CsvExporter.cell("a\"b"))
        assertEquals("\"'=SUM(A1)\"", CsvExporter.cell("=SUM(A1)"))
        assertEquals("\"-12.5\"", CsvExporter.cell("-12.5"))
        assertEquals("\"x y\"", CsvExporter.cell("x\ny"))
    }
}
