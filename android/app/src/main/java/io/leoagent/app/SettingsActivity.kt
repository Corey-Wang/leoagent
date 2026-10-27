package io.leoagent.app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

/** Short-lived invitation, locally confirmed on the user's computer. */
class SettingsActivity : AppCompatActivity() {
    private fun dp(v: Int) = UI.dp(this, v)
    private lateinit var url: EditText
    private lateinit var tok: EditText
    @Volatile private var pairingGeneration = 0
    private var pairingDialog: AlertDialog? = null
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(40)) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(16), dp(8)) }
        bar.addView(UI.icon(this, R.drawable.ms_chevron_left).apply { contentDescription = "返回设置"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(UI.text(this, "连接设置", 17f, R.color.text, true)); l.addView(bar)
        UI.largeTitle(this, l, "连接电脑", "在电脑确认配对后，即可远程继续任务")
        val g = UI.group(this, l, "连接")
        fun field(label: String, v: String, hint: String): EditText {
            if (g.childCount > 0) g.addView(android.view.View(this).apply { setBackgroundColor(UI.col(context, R.color.border)) }, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1) / 2)).apply { leftMargin = dp(16) })
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(6)) }
            val labelView = UI.text(this, label, 13f, R.color.muted)
            labelView.id = View.generateViewId()
            r.addView(labelView)
            val e = EditText(this).apply {
                id = View.generateViewId()
                setText(v); this.hint = hint; background = UI.outline(context, R.color.surface2, 12); textSize = 16f; isSingleLine = true
                setTextColor(UI.col(context, R.color.text)); setPadding(dp(12), dp(8), dp(12), dp(8))
                contentDescription = label
                minHeight = dp(DS.TOUCH)
            }
            labelView.labelFor = e.id
            e.setHintTextColor(UI.col(this, R.color.muted))
            r.addView(e); g.addView(r); return e
        }
        url = field("服务器地址", Prefs.url(this), "https://your-host.example.com")
        tok = field("设备凭据（加密保存）", Prefs.token(this), "配对成功后自动保存")
        tok.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        UI.footer(this, l, "在电脑上运行 leoagent-server pair，扫描或粘贴配对链接。新链接需在电脑核对验证码并确认；旧链接仍可填入，确认后保存。明文 HTTP 仅适合可信私网。")
        val ga = UI.group(this, l)
        UI.action(this, ga, "粘贴配对链接") {
            val cm = getSystemService(ClipboardManager::class.java)
            val s = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            acceptPairLink(s)
        }
        UI.action(this, ga, "测试连接") { test() }
        l.addView(UI.filledButton(this, "保存") { save() }, UI.lp(this, t = 28))
        setContentView(ScrollView(this).apply { setBackgroundColor(UI.col(context, R.color.bg)); addView(l) })
        handle(intent)
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }
    override fun onDestroy() { pairingGeneration++; pairingDialog?.dismiss(); super.onDestroy() }
    private fun handle(i: Intent?) {
        val d = i?.dataString ?: return
        acceptPairLink(d)
        i.data = null
    }
    private fun acceptPairLink(link: String) {
        val invitation = Prefs.parsePair(link)
        if (invitation != null) { fill(invitation); return }
        val legacy = Prefs.parseLegacyPair(link)
        if (legacy == null) {
            Toast.makeText(this, "没有有效的 leoagent://pair 配对链接", Toast.LENGTH_LONG).show()
            return
        }
        pairingGeneration++
        pairingDialog?.dismiss()
        url.setText(legacy.first)
        tok.setText(legacy.second)
        Toast.makeText(this, "已从旧配对链接填入，确认后点「保存」", Toast.LENGTH_LONG).show()
    }
    private fun fill(p: Pair<String, String>) {
        val generation = ++pairingGeneration
        pairingDialog?.dismiss()
        url.setText(p.first)
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".take(80)
        Toast.makeText(this, "正在请求配对…", Toast.LENGTH_SHORT).show()
        Thread {
            fun request(path: String, body: JSONObject): Pair<Int, JSONObject> {
                val result = Http.call("${p.first}/api/v1/pairing/$path", "POST",
                    mapOf("Content-Type" to "application/json"), body.toString().toByteArray(), readTimeoutMs = 10000)
                return result.first to JSONObject(result.second)
            }
            try {
                val (status, claim) = request("claim", JSONObject().put("code", p.second).put("device_name", deviceName))
                check(status == 200) { claim.optString("error", "无法开始配对") }
                if (generation != pairingGeneration) return@Thread
                runOnUiThread {
                    if (generation == pairingGeneration && !isFinishing) {
                        pairingDialog = AlertDialog.Builder(this).setTitle("请在电脑确认")
                            .setMessage("验证码：${claim.getString("verification")}\n\n核对电脑显示的验证码和设备名。确认前请保持此页面打开。")
                            .setNegativeButton("取消") { _, _ -> pairingGeneration++ }
                            .setOnCancelListener { pairingGeneration++ }.show()
                    }
                }
                val end = android.os.SystemClock.elapsedRealtime() + 300000
                val pollBody = JSONObject().put("pairing_id", claim.getString("pairing_id")).put("poll_secret", claim.getString("poll_secret"))
                while (generation == pairingGeneration && android.os.SystemClock.elapsedRealtime() < end) {
                    Thread.sleep(1000)
                    if (generation != pairingGeneration) return@Thread
                    val (code, result) = request("poll", pollBody)
                    check(code == 200) { result.optString("error", "配对失败，请在电脑重新配对") }
                    if (result.optString("state") == "paired") {
                        if (generation != pairingGeneration) return@Thread
                        check(Prefs.save(this, p.first, result.getString("token"))) { "无法加密保存设备凭据，请重新配对" }
                        CxPrefs.setServerIdentity(this, result.getString("data_epoch"))
                        runOnUiThread {
                            if (generation == pairingGeneration && !isFinishing) {
                                pairingDialog?.dismiss()
                                tok.setText(Prefs.token(this))
                                Toast.makeText(this, "配对成功，设备凭据已加密保存", Toast.LENGTH_LONG).show()
                                openMain()
                            }
                        }
                        return@Thread
                    }
                }
                if (generation == pairingGeneration) error("配对已过期，请在电脑重新配对")
            } catch (e: Exception) {
                runOnUiThread {
                    if (generation == pairingGeneration && !isFinishing) {
                        pairingDialog?.dismiss()
                        Toast.makeText(this, "配对失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }
    private fun save() {
        val u = url.text.toString().trim()
        if (!Prefs.validUrl(u)) { Toast.makeText(this, "请输入有效的 http:// 或 https:// 服务器地址", Toast.LENGTH_LONG).show(); return }
        if (tok.text.toString().isBlank()) { tok.error = "请先完成配对，或填写原设备凭据"; tok.requestFocus(); return }
        if (!Prefs.save(this, u, tok.text.toString())) { Toast.makeText(this, "无法加密保存，连接设置未保存", Toast.LENGTH_LONG).show(); return }
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        openMain()
    }
    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish()
    }
    private fun test() {
        val u = url.text.toString().trim().trimEnd('/'); val t = tok.text.toString().trim()
        if (!Prefs.validUrl(u)) { url.error = "请输入有效的 http:// 或 https:// 服务器地址"; url.requestFocus(); return }
        if (t.isEmpty()) { tok.error = "请先完成配对，或填写原设备凭据"; tok.requestFocus(); return }
        Toast.makeText(this, "连接中…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try { val (c, b) = Http.call("$u/api/health", "GET", mapOf("X-Token" to t))
                when (c) { 200 -> "连接成功 · 电脑端 v${JSONObject(b).optString("version", "未知")}"; 401, 403 -> "设备凭据不匹配或已撤销，请重新配对"; else -> "HTTP $c" } } catch (e: Exception) { "连接失败：${e.message}" }
            runOnUiThread { if (!isFinishing && !isDestroyed && url.text.toString().trim().trimEnd('/') == u && tok.text.toString().trim() == t) Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }
}
