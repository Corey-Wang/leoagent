package io.leoagent.app

import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 全 App 共享一个 OkHttpClient: 连接池 + HTTP/2 + 5 分钟 keep-alive, 省掉每次 TLS 握手 (~0.4s). */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).build()

    fun call(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null,
             contentType: String = "application/json; charset=utf-8", readTimeoutMs: Int = 20000): Pair<Int, String> {
        val rb = body?.toRequestBody(contentType.toMediaType()) ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(null) else null
        val b = Request.Builder().url(url).method(method, rb)
        headers.forEach { (k, v) -> b.header(k, v) }
        val cl = if (readTimeoutMs == 20000) client else client.newBuilder().readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS).build()
        cl.newCall(b.build()).execute().use { r -> return Pair(r.code, r.body?.string() ?: "") }
    }

    /** 预热: 提前建好 TLS 连接 (进入 Agent 页 / 按下麦克风时调用). */
    @Volatile private var lastWarm = 0L
    fun warm(url: String, token: String) {
        val now = System.currentTimeMillis(); if (now - lastWarm < 60_000) return; lastWarm = now
        Thread { try { call(url, "GET", mapOf("X-Token" to token), readTimeoutMs = 20000) } catch (_: Exception) {} }.start()
    }
}
