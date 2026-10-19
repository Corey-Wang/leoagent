package io.leoagent.app

import android.content.Context
import android.net.Uri

/** 连接配置: 服务器地址 + 唯一 token (由 leoagent-server 生成, 扫码配对或手动填写) */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("leoagent", Context.MODE_PRIVATE)
    fun url(c: Context) = (sp(c).getString("url", "") ?: "").trim().trimEnd('/')
    fun token(c: Context) = sp(c).getString("token", "") ?: ""
    fun configured(c: Context) = url(c).isNotEmpty() && token(c).isNotEmpty()
    fun save(c: Context, url: String, token: String) = sp(c).edit().putString("url", url.trim().trimEnd('/')).putString("token", token.trim()).apply()
    fun setToken(c: Context, token: String) = sp(c).edit().putString("token", token.trim()).apply()

    /** 解析配对链接: leoagent://pair?url=https%3A%2F%2Fhost&token=xxx  → (url, token) */
    fun parsePair(s: String): Pair<String, String>? = try {
        val u = Uri.parse(s.trim())
        val url = u.getQueryParameter("url")?.trim()?.trimEnd('/'); val tok = u.getQueryParameter("token")?.trim()
        if (u.scheme == "leoagent" && !url.isNullOrEmpty() && !tok.isNullOrEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) Pair(url, tok) else null
    } catch (_: Exception) { null }
}
