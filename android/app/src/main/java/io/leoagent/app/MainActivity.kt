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
    private lateinit var pane: CodexPane
    private lateinit var agentView: View
    private val sp by lazy { getSharedPreferences("ui", MODE_PRIVATE) }
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refreshBadge(); h.postDelayed(this, 60_000) } }
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
        pane = CodexPane(this); agentView = pane.build(intent.getStringExtra("sid")); agentView.visibility = View.GONE; content.addView(agentView)
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
        val start = when { !Prefs.configured(this) -> 2; intent.getStringExtra("tab") == "agent" -> 0; else -> sp.getInt("top", 0).coerceIn(0, 2) }
        nav.selectedItemId = listOf(R.id.n_agent, R.id.n_msg, R.id.n_set)[start]; selectTop(start)
        ensureChannels(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (Prefs.configured(this)) Updater.check(this, false)
    }

    private fun selectTop(i: Int) {
        if (top == 0 && i != 0) pane.onHide()
        top = i; sp.edit().putInt("top", i).apply()
        agentView.visibility = if (i == 0) View.VISIBLE else View.GONE; srl.visibility = if (i == 0) View.GONE else View.VISIBLE
        window.statusBarColor = UI.col(this, R.color.glowA)
        if (i == 0) { pane.onShow(); return }
        show(i)
    }
    private fun show(i: Int) {
        box.removeAllViews(); srl.isRefreshing = false
        if (i == 1) inboxShow() else { UI.largeTitle(this, box, "设置"); renderMe() }
    }
    private fun fail(r: Pair<Int, String>) {
        val g = UI.card(this, box, "连接失败")
        g.addView(UI.text(this, if (r.first == 0) "连不上服务器（电脑关机 / 断网 / 隧道掉线？）" else "HTTP ${r.first}", 15f))
        g.addView(UI.text(this, r.second.take(160), 13f, R.color.muted).apply { setPadding(0, dp(6), 0, 0) })
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
        val g2 = UI.group(this, box, "应用")
        UI.row(this, g2, "当前版本", "v" + packageManager.getPackageInfo(packageName, 0).versionName)
        UI.action(this, g2, "检查更新") { Updater.check(this, true) }
        UI.action(this, g2, "浏览器下载最新安装包") { Updater.openBrowser(this) }
        UI.action(this, g2, "上传日志") { uploadLogs() }
        val g4 = UI.group(this, box, "系统")
        UI.row(this, g4, "电池优化（建议无限制）") { try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) { appDetails() } }
        UI.row(this, g4, "通知设置") { appDetails() }
        UI.footer(this, box, "Agent 运行完成或需要审批时会发通知。部分 ROM 需要允许自启动 / 后台运行。")
    }
    private fun appDetails() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun uploadLogs() {
        val et = android.widget.EditText(this).apply { hint = "备注（可选）：发生了什么"; setPadding(48, 32, 48, 32) }
        android.app.AlertDialog.Builder(this).setTitle("上传日志").setMessage("打包本机日志（含设备信息、近期运行记录）发送到你的服务器").setView(et)
            .setPositiveButton("上传") { _, _ ->
                Toast.makeText(this, "上传中…", Toast.LENGTH_SHORT).show()
                val note = et.text.toString()
                Thread { val r = AppLog.upload(this, note); runOnUiThread { Toast.makeText(this, r?.let { "上传失败：$it" } ?: "日志已上传", Toast.LENGTH_LONG).show() } }.start()
            }.setNegativeButton("取消", null).show()
    }

    override fun onResume() { super.onResume(); Updater.resume(this)
        if (top == 0) pane.onShow() else show(top); refreshBadge(); h.postDelayed(tick, 20_000) }
    override fun onPause() { h.removeCallbacks(tick); super.onPause() }
    override fun onStop() { if (top == 0) pane.onHide(); super.onStop() }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i)
        if (i.getStringExtra("tab") == "agent") { nav.selectedItemId = R.id.n_agent; i.getStringExtra("sid")?.let { pane.open(it) } } }
    @Deprecated("") override fun onActivityResult(rq: Int, rc: Int, data: Intent?) { super.onActivityResult(rq, rc, data); pane.onActivityResult(rq, rc, data) }
    @Deprecated("") override fun onBackPressed() { if (top == 0 && pane.back()) return; super.onBackPressed() }

    // ───────────── 消息 (Agent 完成 / 审批 / 提问通知) ─────────────
    private fun refreshBadge() { if (!Prefs.configured(this)) return; Thread {
        val ln = sp.getInt("seen_note", -1)
        val n = Cx.req(this, "GET", "/notes/count?after=${maxOf(ln, 0)}")
        val c = try { if (ln >= 0 && n.first == 200) JSONObject(n.second).optInt("count") else 0 } catch (_: Exception) { 0 }
        runOnUiThread { setBadge(c) }
    }.start() }
    private fun setBadge(c: Int) { if (top == 1 || c == 0) nav.removeBadge(R.id.n_msg) else nav.getOrCreateBadge(R.id.n_msg).apply { number = c; backgroundColor = UI.col(this@MainActivity, R.color.strong) } }
    private val ibN = java.util.TreeMap<Int, JSONObject>()
    private var ibLoaded = false; private var ibMore = false; private var ibBusy = false; private var ibSeen = -1
    private fun ibMerge(nr: Pair<Int, String>, page: Boolean, lim: Int) {
        if (nr.first == 200) JSONArray(nr.second).let { for (i in 0 until it.length()) it.getJSONObject(i).let { x -> ibN[x.optInt("seq")] = x }
            if (page) ibMore = it.length() >= lim }
    }
    private fun inboxShow() {
        if (ibLoaded) renderInbox() else { UI.largeTitle(this, box, "消息"); UI.empty(this, UI.group(this, box), if (Prefs.configured(this)) "加载中…" else "未连接服务器") }
        if (ibBusy || !Prefs.configured(this)) return; ibBusy = true
        val first = !ibLoaded
        Thread {
            val r = if (first) Cx.req(this, "GET", "/notes?limit=50") else Cx.req(this, "GET", "/notes?since=${ibN.lastKey()}&limit=200")
            runOnUiThread {
                ibBusy = false; srl.isRefreshing = false
                if (r.first != 200 && !ibLoaded) { if (top == 1) { box.removeAllViews(); UI.largeTitle(this, box, "消息"); fail(r) }; return@runOnUiThread }
                if (first) { ibN.clear(); ibN[0] = JSONObject().put("_sentinel", 1) }
                ibMerge(r, first, 50)
                if (first) ibLoaded = true
                if (top == 1) { box.removeAllViews(); renderInbox() }
            }
        }.start()
    }
    private fun inboxOlder() {
        if (ibBusy) return; ibBusy = true
        val minN = ibN.keys.filter { ibN[it]!!.optInt("_sentinel") == 0 }.minOrNull() ?: 999999999
        Thread {
            val r = Cx.req(this, "GET", "/notes?limit=50&before=$minN")
            runOnUiThread { ibBusy = false; ibMerge(r, true, 50); if (top == 1) { box.removeAllViews(); renderInbox() } }
        }.start()
    }
    private fun renderInbox() {
        val ln = sp.getInt("seen_note", -1)
        if (ibSeen < 0) ibSeen = ln
        val items = ibN.filter { it.value.optInt("_sentinel") == 0 }.entries.sortedByDescending { it.key }
        sp.edit().putInt("seen_note", maxOf(ibN.lastKey(), ln, 0)).apply(); setBadge(0)
        UI.largeTitle(this, box, "消息", "Agent 通知 · ${items.count { ibSeen >= 0 && it.key > ibSeen }} 条未读")
        val dayF = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()); val today = dayF.format(java.util.Date())
        val yday = dayF.format(java.util.Date(System.currentTimeMillis() - 86400000L))
        val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        var g: LinearLayout? = null; var curDay = ""
        if (items.isEmpty()) UI.empty(this, UI.group(this, box), "暂无消息")
        for ((q, n) in items) {
            val t = n.optDouble("ts"); val d = dayF.format(java.util.Date((t * 1000).toLong()))
            if (d != curDay || g == null) { curDay = d; g = UI.group(this, box, when (d) { today -> "今天"; yday -> "昨天"; else -> d.substring(5).replace("-", "月") + "日" }) }
            val gg = g!!; val kind = n.optString("kind")
            UI.row(this, gg, (if (kind == "ask") "❓ " else if (kind == "perm") "⚠️ " else "✓ ") + n.optString("title"), df.format(java.util.Date((t * 1000).toLong())), sub = n.optString("text").take(80)) {
                nav.selectedItemId = R.id.n_agent; pane.open(n.optString("sid")) }
            if (ibSeen >= 0 && q > ibSeen) { val v = gg.getChildAt(gg.childCount - 1); v.background = UI.round(this, UI.col(this, R.color.primaryC), 0) }
        }
        if (ibMore) UI.row(this, UI.group(this, box), if (ibBusy) "加载中…" else "查看更早消息") { inboxOlder() }
        else UI.footer(this, box, "没有更早的消息了")
    }
}
