package com.shiyi.app.net

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 极简 WebDAV 客户端（坚果云等）。只用到：建目录、上传、下载、列目录、删除。
 * 调用方负责在 IO 线程调用。
 */
class WebDavClient(
    baseUrl: String,
    private val user: String,
    private val password: String,
    private val client: OkHttpClient = defaultClient,
) {
    class AuthException : IOException("账号或应用密码不正确")

    data class RemoteFile(val name: String, val size: Long)

    private val base = baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
    private val auth = Credentials.basic(user, password, Charsets.UTF_8)

    private fun url(path: String) = base + path.split('/').joinToString("/") { seg ->
        java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
    }

    private fun req(path: String) = Request.Builder().url(url(path)).header("Authorization", auth)

    private fun check(code: Int, what: String) {
        if (code == 401 || code == 403) throw AuthException()
        if (code !in 200..299) throw IOException("$what 失败（HTTP $code）")
    }

    /** 测试连接：对根目录做一次 PROPFIND */
    fun test() {
        client.newCall(req("").method("PROPFIND", "".toRequestBody()).header("Depth", "0").build()).execute().use {
            check(it.code, "连接")
        }
    }

    /** 建目录（已存在时忽略） */
    fun ensureDir(dir: String) {
        client.newCall(req("$dir/").method("MKCOL", null).build()).execute().use {
            if (it.code == 401) throw AuthException()
            // 201 新建；405 已存在；坚果云已存在时也可能返回 409/405
            if (it.code !in listOf(200, 201, 204, 301, 405, 409)) check(it.code, "创建目录")
        }
    }

    fun upload(dir: String, name: String, file: File) {
        val body = file.asRequestBody("application/octet-stream".toMediaType())
        client.newCall(req("$dir/$name").put(body).build()).execute().use { check(it.code, "上传") }
    }

    fun download(dir: String, name: String, to: File) {
        client.newCall(req("$dir/$name").get().build()).execute().use { resp ->
            check(resp.code, "下载")
            val body = resp.body ?: throw IOException("下载内容为空")
            to.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
    }

    fun delete(dir: String, name: String) {
        client.newCall(req("$dir/$name").delete().build()).execute().use {
            if (it.code != 404) check(it.code, "删除")
        }
    }

    /** 列出目录下的文件（不含子目录），目录不存在时返回空列表 */
    fun list(dir: String): List<RemoteFile> {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"""
        client.newCall(
            req("$dir/").method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "1").build()
        ).execute().use { resp ->
            if (resp.code == 404) return emptyList()
            check(resp.code, "读取目录")
            return parsePropfind(resp.body?.string().orEmpty())
        }
    }

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        private val response = Regex("""<(?:\w+:)?response\b[^>]*>(.*?)</(?:\w+:)?response>""", RegexOption.DOT_MATCHES_ALL)
        private val href = Regex("""<(?:\w+:)?href[^>]*>(.*?)</(?:\w+:)?href>""", RegexOption.DOT_MATCHES_ALL)
        private val length = Regex("""<(?:\w+:)?getcontentlength[^>]*>(\d+)</""")
        private val collection = Regex("""<(?:\w+:)?collection\s*/?>""")

        /** 解析 PROPFIND 的多状态响应（兼容不同命名空间前缀） */
        fun parsePropfind(xml: String): List<RemoteFile> =
            response.findAll(xml).mapNotNull { m ->
                val block = m.groupValues[1]
                if (collection.containsMatchIn(block)) return@mapNotNull null
                val h = href.find(block)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
                val name = URLDecoder.decode(h.trimEnd('/').substringAfterLast('/'), "UTF-8")
                if (name.isBlank()) return@mapNotNull null
                RemoteFile(name, length.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L)
            }.toList()
    }
}
