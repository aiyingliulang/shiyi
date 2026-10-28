package com.shiyi.app

import com.shiyi.app.domain.Classifier
import com.shiyi.app.domain.ReasonTag
import com.shiyi.app.domain.Verdict
import com.shiyi.app.net.BarcodeLookup
import com.shiyi.app.net.WebDavClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ClassifierTest {
    @Test fun autoRules() {
        assertEquals(Verdict.GOOD, Classifier.classify(9, "", null).verdict)
        assertEquals(Verdict.GOOD, Classifier.classify(8, "", null).verdict)
        assertEquals(Verdict.NEUTRAL, Classifier.classify(6, "", null).verdict)
        assertEquals(Verdict.AVOID, Classifier.classify(3, "BAD_TASTE", null).verdict)
        assertEquals(Verdict.AVOID, Classifier.classify(4, "", null).verdict)
        assertEquals(Verdict.UNRATED, Classifier.classify(null, "", null).verdict)
        // 低分只因配送 → 不冤枉菜品
        assertEquals(Verdict.NEUTRAL, Classifier.classify(2, ReasonTag.encode(listOf(ReasonTag.SLOW_DELIVERY, ReasonTag.COLD)), null).verdict)
        // 手动归类优先
        assertEquals(Verdict.AVOID, Classifier.classify(9, "", "UNWILLING").verdict)
        assertEquals(Verdict.GOOD, Classifier.classify(2, "", "WILLING").verdict)
        assertTrue(Classifier.classify(2, "", "WILLING").manual)
    }
}

class BarcodeParseTest {
    @Test fun prefersChinese() {
        val body = """{"code":"6924743915763","product":{"brands":"Lay's, Lay's 乐事","generic_name":"油炸型膨化食品",
            "product_name":"Lay's 乐事 无限 忠于原味","product_name_zh":"Lay's 乐事 无限 忠于原味","quantity":"104 g"},"status":1}"""
        val p = BarcodeLookup.parseOpenFoodFacts(body)!!
        assertEquals("Lay's 乐事 无限 忠于原味", p.name)
        assertEquals("Lay's 乐事", p.brand)
        assertEquals("104 g", p.spec)
    }

    @Test fun foreignNameWhenNoChinese() {
        val p = BarcodeLookup.parseOpenFoodFacts("""{"product":{"brands":"Genki, Genki Forest","product_name":"peach sparkling water","quantity":"480 mL"},"status":1}""")!!
        assertEquals("peach sparkling water", p.name); assertEquals("Genki", p.brand)
    }

    @Test fun notFound() {
        assertNull(BarcodeLookup.parseOpenFoodFacts("""{"code":"1","status":0,"status_verbose":"product not found"}"""))
        assertNull(BarcodeLookup.parseOpenFoodFacts("not json"))
    }
}

/** 用 MockWebServer 模拟一个最小的 WebDAV 服务（含 Basic 认证） */
class WebDavClientTest {
    private fun server(): Pair<MockWebServer, MutableMap<String, ByteArray>> {
        val files = ConcurrentHashMap<String, ByteArray>()
        val dirs = ConcurrentHashMap.newKeySet<String>().apply { add("/dav/") }
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != okhttp3.Credentials.basic("me@x.com", "app-pass")) return MockResponse().setResponseCode(401)
                val path = java.net.URLDecoder.decode(request.requestUrl!!.encodedPath, "UTF-8")
                return when (request.method) {
                    "MKCOL" -> if (dirs.add(path)) MockResponse().setResponseCode(201) else MockResponse().setResponseCode(405)
                    "PUT" -> { files[path] = request.body.readByteArray(); MockResponse().setResponseCode(201) }
                    "GET" -> files[path]?.let { MockResponse().setBody(okio.Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                    "DELETE" -> if (files.remove(path) != null) MockResponse().setResponseCode(204) else MockResponse().setResponseCode(404)
                    "PROPFIND" -> {
                        if (path !in dirs) return MockResponse().setResponseCode(404)
                        val items = files.filterKeys { it.startsWith(path) && it != path }.entries.joinToString("") { (k, v) ->
                            "<D:response><D:href>${k.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }}</D:href><D:propstat><D:prop><D:getcontentlength>${v.size}</D:getcontentlength><D:resourcetype/></D:prop></D:propstat></D:response>"
                        }
                        MockResponse().setResponseCode(207).setBody(
                            """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"><D:response><D:href>$path</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>$items</D:multistatus>"""
                        )
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
        s.start()
        return s to files
    }

    @Test fun fullCycle() {
        val (s, _) = server()
        try {
            val c = WebDavClient(s.url("/dav/").toString(), "me@x.com", "app-pass")
            c.test()
            c.ensureDir("食忆备份"); c.ensureDir("食忆备份") // 第二次已存在也不报错
            val f1 = File.createTempFile("upload", ".bin").apply { writeBytes(ByteArray(1234) { 7 }) }
            c.upload("食忆备份", "shiyi-20261025-210000.shiyibak", f1)
            c.upload("食忆备份", "shiyi-20261026-090000.shiyibak", f1)
            val list = c.list("食忆备份")
            assertEquals(setOf("shiyi-20261025-210000.shiyibak", "shiyi-20261026-090000.shiyibak"), list.map { it.name }.toSet())
            assertEquals(1234L, list.first().size)
            val out = File.createTempFile("download", ".bin")
            c.download("食忆备份", "shiyi-20261025-210000.shiyibak", out)
            assertEquals(1234L, out.length())
            c.delete("食忆备份", "shiyi-20261025-210000.shiyibak")
            assertEquals(1, c.list("食忆备份").size)
            assertEquals(0, c.list("不存在的目录").size)
        } finally { s.shutdown() }
    }

    @Test(expected = WebDavClient.AuthException::class)
    fun wrongPassword() {
        val (s, _) = server()
        try { WebDavClient(s.url("/dav/").toString(), "me@x.com", "wrong").test() } finally { s.shutdown() }
    }
}
