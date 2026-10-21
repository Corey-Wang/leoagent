package io.leoagent.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONArray
import org.json.JSONObject

/** 三个底部 Tab: 0 Agent · 1 消息 (Agent 通知) · 2 设置 */
class MainActivity : AppCompatActivity() {
    private lateinit var content: FrameLayout
    private lateinit var srl: SwipeRefreshLayout
    private lateinit var box: LinearLayout
    private lateinit var nav: BottomNavigationView
    private var top = 0
    private var resumed = false
    private lateinit var pane: CodexPane
    private lateinit var agentView: View
    private val sp by lazy { getSharedPreferences("ui", MODE_PRIVATE) }
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { if (top == 1) inboxShow() else refreshBadge(); h.postDelayed(this, 20_000) } }
    private fun dp(v: Int) = UI.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = FrameLayout(this).apply { background = UI.pageBg(context) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(120)) }
        srl = SwipeRefreshLayout(this).apply {
            addView(ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(box) })
            setOnRefreshListener { show(top) }; setColorSchemeColors(UI.col(context, R.color.primary))
            setProgressBackgroundColorSchemeColor(UI.col(context, R.color.surface))
        }
        content.addView(srl)
        pane = CodexPane(this); agentView = pane.build(if (validNotification(intent)) intent.getStringExtra("sid") else null); agentView.visibility = View.GONE; content.addView(agentView)
        col.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        col.addView(View(this).apply { setBackgroundColor(UI.col(context, R.color.border)) }, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1) / 2)))
        val tint = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(UI.col(this, R.color.primary), UI.col(this, R.color.muted)))
        nav = BottomNavigationView(this).apply {
            inflateMenu(R.menu.nav); labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            setBackgroundColor(UI.col(context, R.color.bar)); elevation = 0f
            isItemActiveIndicatorEnabled = false; itemIconTintList = null; itemTextColor = tint; itemRippleColor = null; itemIconSize = dp(22)
            itemPaddingTop = dp(7); itemPaddingBottom = dp(6); minimumHeight = 0
            setItemTextAppearanceActive(R.style.NavLabel); setItemTextAppearanceInactive(R.style.NavLabel)
            setOnItemSelectedListener { selectTop(when (it.itemId) { R.id.n_agent -> 0; R.id.n_msg -> 1; else -> 2 }); true }
        }
        col.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, dp(54)))
        root.addView(col)
        setContentView(root)
        root.viewTreeObserver.addOnGlobalLayoutListener {  // 键盘弹出时隐藏底栏
            val kb = root.rootView.height - root.height > root.rootView.height / 4
            val want = if (kb && top == 0) View.GONE else View.VISIBLE
            if (nav.visibility != want) nav.visibility = want
        }
        val start = when { !Prefs.configured(this) -> 2; validNotification(intent) && intent.getStringExtra("tab") == "agent" -> 0; else -> sp.getInt("top", 0).coerceIn(0, 2) }
        nav.selectedItemId = listOf(R.id.n_agent, R.id.n_msg, R.id.n_set)[start]; selectTop(start)
        ensureChannels(this)
        if (!validNotification(intent)) Toast.makeText(this, "这条通知来自另一台服务器，请先在设置中切换连接", Toast.LENGTH_LONG).show()
        if (Prefs.configured(this)) Updater.check(this, false)
    }

    private fun selectTop(i: Int) {
        if (top == 0 && i != 0) pane.onHide()
        top = i; sp.edit().putInt("top", i).apply()
        agentView.visibility = if (i == 0) View.VISIBLE else View.GONE; srl.visibility = if (i == 0) View.GONE else View.VISIBLE
        window.statusBarColor = UI.col(this, R.color.glowA)
        if (i == 0) { if (resumed) pane.onShow(); return }
        show(i)
    }
    private fun show(i: Int) {
        box.removeAllViews(); srl.isRefreshing = false
        if (i == 1) inboxShow() else { UI.largeTitle(this, box, "设置"); renderMe() }
    }

    // ───────────── 设置 ─────────────
    private fun renderMe() {
        if (!Prefs.configured(this)) {
            val c = UI.card(this, box, "还没有连接服务器")
            c.addView(UI.text(this, "在电脑上运行 `leoagent-server pair`，用手机相机扫描终端里的二维码；或在「服务器与 Token」里手动填写。", 14f))
            UI.action(this, UI.group(this, box), "去配置服务器") { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
        val gc = UI.group(this, box, "Agent")
        UI.row(this, gc, "默认模型", "${CxPrefs.model(this).ifEmpty { "默认" }} · ${CxPrefs.effort(this)}") { pane.pickDefaultModel { if (top == 2) show(2) } }
        UI.row(this, gc, "新对话审批", if (CxPrefs.mode(this) == "ask") "每条命令审批" else "项目内自由运行") {
            CxPrefs.setMode(this, if (CxPrefs.mode(this) == "ask") "auto" else "ask"); show(2) }
        UI.row(this, gc, "Agent", "Codex 等", sub = "启用 / 停用本机 ACP Agent，设置默认 Agent") { pane.agentSettings() }
        if (Prefs.configured(this)) Thread {
            val j = try { JSONObject(Cx.req(this, "GET", "/settings").second) } catch (_: Exception) { null }
            if (j == null || !j.optBoolean("desktop_sync_available")) return@Thread
            runOnUiThread {
                if (top != 2) return@runOnUiThread
                val v = when { j.optString("backend") == "acp" -> "关（仅 codex-acp）"; j.optString("active") == "desktop" -> "开 · 实时同步中"; else -> "开 · 桌面端未运行，暂用 codex-acp" }
                UI.row(this, gc, "桌面同步（实验）", v, sub = "需服务端开启 desktop_sync；经 Codex 桌面端执行并双向同步") {
                    Thread { Cx.req(this, "POST", "/settings", JSONObject().put("backend", if (j.optString("backend") == "acp") "auto" else "acp")); runOnUiThread { if (top == 2) show(2) } }.start() }
            }
        }.start()
        val g3 = UI.group(this, box, "连接")
        UI.row(this, g3, "服务器与 Token", Prefs.url(this).removePrefix("https://").ifEmpty { "未配置" }) { startActivity(Intent(this, SettingsActivity::class.java)) }
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
                    r.first in setOf(401, 403) -> "设备授权已失效，请打开「服务器与 Token」重新配对"
                    else -> "暂时无法连接电脑，请确认电脑在线、未休眠，网络入口可用"
                }
                runOnUiThread { if (!isDestroyed && conn.isCurrent(this)) status.text = text }
            }.start()
        }
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
        UI.footer(this, box, "后台提醒依赖本机网络与系统允许后台运行；强行停止应用后不会继续提醒。重新打开消息页可补查近 7 天记录。")
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
                    .setMessage("离开对话后，LeoAgent 会提醒任务完成、待审批和待回答的问题。也可以随时在消息页查看。")
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
        if (requestCode == 1 && top != 0) show(top)
    }
    override fun onResume() { super.onResume(); resumed = true; Updater.resume(this)
        resetInboxConnection()
        if (Prefs.configured(this)) Thread { runCatching { MessageStore.prepare(this) } }.start()
        if (top == 0) pane.onShow() else show(top); refreshBadge(); h.removeCallbacks(tick); h.postDelayed(tick, 20_000) }
    override fun onPause() { resumed = false; h.removeCallbacks(tick); if (top == 0) pane.onHide(); super.onPause() }
    override fun onDestroy() { ibGeneration++; h.removeCallbacks(tick); super.onDestroy() }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i)
        if (!validNotification(i)) { Toast.makeText(this, "这条通知来自另一台服务器，请先在设置中切换连接", Toast.LENGTH_LONG).show(); return }
        if (i.getStringExtra("tab") == "agent") { nav.selectedItemId = R.id.n_agent; i.getStringExtra("sid")?.let { pane.open(it) } } }
    @Deprecated("") override fun onActivityResult(rq: Int, rc: Int, data: Intent?) { super.onActivityResult(rq, rc, data); pane.onActivityResult(rq, rc, data) }
    @Deprecated("") override fun onBackPressed() { if (top == 0 && pane.back()) return; super.onBackPressed() }

    // ───────────── 消息 ─────────────
    private var ibConnection: Cx.Connection? = null
    private var ibGeneration = 0
    private val ibN = java.util.TreeMap<Long, JSONObject>()
    private var ibLoaded = false
    private var ibMore = false
    private var ibBusy = false
    private var ibError: String? = null
    private var ibHigh = 0L
    private var ibUnread = 0
    private fun resetInboxConnection() {
        val next = Cx.connection(this)
        if (next == ibConnection) return
        ibConnection = next; ibGeneration++; ibN.clear(); ibLoaded = false; ibMore = false; ibBusy = false; ibError = null; ibHigh = 0; ibUnread = 0; setBadge(0)
    }
    private fun currentInbox(conn: Cx.Connection, generation: Int) = !isDestroyed && conn.isCurrent(this) && generation == ibGeneration
    private fun refreshBadge() {
        resetInboxConnection()
        if (!Prefs.configured(this)) return
        val conn = ibConnection!!; val generation = ibGeneration; val store = MessageStore(this, conn)
        val seen = store.seen
        Thread {
            val r = Cx.req(this, "GET", "/notes/count?after=$seen", connection = conn)
            val o = runCatching { if (r.first == 200) JSONObject(r.second) else null }.getOrNull()
            runOnUiThread {
                if (!resumed || !currentInbox(conn, generation) || o == null || store.seen != seen) return@runOnUiThread
                if (store.sync(o, highWater = true)) {
                    ibGeneration++; ibN.clear(); ibLoaded = false; ibBusy = false
                    if (top == 1) inboxShow() else refreshBadge()
                    return@runOnUiThread
                }
                ibHigh = o.optLong("last"); ibUnread = o.optInt("count"); setBadge(ibUnread)
            }
        }.start()
    }
    private fun setBadge(c: Int) { if (c <= 0) nav.removeBadge(R.id.n_msg) else nav.getOrCreateBadge(R.id.n_msg).apply { number = c; maxCharacterCount = 4; backgroundColor = UI.col(this@MainActivity, R.color.strong) } }
    private fun inboxShow() {
        resetInboxConnection()
        box.removeAllViews()
        if (ibLoaded) renderInbox() else {
            UI.largeTitle(this, box, "消息")
            UI.empty(this, UI.group(this, box), if (Prefs.configured(this)) "加载中…" else "连接服务器后，任务与交互提醒会显示在这里")
        }
        if (ibBusy || !Prefs.configured(this)) { srl.isRefreshing = false; return }
        loadInbox(false)
    }
    private fun loadInbox(older: Boolean) {
        if (ibBusy) return
        ibBusy = true
        val conn = ibConnection ?: return; val generation = ibGeneration; val first = !ibLoaded
        val store = MessageStore(this, conn); val seen = store.seen
        val before = ibN.firstKeyOrNull()
        val after = ibN.lastKeyOrNull() ?: 0L
        Thread {
            var count: JSONObject? = null
            var reset = false
            val rows = java.util.TreeMap<Long, JSONObject>()
            var more = false
            val result = runCatching {
                val cr = Cx.req(this, "GET", "/notes/count?after=$seen", connection = conn)
                check(cr.first == 200) { Cx.err(cr) }
                count = JSONObject(cr.second)
                // Epoch mutation is committed only on the UI thread, after stale-response checks.
                reset = (count!!.optString("data_epoch").isNotEmpty() && store.epoch.isNotEmpty() && store.epoch != count!!.optString("data_epoch")) || count!!.optLong("last") < seen
                var cursor = after
                // Older servers return descending incremental pages. Refresh their newest page instead.
                val incremental = !older && !first && !reset && count!!.has("data_epoch")
                do {
                    val path = when {
                        older && !reset -> "/notes?before=${before ?: Long.MAX_VALUE}&limit=50"
                        incremental -> "/notes?since=$cursor&limit=200"
                        else -> "/notes?limit=50"
                    }
                    val r = Cx.req(this, "GET", path, connection = conn)
                    check(r.first == 200) { Cx.err(r) }
                    val page = JSONArray(r.second)
                    for (i in 0 until page.length()) page.getJSONObject(i).let { rows[it.getLong("seq")] = it }
                    more = page.length() >= 50
                    if (!incremental || page.length() < 200) break
                    val next = rows.lastKey()
                    check(next > cursor) { "服务器消息游标未前进" }; cursor = next
                } while (currentInbox(conn, generation))
            }
            runOnUiThread {
                if (!currentInbox(conn, generation)) return@runOnUiThread
                ibBusy = false; srl.isRefreshing = false
                if (result.isFailure) ibError = result.exceptionOrNull()?.message ?: "暂时无法连接服务器"
                else {
                    val changed = store.sync(count!!, highWater = true)
                    if (reset || changed || (!older && !count!!.has("data_epoch"))) { ibN.clear(); ibLoaded = false }
                    if (first || !ibLoaded || older) ibMore = more
                    ibN.putAll(rows); ibLoaded = true; ibError = null
                    ibHigh = count!!.optLong("last")
                    ibUnread = if (store.seen == seen) count!!.optInt("count") else ibUnread
                    if (changed) { ibUnread = ibN.keys.count { it > store.seen }; refreshBadge() }
                    setBadge(ibUnread)
                }
                if (top == 1 && resumed) { box.removeAllViews(); renderInbox() }
            }
        }.start()
    }
    private fun java.util.TreeMap<Long, JSONObject>.firstKeyOrNull() = if (isEmpty()) null else firstKey()
    private fun java.util.TreeMap<Long, JSONObject>.lastKeyOrNull() = if (isEmpty()) null else lastKey()
    private fun renderInbox() {
        val conn = ibConnection ?: return
        val store = MessageStore(this, conn)
        val items = ibN.entries.sortedByDescending { it.key }
        UI.largeTitle(this, box, "消息", "近 7 天 · $ibUnread 条未读")
        if (!taskNotificationsEnabled(this)) UI.row(this, UI.group(this, box), "开启任务通知", sub = "允许通知后，离开对话也能收到提醒") { notificationSettings() }
        ibError?.let {
            UI.row(this, UI.group(this, box), "刷新失败，点击重试", sub = if (ibLoaded) "已保留上次消息。$it" else it) { inboxShow() }
        }
        if (ibUnread > 0) UI.action(this, UI.group(this, box), "全部标为已读") {
            store.markAll(ibHigh); ibUnread = ibN.keys.count { it > store.seen }; setBadge(ibUnread); box.removeAllViews(); renderInbox(); refreshBadge()
        }
        val dayF = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()); val today = dayF.format(java.util.Date())
        val yday = dayF.format(java.util.Date(System.currentTimeMillis() - 86400000L))
        val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        var group: LinearLayout? = null; var day = ""
        if (items.isEmpty() && ibError == null) UI.empty(this, UI.group(this, box), "暂无消息，任务完成、审批或提问后会出现在这里")
        for ((seq, note) in items) {
            val time = java.util.Date((note.optDouble("ts") * 1000).toLong()); val d = dayF.format(time)
            if (d != day || group == null) { day = d; group = UI.group(this, box, when (d) { today -> "今天"; yday -> "昨天"; else -> d }) }
            val glyph = when (note.optString("kind")) { "ask" -> "❓"; "perm" -> "⚠️"; "error", "unknown" -> "❗"; "cancelled" -> "■"; else -> "✓" }
            val row = UI.row(this, group, "$glyph ${note.optString("title")}", df.format(time), sub = note.optString("text").take(160)) {
                val sid = note.optString("sid")
                if (sid.isEmpty() || sid == "null") Toast.makeText(this, "该消息没有关联对话", Toast.LENGTH_SHORT).show()
                else { nav.selectedItemId = R.id.n_agent; pane.open(sid) }
            }
            if (seq > store.seen) { row.background = UI.round(this, UI.col(this, R.color.primaryC), 0); row.contentDescription = "未读，${note.optString("title")}，${note.optString("text")}" }
        }
        if (ibMore) UI.row(this, UI.group(this, box), if (ibBusy) "加载中…" else "查看更早消息") { loadInbox(true) }
        else if (ibLoaded && items.isNotEmpty()) UI.footer(this, box, "已显示全部保留消息")
        UI.footer(this, box, "查看消息不会自动清除未读；确认后可点「全部标为已读」。审批是否有效以对话当前状态为准。")
    }
}
