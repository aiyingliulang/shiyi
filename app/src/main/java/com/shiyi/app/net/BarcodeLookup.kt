package com.shiyi.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 从商品库查到的信息（都可能为空） */
data class ProductInfo(val name: String, val brand: String, val spec: String, val source: String)

/**
 * 联网按条码查商品名称/品牌/规格。数据源：Open Food Facts（开源商品库，免费、无需账号）。
 * 只发送条码数字，不发送任何其他信息。国内零食覆盖率有限，查不到时手动填写即可。
 */
class BarcodeLookup(private val client: OkHttpClient = defaultClient) {
    private val cache = ConcurrentHashMap<String, ProductInfo?>()

    sealed interface Result {
        data class Found(val info: ProductInfo) : Result
        data object NotFound : Result
        data class Failed(val message: String) : Result
    }

    suspend fun lookup(code: String): Result = withContext(Dispatchers.IO) {
        val c = code.trim()
        if (!c.all { it.isDigit() } || c.length !in 8..14) return@withContext Result.NotFound
        if (cache.containsKey(c)) return@withContext cache[c]?.let { Result.Found(it) } ?: Result.NotFound
        val req = Request.Builder()
            .url("https://world.openfoodfacts.org/api/v2/product/$c.json?fields=product_name,product_name_zh,generic_name,generic_name_zh,brands,quantity")
            .header("User-Agent", "ShiYi/1.1 (personal Android app)")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) { cache[c] = null; return@withContext Result.NotFound }
                if (!resp.isSuccessful) return@withContext Result.Failed("商品库返回 ${resp.code}")
                val info = parseOpenFoodFacts(resp.body?.string().orEmpty())
                cache[c] = info
                if (info == null) Result.NotFound else Result.Found(info)
            }
        } catch (e: Exception) {
            Result.Failed("网络不可用或超时")
        }
    }

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        private val json = Json { ignoreUnknownKeys = true }
        private fun hasCjk(s: String) = s.any { it.code in 0x4e00..0x9fff }

        /** 解析 Open Food Facts 返回；优先中文名称和中文品牌。 */
        fun parseOpenFoodFacts(body: String): ProductInfo? {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            if (root["status"]?.jsonPrimitive?.intOrNull != 1) return null
            val p = root["product"] as? JsonObject ?: return null
            fun f(k: String) = p[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val names = listOf(f("product_name_zh"), f("product_name"), f("generic_name_zh"), f("generic_name")).filter { it.isNotBlank() }
            val name = names.firstOrNull { hasCjk(it) } ?: names.firstOrNull().orEmpty()
            val brands = f("brands").split(',', '，').map { it.trim() }.filter { it.isNotBlank() }
            val brand = brands.firstOrNull { hasCjk(it) } ?: brands.firstOrNull().orEmpty()
            val spec = f("quantity")
            if (name.isBlank() && brand.isBlank()) return null
            return ProductInfo(name = name, brand = brand, spec = spec, source = "Open Food Facts")
        }
    }
}
