package io.leoagent.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject

/** 对话为主界面；完整设置从左侧抽屉进入。 */
class MainActivity : AppCompatActivity() {
    private lateinit var srl: SwipeRefreshLayout
    private lateinit var box: LinearLayout
    private lateinit var settingsView: View
    private var settingsOpen = false
    private var resumed = false
    private lateinit var pane: CodexPane
    private lateinit var agentView: View
    private val sp by lazy { getSharedPreferences("ui", MODE_PRIVATE) }
    private fun dp(v: Int) = UI.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = FrameLayout(this).apply { background = UI.pageBg(context) }
        pane = CodexPane(this) { showSettings() }
        agentView = pane.build(if (validNotification(intent)) intent.getStringExtra("sid") else null)
        root.addView(agentView)
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UI.pageBg(context) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(16), dp(6)) }
        bar.addView(UI.icon(this, R.drawable.ms_chevron_left).apply {
            contentDescription = "返回对话"; setOnClickListener { showAgent() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(UI.text(this, "连接与设置", 17f, R.color.text, true))
        settings.addView(bar)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(32)) }
        srl = SwipeRefreshLayout(this).apply {
            addView(ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(box) })
            setOnRefreshListener { renderSettings() }; setColorSchemeColors(UI.col(context, R.color.primary))
            setProgressBackgroundColorSchemeColor(UI.col(context, R.color.surface))
        }
        settings.addView(srl, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        settingsView = settings
        root.addView(settingsView)
        setContentView(root)
        // Legacy bottom-tab preferences must not restore the removed message page.
        if (b?.getBoolean("settings_open") ?: !Prefs.configured(this)) showSettings() else showAgent()
        ensureChannels(this)
        if (!validNotification(intent)) Toast.makeText(this, "这条通知来自另一台服务器，请先在设置中切换连接", Toast.LENGTH_LONG).show()
        if (Prefs.configured(this)) Updater.check(this, false)
    }

    fun showSettings() {
        if (!settingsOpen && resumed) pane.onHide()
        settingsOpen = true
        agentView.visibility = View.GONE; settingsView.visibility = View.VISIBLE
        window.statusBarColor = UI.col(this, R.color.bg)
        renderSettings()
    }
    private fun showAgent() {
        settingsOpen = false
        settingsView.visibility = View.GONE; agentView.visibility = View.VISIBLE
        window.statusBarColor = UI.col(this, R.color.bg)
        if (resumed) pane.onShow()
    }
    private fun renderSettings() {
        box.removeAllViews(); srl.isRefreshing = false
        renderMe()
    }

    // ───────────── 设置 ─────────────
    private fun renderMe() {
        UI.largeTitle(this, box, "你的电脑", "任务在电脑执行，手机随时回来续聊")
        if (!Prefs.configured(this)) {
            val c = UI.card(this, box, "还没有连接服务器")
            c.addView(UI.text(this, "在电脑上运行 `leoagent-server pair`，用手机相机扫描终端里的二维码；或在「服务器与设备凭据」里手动填写。", 14f))
            UI.action(this, UI.group(this, box), "去配置服务器") { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
        val g3 = UI.group(this, box, "连接")
        UI.row(this, g3, "服务器与设备凭据", Uri.parse(Prefs.url(this)).host ?: "未配置") { startActivity(Intent(this, SettingsActivity::class.java)) }
        if (Prefs.configured(this)) {
            val conn = Cx.connection(this)
            val status = UI.text(this, "正在检查电脑连接…", 14f, R.color.muted)
            UI.card(this, box).addView(status)
            Thread {
                val r = Cx.req(this, "GET", "/health", readTimeout = 5000, connection = conn)
                val o = runCatching { JSONObject(r.second) }.getOrNull()
                val text = when {
                    r.first == 200 && o?.optJSONObject("features")?.optBoolean("durable_tasks") == true -> "已连接电脑端 v${o.optString("version")} · 任务可断线恢复"
                    r.first == 200 -> "已连接，建议升级电脑端后使用可靠任务与恢复功能"
                    r.first in setOf(401, 403) -> "设备授权已失效，请打开「服务器与设备凭据」重新配对"
                    else -> "暂时无法连接电脑，请确认电脑在线、未休眠，网络入口可用"
                }
                runOnUiThread { if (!isDestroyed && conn.isCurrent(this)) status.text = text }
            }.start()
        }
        val gc = UI.group(this, box, "Agent")
        UI.row(this, gc, "默认模型", "${CxPrefs.model(this).ifEmpty { "默认" }} · ${CxPrefs.effort(this)}") { pane.pickDefaultModel { if (settingsOpen) renderSettings() } }
        UI.row(this, gc, "新对话审批", if (CxPrefs.mode(this) == "ask") "每条命令审批" else "项目内自由运行") {
            CxPrefs.setMode(this, if (CxPrefs.mode(this) == "ask") "auto" else "ask"); renderSettings() }
        UI.row(this, gc, "Agent", "Codex 等", sub = "启用 / 停用本机 ACP Agent，设置默认 Agent") { pane.agentSettings() }
        if (Prefs.configured(this)) Thread {
            val j = try { JSONObject(Cx.req(this, "GET", "/settings").second) } catch (_: Exception) { null }
            if (j == null || !j.optBoolean("desktop_sync_available")) return@Thread
            runOnUiThread {
                if (!settingsOpen) return@runOnUiThread
                val v = when { j.optString("backend") == "acp" -> "关（仅 codex-acp）"; j.optString("active") == "desktop" -> "开 · 实时同步中"; else -> "开 · 桌面端未运行，暂用 codex-acp" }
                UI.row(this, gc, "桌面同步（实验）", v, sub = "需服务端开启 desktop_sync；经 Codex 桌面端执行并双向同步") {
                    Thread { Cx.req(this, "POST", "/settings", JSONObject().put("backend", if (j.optString("backend") == "acp") "auto" else "acp")); runOnUiThread { if (settingsOpen) renderSettings() } }.start() }
            }
        }.start()
        val g2 = UI.group(this, box, "应用")
        UI.row(this, g2, "当前版本", "v" + packageManager.getPackageInfo(packageName, 0).versionName)
        UI.action(this, g2, "检查更新") { Updater.check(this, true) }
        UI.action(this, g2, "浏览器下载最新安装包") { Updater.openBrowser(this) }
        UI.action(this, g2, "上传日志") { uploadLogs() }
        val g4 = UI.group(this, box, "系统")
        val power = getSystemService(android.os.PowerManager::class.java)
        UI.row(this, g4, "后台电池限制", if (power.isIgnoringBatteryOptimizations(packageName)) "无限制" else "系统优化中") { try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) { appDetails() } }
        UI.row(this, g4, "任务通知", if (taskNotificationsEnabled(this)) "已开启" else "未开启",
            sub = "完成、审批与提问提醒；锁屏默认隐藏消息正文") { notificationSettings() }
        UI.footer(this, box, "后台提醒依赖本机网络与系统允许后台运行；强行停止应用后不会继续提醒。重新打开对应对话可查看任务结果。")
    }
    private fun appDetails() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun uploadLogs() {
        val et = android.widget.EditText(this).apply { hint = "备注（可选）：发生了什么"; setPadding(48, 32, 48, 32) }
        android.app.AlertDialog.Builder(this).setTitle("上传日志").setMessage("将设备信息及脱敏后的本应用运行日志发送到当前电脑。日志仍可能包含项目名称或错误信息，请确认后上传；不会附带系统日志。").setView(et)
            .setPositiveButton("上传") { _, _ ->
                Toast.makeText(this, "上传中…", Toast.LENGTH_SHORT).show()
                val note = et.text.toString()
                Thread { val r = AppLog.upload(this, note); runOnUiThread { Toast.makeText(this, r?.let { "上传失败：$it" } ?: "日志已上传", Toast.LENGTH_LONG).show() } }.start()
            }.setNegativeButton("取消", null).show()
    }

    private fun validNotification(i: Intent) = !i.hasExtra("server") || i.getStringExtra("server") == Prefs.url(this)
    private fun notificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!sp.getBoolean("notification_requested", false) || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                android.app.AlertDialog.Builder(this).setTitle("开启任务通知")
                    .setMessage("离开对话后，LeoAgent 会提醒任务完成、待审批和待回答的问题。点击通知可返回对应对话。")
                    .setPositiveButton("允许通知") { _, _ -> sp.edit().putBoolean("notification_requested", true).apply(); requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
                    .setNegativeButton("暂不开启", null).show()
                return
            }
        }
        try {
            val i = if (getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled())
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, CH_CODEX)
            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            startActivity(i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: Exception) { appDetails() }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && settingsOpen) renderSettings()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("settings_open", settingsOpen)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume(); resumed = true; Updater.resume(this)
        if (Prefs.configured(this)) Thread { runCatching { MessageStore.prepare(this) } }.start()
        if (settingsOpen) renderSettings() else pane.onShow()
    }
    override fun onPause() {
        resumed = false
        if (!settingsOpen) pane.onHide()
        super.onPause()
    }
    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i); setIntent(i)
        if (!validNotification(i)) { Toast.makeText(this, "这条通知来自另一台服务器，请先在设置中切换连接", Toast.LENGTH_LONG).show(); return }
        if (i.getStringExtra("tab") == "agent") { i.getStringExtra("sid")?.let { pane.open(it) }; showAgent() }
    }
    @Deprecated("") override fun onActivityResult(rq: Int, rc: Int, data: Intent?) { super.onActivityResult(rq, rc, data); pane.onActivityResult(rq, rc, data) }
    @Deprecated("") override fun onBackPressed() {
        if (settingsOpen) { showAgent(); return }
        if (pane.back()) return
        super.onBackPressed()
    }
}
