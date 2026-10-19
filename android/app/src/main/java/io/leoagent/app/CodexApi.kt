package io.leoagent.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CxPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("codex", Context.MODE_PRIVATE)
    fun base(c: Context) = Prefs.url(c) + "/api"
    fun token(c: Context) = Prefs.token(c)
    fun setToken(c: Context, v: String) = Prefs.setToken(c, v)
    fun project(c: Context) = sp(c).getInt("project", -1)
    fun setProject(c: Context, v: Int) = sp(c).edit().putInt("project", v).apply()
    fun session(c: Context) = sp(c).getString("session", null)
    fun setSession(c: Context, v: String?) = sp(c).edit().putString("session", v).apply()
    fun model(c: Context) = sp(c).getString("model", "") ?: ""
    fun effort(c: Context) = sp(c).getString("effort", "medium") ?: "medium"
    fun setModel(c: Context, m: String, e: String) = sp(c).edit().putString("model", m).putString("effort", e).apply()
    fun mode(c: Context) = sp(c).getString("mode", "auto") ?: "auto"
    fun setMode(c: Context, v: String) = sp(c).edit().putString("mode", v).apply()
    fun think(c: Context) = sp(c).getBoolean("think", false)
    fun setThink(c: Context, v: Boolean) = sp(c).edit().putBoolean("think", v).apply()
    fun agent(c: Context) = sp(c).getString("agent", "") ?: ""
    fun setAgent(c: Context, v: String) = sp(c).edit().putString("agent", v).apply()
    fun agentCfg(c: Context, a: String) = try { org.json.JSONObject(sp(c).getString("cfg_$a", "{}")!!) } catch (_: Exception) { org.json.JSONObject() }
    fun setAgentCfg(c: Context, a: String, o: org.json.JSONObject) = sp(c).edit().putString("cfg_$a", o.toString()).apply()
    fun agentFilter(c: Context) = sp(c).getBoolean("agent_filter", false)
    fun setAgentFilter(c: Context, v: Boolean) = sp(c).edit().putBoolean("agent_filter", v).apply()
    fun noteLast(c: Context) = sp(c).getInt("note_last", 0)
    fun setNoteLast(c: Context, v: Int) = sp(c).edit().putInt("note_last", v).apply()
}

object Cx {
    fun req(c: Context, method: String, path: String, body: JSONObject? = null, readTimeout: Int = 20000): Pair<Int, String> = try {
        val hd = mutableMapOf("X-Token" to CxPrefs.token(c), "User-Agent" to "leoagent-android")
        if (method == "PATCH") hd["X-HTTP-Method-Override"] = "PATCH"
        val m = if (method == "PATCH") "POST" else method
        val (code, s) = Http.call(CxPrefs.base(c) + path, m, hd, body?.toString()?.toByteArray(), readTimeoutMs = readTimeout)
        if (code >= 400) AppLog.e("codex", "$method $path -> $code ${s.take(300)}")
        Pair(code, s)  // 共享 OkHttp 连接池 (HTTP/2 keep-alive)
    } catch (e: Exception) { AppLog.e("codex", "$method $path failed: ${e.javaClass.simpleName}: ${e.message}"); Pair(0, e.message ?: "网络错误") }
    fun err(r: Pair<Int, String>) = try { JSONObject(r.second).optString("error", "HTTP ${r.first}") } catch (_: Exception) { if (r.first == 0) r.second else "HTTP ${r.first}" }
}
