package io.leoagent.app

import java.net.HttpURLConnection
import java.net.URL

object Net {
    fun req(url: String, token: String, method: String = "GET", body: String? = null): Pair<Int, String> = try { req0(url, token, method, body).also {
        if (it.first >= 400) AppLog.e("net", "$method ${url.substringAfter("/api").substringBefore('?')} -> ${it.first}") }
    } catch (e: Exception) { AppLog.e("net", "$method ${url.substringAfter("/api")} failed: ${e.javaClass.simpleName}: ${e.message}"); throw e }
    private fun req0(url: String, token: String, method: String, body: String?): Pair<Int, String> =
        Http.call(url, method, mapOf("X-Token" to token, "Cache-Control" to "no-cache"), body?.toByteArray(), readTimeoutMs = 15000)
}
