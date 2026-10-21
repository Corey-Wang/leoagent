package io.leoagent.app

import android.content.Context
import android.net.Uri

/** Per-computer connection; credentials live in Keystore-encrypted storage. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("leoagent", Context.MODE_PRIVATE)
    fun url(c: Context) = (sp(c).getString("url", "") ?: "").trim().trimEnd('/')
    @Synchronized fun token(c: Context): String = try {
        val name = "credential_" + SecureStore.digest(url(c))
        val saved = SecureStore.read(c, name)
        if (saved != null) saved.optString("token") else {
            val legacy = sp(c).getString("token", "") ?: ""
            if (legacy.isNotEmpty()) {
                SecureStore.write(c, name, org.json.JSONObject().put("token", legacy))
                check(sp(c).edit().remove("token").commit())
            }
            legacy
        }
    } catch (_: Exception) { "" } // Lost/unavailable key requires pairing; never send an unprotected fallback.
    fun configured(c: Context) = url(c).isNotEmpty() && token(c).isNotEmpty()
    @Synchronized fun save(c: Context, url: String, token: String): Boolean = try {
        val normalized = url.trim().trimEnd('/')
        SecureStore.write(c, "credential_" + SecureStore.digest(normalized), org.json.JSONObject().put("token", token.trim()))
        sp(c).edit().putString("url", normalized).remove("token").commit()
    } catch (_: Exception) { false }
    fun setToken(c: Context, token: String) = save(c, url(c), token)

    fun validUrl(value: String): Boolean = try {
        val u = Uri.parse(value)
        u.scheme in listOf("https", "http") && !u.host.isNullOrEmpty() &&
            u.userInfo == null && u.query == null && u.fragment == null
    } catch (_: Exception) { false }

    /** The link carries an expiring invitation, never the long-lived credential. */
    fun parsePair(s: String): Pair<String, String>? = try {
        val u = Uri.parse(s.trim())
        val url = u.getQueryParameter("url")?.trim()?.trimEnd('/'); val code = u.getQueryParameter("code")?.trim()
        if (u.scheme == "leoagent" && u.host == "pair" && !url.isNullOrEmpty() &&
            validUrl(url) && code?.matches(Regex("[a-f0-9]{64}")) == true) Pair(url, code) else null
    } catch (_: Exception) { null }

    /** Existing QR codes remain usable when the server allows legacy credentials. */
    fun parseLegacyPair(s: String): Pair<String, String>? = try {
        val u = Uri.parse(s.trim())
        val url = u.getQueryParameter("url")?.trim()?.trimEnd('/')
        val token = u.getQueryParameter("token")?.trim()
        if (u.scheme == "leoagent" && u.host == "pair" && !url.isNullOrEmpty() &&
            validUrl(url) && !token.isNullOrEmpty() && u.getQueryParameter("code") == null) Pair(url, token) else null
    } catch (_: Exception) { null }
}
