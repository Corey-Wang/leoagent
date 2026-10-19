package io.leoagent.app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** 服务器地址 + Token。也处理配对链接 leoagent://pair?url=…&token=… (用系统相机/任意扫码 App 扫 `leoagent-server pair` 的二维码即可打开) */
class SettingsActivity : AppCompatActivity() {
    private fun dp(v: Int) = UI.dp(this, v)
    private lateinit var url: EditText
    private lateinit var tok: EditText
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(40)) }
        l.addView(UI.text(this, "‹ 设置", 17f, R.color.primary).apply { setPadding(dp(16), dp(14), dp(16), dp(8)); setOnClickListener { finish() } })
        UI.largeTitle(this, l, "服务器与 Token")
        val g = UI.group(this, l, "连接")
        fun field(label: String, v: String, hint: String): EditText {
            if (g.childCount > 0) g.addView(android.view.View(this).apply { setBackgroundColor(UI.col(context, R.color.border)) }, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1) / 2)).apply { leftMargin = dp(16) })
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(6)) }
            r.addView(UI.text(this, label, 13f, R.color.muted))
            val e = EditText(this).apply { setText(v); this.hint = hint; background = null; textSize = 16f; isSingleLine = true; setTextColor(UI.col(context, R.color.text)); setPadding(0, dp(4), 0, dp(6)) }
            r.addView(e); g.addView(r); return e
        }
        url = field("服务器地址", Prefs.url(this), "https://your-host.example.com")
        tok = field("Token", Prefs.token(this), "leoagent-server 生成的 token")
        UI.footer(this, l, "在电脑上运行 `leoagent-server pair`，用相机扫描二维码即可自动填入；也可复制配对链接后点「粘贴配对链接」。")
        val ga = UI.group(this, l)
        UI.action(this, ga, "粘贴配对链接") {
            val cm = getSystemService(ClipboardManager::class.java)
            val s = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            val p = Prefs.parsePair(s)
            if (p == null) Toast.makeText(this, "剪贴板里没有有效的 leoagent://pair 链接", Toast.LENGTH_LONG).show() else fill(p)
        }
        UI.action(this, ga, "测试连接") { test() }
        l.addView(UI.filledButton(this, "保存") { save() }, UI.lp(this, t = 28))
        setContentView(ScrollView(this).apply { setBackgroundColor(UI.col(context, R.color.bg)); addView(l) })
        handle(intent)
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }
    private fun handle(i: Intent?) { val d = i?.dataString ?: return; Prefs.parsePair(d)?.let { fill(it); Toast.makeText(this, "已从配对链接填入，确认后点「保存」", Toast.LENGTH_LONG).show() } }
    private fun fill(p: Pair<String, String>) { url.setText(p.first); tok.setText(p.second) }
    private fun save() {
        val u = url.text.toString().trim()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) { Toast.makeText(this, "地址需以 http:// 或 https:// 开头", Toast.LENGTH_LONG).show(); return }
        Prefs.save(this, u, tok.text.toString()); Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish()
    }
    private fun test() {
        val u = url.text.toString().trim().trimEnd('/'); val t = tok.text.toString().trim()
        Toast.makeText(this, "连接中…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try { val (c, b) = Http.call("$u/api/health", "GET", mapOf("X-Token" to t))
                when (c) { 200 -> "连接成功 · ${JSONObject(b).optInt("agents")} 个活动会话"; 401 -> "Token 不正确"; else -> "HTTP $c" } } catch (e: Exception) { "连接失败：${e.message}" }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }
}
