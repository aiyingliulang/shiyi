package com.shiyi.app.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密备份文件格式（.shiyibak）：
 *
 *   "SHIYIBK1" | u32 头长度 | 头 JSON(UTF-8) | 分块...
 *   分块 = u32 密文长度 | AES-256-GCM 密文(含 16 字节 tag)
 *
 * - 密钥：PBKDF2-HMAC-SHA256(密码, 随机 16 字节盐, 600,000 次) → 256 位
 * - 每块 nonce = 8 字节随机前缀 + 4 字节块序号，绝不重复
 * - AAD = 头字节 + 块序号 + 是否最后一块，防篡改、防重排、防截断
 * - 分块（1 MiB）流式处理，图片较多时也不需要把整个备份读入内存
 * - 密码提示以明文存放在头部（用户自行决定是否填写）
 */
object BackupCrypto {
    private val MAGIC = "SHIYIBK1".toByteArray(Charsets.US_ASCII)
    const val DEFAULT_ITERATIONS = 600_000
    private const val CHUNK = 1 shl 20
    private const val TAG_BITS = 128
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Header(
        val v: Int = 1,
        val kdf: String = "PBKDF2WithHmacSHA256",
        val iterations: Int,
        val salt: String,
        val noncePrefix: String,
        val chunkSize: Int = CHUNK,
        val hint: String = "",
        val createdAt: Long,
    )

    class WrongPasswordOrCorrupted : Exception("密码错误或备份文件已损坏")
    class NotABackup : Exception("不是食忆备份文件")

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(
        input: InputStream, output: OutputStream, password: CharArray, hint: String,
        iterations: Int = DEFAULT_ITERATIONS, now: Long = System.currentTimeMillis(),
    ) {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val prefix = ByteArray(8).also(rnd::nextBytes)
        val header = Header(
            iterations = iterations,
            salt = b64(salt),
            noncePrefix = b64(prefix),
            hint = hint.take(100),
            createdAt = now,
        )
        val headerBytes = json.encodeToString(Header.serializer(), header).toByteArray(Charsets.UTF_8)
        val key = deriveKey(password, salt, iterations)
        val out = DataOutputStream(output)
        out.write(MAGIC)
        out.writeInt(headerBytes.size)
        out.write(headerBytes)

        val buf = ByteArray(CHUNK)
        var next = ByteArray(CHUNK)
        var len = readFully(input, buf)
        var index = 0
        while (true) {
            val nextLen = if (len == CHUNK) readFully(input, next) else 0
            val last = nextLen == 0
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, index)))
            cipher.updateAAD(aad(headerBytes, index, last))
            val ct = cipher.doFinal(buf, 0, len)
            out.writeInt(ct.size)
            out.write(ct)
            if (last) break
            System.arraycopy(next, 0, buf, 0, nextLen)
            len = nextLen
            index++
            if (index == Int.MAX_VALUE) error("备份过大")
        }
        out.flush()
        next.fill(0); buf.fill(0)
    }

    /** 只读取头部（用于显示密码提示），不需要密码 */
    fun readHeader(input: InputStream): Header {
        val din = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try { din.readFully(magic) } catch (e: EOFException) { throw NotABackup() }
        if (!magic.contentEquals(MAGIC)) throw NotABackup()
        val n = din.readInt()
        if (n !in 1..65536) throw NotABackup()
        val hb = ByteArray(n).also(din::readFully)
        return runCatching { json.decodeFromString(Header.serializer(), String(hb, Charsets.UTF_8)) }
            .getOrElse { throw NotABackup() }
    }

    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val din = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try { din.readFully(magic) } catch (e: EOFException) { throw NotABackup() }
        if (!magic.contentEquals(MAGIC)) throw NotABackup()
        val n = din.readInt()
        if (n !in 1..65536) throw NotABackup()
        val headerBytes = ByteArray(n).also(din::readFully)
        val header = runCatching { json.decodeFromString(Header.serializer(), String(headerBytes, Charsets.UTF_8)) }
            .getOrElse { throw NotABackup() }
        if (header.v != 1 || header.kdf != "PBKDF2WithHmacSHA256") throw NotABackup()
        val key = deriveKey(password, unb64(header.salt), header.iterations)
        val prefix = unb64(header.noncePrefix)
        var index = 0
        var ct: ByteArray? = readChunk(din, header.chunkSize) ?: throw WrongPasswordOrCorrupted()
        while (ct != null) {
            val following = readChunk(din, header.chunkSize)
            val last = following == null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, index)))
            cipher.updateAAD(aad(headerBytes, index, last))
            val pt = try { cipher.doFinal(ct) } catch (e: AEADBadTagException) { throw WrongPasswordOrCorrupted() }
            output.write(pt)
            ct = following
            index++
        }
        output.flush()
    }

    private fun readChunk(din: DataInputStream, chunkSize: Int): ByteArray? {
        val len = try { din.readInt() } catch (e: EOFException) { return null }
        if (len < 16 || len > chunkSize + 16) throw WrongPasswordOrCorrupted()
        val b = ByteArray(len)
        try { din.readFully(b) } catch (e: EOFException) { throw WrongPasswordOrCorrupted() }
        return b
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun nonce(prefix: ByteArray, index: Int): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(index).array()

    private fun aad(header: ByteArray, index: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 5).put(header).putInt(index).put(if (last) 1 else 0).array()

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)
}
