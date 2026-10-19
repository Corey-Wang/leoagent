package io.leoagent.app

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Agent 标签页: Codex 对话 (由 MainActivity 托管) */
class CodexPane(val act: AppCompatActivity) : android.content.ContextWrapper(act) {
    companion object { @Volatile var visibleSession: String? = null }
    private lateinit var drawer: DrawerLayout
    private lateinit var side: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var thumbs: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private var projects = JSONArray()
    private var models = JSONArray()
    private var agents = JSONArray(); private var defAgent = "codex"
    private fun agentObj(id: String): JSONObject? { for (i in 0 until agents.length()) agents.getJSONObject(i).let { if (it.optString("id") == id) return it }; return null }
    private fun agentName(id: String) = agentObj(id)?.optString("name") ?: when (id) { "codex" -> "Codex"; "claude" -> "Claude"; "deepseek" -> "DeepSeek"; "opencode" -> "OpenCode"; else -> id }
    private fun agentIcon(id: String) = agentObj(id)?.optString("icon") ?: "•"
    private fun usable() = (0 until agents.length()).map { agents.getJSONObject(it) }.filter { it.optBoolean("enabled") && it.optBoolean("ok") }
    /** 新对话用的 agent */
    private fun newAgent() = CxPrefs.agent(this).ifEmpty { defAgent }.let { a -> if (agents.length() == 0 || usable().any { it.optString("id") == a }) a else "codex" }
    private fun curAgent() = session?.optString("agent")?.ifEmpty { null } ?: newAgent()
    private var cfgOpts = JSONArray()  // 当前非 Codex 会话的 configOptions
    private var session: JSONObject? = null
    private var sid: String? = null
    private var lastSeq = 0
    private var running = false
    @Volatile private var gen = 0
    private val tools = HashMap<String, Pair<LinearLayout, JSONObject>>()
    private val perms = LinkedHashMap<String, JSONObject>()
    private var permShown: String? = null
    private var curMsg: MdView? = null; private var curMsgSb = StringBuilder()
    private var curThought: TextView? = null; private var curThoughtSb = StringBuilder()
    private val thoughts = ArrayList<View>()
    private var renderPending = false
    private var live = false
    private var shown = 0; private var lastParse = 0L; private var twOn = false; private var acc = 0f
    private var typing: View? = null
    private var pendingEcho: String? = null
    private val tw = object : Runnable { override fun run() {
        val m = curMsg; if (m == null) { twOn = false; return }
        val total = curMsgSb.length
        if (shown < total) {
            // 匀速打字: 约 280ms 内追平积压 (上游约每 190ms 一批), 不足 1 字时累积
            acc += maxOf((total - shown) * 16f / 280f, 0.35f); val n = acc.toInt(); acc -= n
            if (n == 0) { h.postDelayed(this, 16); return }
            val from = shown; shown = minOf(total, shown + n)
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastParse >= 60 || shown >= total) { m.setMd(curMsgSb.substring(0, shown)); lastParse = now }
            else m.appendFast(curMsgSb.substring(from, shown))
            stick()
        }
        if (shown < total) h.postDelayed(this, 16) else twOn = false
    } }
    private fun stick() { if (atBottom) scroll.post { scroll.scrollTo(0, maxOf(0, list.height - scroll.height)) } }
    private fun showTyping() {
        if (typing != null) return
        val t = UI.text(this, "●  ●  ●", 14f, R.color.muted).apply { setPadding(dp(4), dp(10), 0, dp(6)) }
        t.animate().alpha(0.25f).setDuration(600).withEndAction(object : Runnable { override fun run() { if (typing === t) t.animate().alpha(if (t.alpha < 0.5f) 1f else 0.25f).setDuration(600).withEndAction(this).start() } }).start()
        typing = t; list.addView(t); stick()
    }
    private fun hideTyping() { typing?.let { list.removeView(it) }; typing = null }
    private val images = ArrayList<Pair<String, String>>()
    private var atBottom = true
    private var unread = 0
    private lateinit var fab: FrameLayout
    private lateinit var fabBadge: TextView
    private lateinit var pip: ScreenPip
    private fun updateFab() {
        if (!::fab.isInitialized) return
        if (atBottom) unread = 0
        val show = !atBottom && list.height > scroll.height * 2
        if (show && fab.visibility != View.VISIBLE) { fab.alpha = 0f; fab.visibility = View.VISIBLE; fab.animate().alpha(1f).setDuration(150).start() }
        if (!show) fab.visibility = View.GONE
        fabBadge.visibility = if (show && unread > 0) View.VISIBLE else View.GONE
        fabBadge.text = if (unread > 99) "99+" else unread.toString()
    }
    private fun dp(v: Int) = UI.dp(this, v)
    private fun col(id: Int) = UI.col(this, id)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun bg(f: () -> Unit) = Thread { f() }.start()

    fun runOnUiThread(f: () -> Unit) = act.runOnUiThread(f)
    lateinit var root: View
    fun build(initSid: String?): View {
        ensureChannels(this); ensureCodexChannel(this)
        drawer = DrawerLayout(this)
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UI.pageBg(context) }
        // 顶栏
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(4), dp(6)) }
        bar.addView(iconBtn("☰") { drawer.openDrawer(GravityCompat.START) })
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = UI.ripple(context, col(R.color.surface), 10); setPadding(dp(8), dp(2), dp(8), dp(2)); setOnClickListener { pickModel() } }
        titleTv = UI.text(this, "Codex", 17f, R.color.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER }
        subTv = UI.text(this, "", 12.5f, R.color.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER }
        tcol.addView(titleTv); tcol.addView(subTv)
        bar.addView(tcol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(iconBtn("▣") { pip.toggle() }.apply { textSize = 20f; contentDescription = "桌面画中画" })
        bar.addView(iconBtn("±") { showDiff() })
        bar.addView(iconBtn("⋯") { moreMenu() })
        main.addView(bar)
        main.addView(View(this).apply { setBackgroundColor(col(R.color.border)) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(24)) }
        scroll = ScrollView(this).apply { addView(list); isFillViewport = true
            viewTreeObserver.addOnScrollChangedListener { atBottom = !canScrollVertically(1) || (list.height - (scrollY + height) < dp(80)); updateFab(); if (scrollY < dp(40) && histMore && !loadingOlder && list.height > height) loadOlder() } }
        list.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(p: View?, c: View?) { if (!atBottom && c !== typing && c !== skeleton) { unread++; updateFab() } }
            override fun onChildViewRemoved(p: View?, c: View?) {} })
        val chatFrame = FrameLayout(this)
        chatFrame.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        fab = FrameLayout(this).apply { visibility = View.GONE; elevation = dp(6).toFloat(); background = UI.round(context, col(R.color.surface), 22)
            addView(TextView(context).apply { text = "↓"; textSize = 20f; typeface = UI.BOLD; gravity = Gravity.CENTER; setTextColor(col(R.color.primary)) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            setOnClickListener { unread = 0; atBottom = true; scroll.smoothScrollTo(0, maxOf(0, list.height - scroll.height)); updateFab() }; contentDescription = "回到最新" }
        fabBadge = TextView(this).apply { textSize = 10f; setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER; background = UI.round(context, col(R.color.primary), 9); setPadding(dp(4), 0, dp(4), 0); visibility = View.GONE; elevation = dp(7).toFloat() }
        chatFrame.addView(fab, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(16)) })
        chatFrame.addView(fabBadge, FrameLayout.LayoutParams(WRAP_CONTENT, dp(18), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(12), dp(46)) })
        pip = ScreenPip(chatFrame)
        main.addView(chatFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        // 输入区
        thumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(6), dp(14), 0); visibility = View.GONE }
        main.addView(thumbs)
        val comp = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(10), dp(8), dp(10), dp(10)) }
        comp.addView(circle("＋", R.color.surface, R.color.text) { pickImage() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        input = EditText(this).apply {
            hint = "给 Codex 发消息"; textSize = 17f; setTextColor(col(R.color.text)); setHintTextColor(col(R.color.muted)); maxLines = 6; minHeight = dp(40)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = UI.card(context, 22); setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        comp.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
        VoiceInput(act, input, comp) { if (!running) send() }
        sendBtn = circle("↑", R.color.primary, android.R.color.white) { if (running) stop() else send() }
        comp.addView(sendBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        main.addView(comp)
        drawer.addView(main, DrawerLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UI.pageBg(context) }
        drawer.addView(side, DrawerLayout.LayoutParams(dp(300), MATCH_PARENT, Gravity.START))
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() { override fun onDrawerOpened(v: View) { loadThreads() } })
        root = drawer
        sid = initSid ?: CxPrefs.session(this)
        refreshMeta(); openSession(sid)
        return drawer
    }
    fun back(): Boolean { if (drawer.isDrawerOpen(GravityCompat.START)) { drawer.closeDrawers(); return true }; return false }
    fun open(id: String) { drawer.closeDrawers(); if (id != sid) openSession(id) }
    private var stopped = false
    fun onShow() { Http.warm(CxPrefs.base(this) + "/agents", CxPrefs.token(this)); visibleSession = sid; pip.resume(); if (stopped) { stopped = false; if (sid != null) startStream() } }
    fun onHide() { pip.pause(); visibleSession = null; gen++; stopped = true; if (running || perms.isNotEmpty()) CodexService.start(this) }

    // ───────── 组件 ─────────
    private val GLYPH = mapOf("☰" to R.drawable.ms_menu, "±" to R.drawable.ms_difference, "⋯" to R.drawable.ms_more_horiz, "✕" to R.drawable.ms_close,
        "＋" to R.drawable.ms_add, "↑" to R.drawable.ms_arrow_upward, "■" to R.drawable.ms_stop)
    private fun iconBtn(s: String, f: () -> Unit) = TextView(this).apply {
        text = s; textSize = 22f; gravity = Gravity.CENTER; setTextColor(col(R.color.text)); layoutParams = LinearLayout.LayoutParams(dp(DS.TOUCH), dp(DS.TOUCH))
        GLYPH[s]?.let { UI.glyph(context, this, it, col(R.color.text), android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22808080), null, UI.round(context, android.graphics.Color.WHITE, 22)), 24) } ?: run { background = UI.ripple(context, android.graphics.Color.TRANSPARENT, 22) }
        setOnClickListener { f() }
    }
    private fun circle(s: String, bgc: Int, fg: Int, f: () -> Unit) = TextView(this).apply {
        text = s; textSize = 20f; typeface = UI.BOLD; gravity = Gravity.CENTER; setTextColor(col(fg)); tag = intArrayOf(bgc, fg)
        GLYPH[s]?.let { UI.glyph(context, this, it, col(fg), if (bgc == R.color.primary) UI.grad(context, 20) else UI.card(context, 20), 22) } ?: run { background = UI.ripple(context, col(bgc), 20) }
        setOnClickListener { f() }
    }
    private fun setRunning(r: Boolean) {
        running = r; UI.glyph(this, sendBtn, if (r) R.drawable.ms_stop else R.drawable.ms_arrow_upward, 0xFFFFFFFF.toInt(), UI.grad(this, 20), if (r) 20 else 22)
    }

    // ───────── 元数据 / 抽屉 ─────────
    private fun refreshMeta(): Unit = bg {
        val p = Cx.req(this, "GET", "/projects"); val m = Cx.req(this, "GET", "/models"); val ag = Cx.req(this, "GET", "/agents")
        if (ag.first == 200) JSONObject(ag.second).let { o -> runOnUiThread { agents = o.optJSONArray("agents") ?: JSONArray(); defAgent = o.optString("default", "codex"); if (sid == null && newAgent() != "codex") loadCfg() } }
        runOnUiThread {
            if (p.first == 200) projects = JSONArray(p.second) else toast("连接失败：" + Cx.err(p))
            if (m.first == 200) JSONObject(m.second).let { models = it.optJSONArray("models") ?: JSONArray()
                if (CxPrefs.model(this).isEmpty()) { val d = it.optString("default"); val mm = Regex("(.*)\\[(.*)]").find(d)
                    if (mm != null) CxPrefs.setModel(this, mm.groupValues[1], "medium") } }
            if (CxPrefs.project(this) < 0 && projects.length() > 0) CxPrefs.setProject(this, projects.getJSONObject(0).getInt("id"))
            updateTitle(); buildDrawer(); if (sid == null) { list.removeAllViews(); emptyState() }
        }
    }
    private fun projName(id: Int): String { for (i in 0 until projects.length()) projects.getJSONObject(i).let { if (it.getInt("id") == id) return it.getString("name") }; return "选择项目" }
    private fun buildDrawer() {
        side.removeAllViews()
        val na = newAgent(); val multi = usable().size > 1
        side.addView(UI.text(this, agentName(na) + if (multi) "  ⌄" else "", 24f, R.color.text, true).apply { setPadding(dp(18), dp(18), dp(18), dp(4))
            if (multi) { background = UI.ripple(context, col(R.color.bg), 10); setOnClickListener { pickAgent() } } })
        side.addView(UI.text(this, "运行在你的 Mac" + (if (multi && CxPrefs.agentFilter(this)) " · 仅显示 ${agentName(na)} 会话" else ""), 12.5f, R.color.muted).apply { setPadding(dp(18), 0, dp(18), dp(10)) })
        val pid = CxPrefs.project(this)
        val pg = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UI.round(context, col(R.color.surface), 12) }
        side.addView(pg, UI.lp(this, 12, 0, 12, 0))
        UI.row(this, pg, "📁  " + projName(pid), null, sub = projPath(pid)) { pickProject() }
        side.addView(UI.filledButton(this, "＋  新对话") { newChat() }.apply { textSize = 16f }, UI.lp(this, 12, 10, 12, 6))
        val search = EditText(this).apply { hint = "搜索会话"; textSize = 14.5f; isSingleLine = true; setTextColor(col(R.color.text)); setHintTextColor(col(R.color.muted))
            background = UI.round(context, col(R.color.surface2), 10); setPadding(dp(12), dp(8), dp(12), dp(8)); setText(threadQuery) }
        side.addView(search, UI.lp(this, 12, 4, 12, 4))
        val sl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8)) }
        side.addView(ScrollView(this).apply { addView(sl) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UI.round(context, col(R.color.surface), 12) }
        side.addView(g, UI.lp(this, 12, 6, 12, 14))
        UI.row(this, g, "设置", null) { settings() }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { threadQuery = e.toString().trim(); h.removeCallbacks(searchR); h.postDelayed(searchR, 300) }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {} })
        threadList = sl; loadThreads()
    }
    private var threadQuery = ""
    private var threadList: LinearLayout? = null
    private val expanded = HashSet<String>()
    private val searchR = Runnable { loadThreads() }
    /** 会话列表 = Codex 自己的线程库 (与桌面端一致: 置顶 / 项目分组 / 对话, 最近优先) */
    private fun loadThreads() {
        val sl = threadList ?: return
        bg {
            val r = Cx.req(this, "GET", "/threads?q=" + Uri.encode(threadQuery) + (if (CxPrefs.agentFilter(this) && usable().size > 1) "&agent=" + newAgent() else ""))
            runOnUiThread {
                sl.removeAllViews()
                if (r.first != 200) { sl.addView(UI.text(this, Cx.err(r), 14f, R.color.muted).apply { setPadding(dp(24), dp(8), dp(24), 0) }); return@runOnUiThread }
                val a = JSONArray(r.second); val all = (0 until a.length()).map { a.getJSONObject(it) }
                if (all.isEmpty()) { sl.addView(UI.text(this, if (threadQuery.isEmpty()) "还没有会话" else "没有匹配的会话", 14f, R.color.muted).apply { setPadding(dp(24), dp(10), dp(24), 0) }); return@runOnUiThread }
                val pinned = all.filter { it.optBoolean("pinned") }
                val inProj = all.filter { !it.optBoolean("pinned") && !it.isNull("project") && it.optString("project").isNotEmpty() }
                val chats = all.filter { !it.optBoolean("pinned") && (it.isNull("project") || it.optString("project").isEmpty()) }
                if (threadQuery.isNotEmpty()) { all.forEach { threadRow(sl, it) }; return@runOnUiThread }   // 搜索: 平铺
                val openSet = folderOpen()
                fun isCur(t: JSONObject) = (t.optString("sid").isNotEmpty() && t.optString("sid") == sid) || (!t.isNull("id") && t.optString("id") == session?.optString("acp_sid"))
                val anyCur = all.any { isCur(it) }
                fun section(name: String, items: List<JSONObject>, key: String, limit: Int) {
                    if (items.isEmpty()) return
                    val hasCur = items.any { isCur(it) } || (!anyCur && sid != null && key == session?.optString("project"))
                    if (hasCur && autoOpened != sid) { openSet.add(key); autoOpened = sid; saveFolders(openSet) }
                    val open = key in openSet
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(46)
                        setPadding(dp(12), 0, dp(12), 0); background = UI.ripple(context, col(R.color.bg), 12)
                        setOnClickListener { if (key in openSet) openSet.remove(key) else openSet.add(key); saveFolders(openSet); loadThreads() } }
                    val ic = when (key) { "_pin" -> "📌"; "_chats" -> "💬"; else -> "📁" }
                    row.addView(UI.text(this, ic, 15f, R.color.text).apply { setPadding(0, 0, dp(10), 0) })
                    row.addView(UI.text(this, name, 15f, R.color.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    if (hasCur && !open) row.addView(View(this).apply { background = UI.round(context, col(R.color.primary), 4) }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(8) })
                    row.addView(UI.text(this, items.size.toString(), 12f, R.color.muted).apply { background = UI.round(context, col(R.color.surface2), 10); setPadding(dp(8), dp(1), dp(8), dp(1)) })
                    row.addView(ImageView(this).apply { setImageResource(R.drawable.ms_chevron_right); setColorFilter(col(R.color.muted)); rotation = if (open) 90f else 0f }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { leftMargin = dp(6) })
                    sl.addView(row, UI.lp(this, 8, 2, 8, 0))
                    if (!open) return
                    val more = key in expanded
                    val shown = if (more) items else items.take(limit).let { l -> if (l.none { isCur(it) }) l + items.filter { isCur(it) } else l }
                    for (t in shown) threadRow(sl, t, true)
                    if (!more && items.size > shown.size) sl.addView(UI.text(this, "显示更多（${items.size - shown.size}）", 13.5f, R.color.primary).apply {
                        setPadding(dp(46), dp(6), dp(24), dp(6)); setOnClickListener { expanded.add(key); loadThreads() } })
                }
                section("置顶", pinned, "_pin", 20)
                inProj.groupBy { it.optString("project") }.entries.sortedWith(compareByDescending<Map.Entry<String, List<JSONObject>>> { e -> e.value.any { isCur(it) } || (!anyCur && e.key == session?.optString("project")) }.thenByDescending { e -> e.value.maxOf { it.optDouble("updated") } }).forEach { (p, l) -> section(p, l, p, 8) }
                section("对话", chats, "_chats", 15)
            }
        }
    }
    private fun ago(t: Double): String { val d = (System.currentTimeMillis() / 1000.0 - t).toLong()
        return when { d < 60 -> "刚刚"; d < 3600 -> "${d / 60} 分钟前"; d < 86400 -> "${d / 3600} 小时前"; d < 86400 * 7 -> "${d / 86400} 天前"
            else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA).format(java.util.Date((t * 1000).toLong())) } }
    private var autoOpened: String? = null
    private fun folderOpen(): HashSet<String> = HashSet(getSharedPreferences("cx", 0).getStringSet("folders_open", emptySet())!!)
    private fun saveFolders(s: Set<String>) = getSharedPreferences("cx", 0).edit().putStringSet("folders_open", HashSet(s)).apply()
    private fun threadRow(sl: LinearLayout, t: JSONObject, nested: Boolean = false) {
        val sidT = t.optString("sid").ifEmpty { null }; val tid = if (t.isNull("id")) null else t.optString("id")
        val cur = (sidT != null && sidT == sid) || (tid != null && tid == session?.optString("acp_sid"))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8))
            background = UI.ripple(context, col(if (cur) R.color.surface2 else R.color.bg), 10) }
        box.addView(UI.text(this, (if (t.optBoolean("running")) "● " else "") + t.optString("title"), 15f, R.color.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        val origin = when (t.optString("origin")) { "desktop" -> "桌面"; "cli" -> "CLI"; "phone" -> "手机"; "acp" -> "ACP"; else -> "" }
        val sub = SpannableStringBuilder(ago(t.optDouble("updated")))
        val tag = t.optString("agent", "codex")
        if (tag != "codex") { sub.clear(); val st = sub.length; sub.append(agentIcon(tag) + " " + agentName(tag)); sub.setSpan(ForegroundColorSpan(col(R.color.primary)), st, sub.length, 0); sub.append(" · ").append(ago(t.optDouble("updated"))) }
        else if (origin.isNotEmpty()) sub.append(" · ").append(origin)
        if (t.optBoolean("open_on_mac")) { val st = sub.length; sub.append(" · Mac 上打开"); sub.setSpan(ForegroundColorSpan(col(R.color.weak)), st, sub.length, 0) }
        else if (t.optBoolean("active")) { val st = sub.length; sub.append(" · 活跃"); sub.setSpan(ForegroundColorSpan(col(R.color.down)), st, sub.length, 0) }
        if (t.isNull("project") || t.optString("project").isEmpty()) sub.append(" · ").append(t.optString("cwd").substringAfterLast('/'))
        box.addView(UI.text(this, sub, 12f, R.color.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(1), 0, 0) })
        box.setOnClickListener { drawer.closeDrawers(); openThread(t) }
        if (sidT != null) box.setOnLongClickListener { sessionMenu(JSONObject().put("id", sidT).put("title", t.optString("title")).put("codex", tid != null)); true }
        sl.addView(box, UI.lp(this, if (nested) 30 else 8, 0, 8, 1))
    }
    private fun openThread(t: JSONObject) {
        val sidT = t.optString("sid").ifEmpty { null }; val tid = if (t.isNull("id")) null else t.optString("id")
        val warn = t.optBoolean("open_on_mac"); val origin = t.optString("origin")
        if (tid == null) { if (sidT != null) openSession(sidT); return }
        bg {
            val r = Cx.req(this, "POST", "/threads/$tid/open", JSONObject(), 30000)
            runOnUiThread {
                if (r.first != 200) { toast("打开失败：" + Cx.err(r)); return@runOnUiThread }
                val o = JSONObject(r.second); refreshMeta(); openSession(o.getString("id"))
                if (origin == "desktop" || origin == "cli") pendingBanner = (if (origin == "desktop") "来自 Mac 桌面端" else "来自 Codex CLI") + " · 发送消息即可在手机上继续" +
                    if (warn || o.optBoolean("open_on_mac")) "\n⚠️ 该会话正在 Mac 上打开，两边同时继续可能冲突" else ""
            }
        }
    }
    private var pendingBanner: String? = null
    private fun projPath(id: Int): String? { for (i in 0 until projects.length()) projects.getJSONObject(i).let { if (it.getInt("id") == id) return it.getString("path") }; return null }
    private fun pickAgent() {
        val L = usable(); val na = newAgent(); val f = CxPrefs.agentFilter(this)
        val items = L.map { it.optString("icon") + "  " + it.optString("name") + (if (it.optString("id") == na) "   ✓" else "") } + listOf(if (f) "显示全部 Agent 的会话" else "只显示当前 Agent 的会话", "管理 Agent…")
        AlertDialog.Builder(this).setTitle("新对话使用").setItems(items.toTypedArray()) { _, w -> when {
            w < L.size -> { CxPrefs.setAgent(this, L[w].optString("id")); newChat(); buildDrawer(); updateTitle() }
            w == L.size -> { CxPrefs.setAgentFilter(this, !f); buildDrawer() }
            else -> agentSettings()
        } }.show()
    }
    fun agentSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val sws = HashMap<String, Switch>()
        for (i in 0 until agents.length()) { val a = agents.getJSONObject(i); val id = a.optString("id"); val ok = a.optBoolean("ok")
            val sw = Switch(this).apply { text = a.optString("icon") + "  " + a.optString("name") + (if (a.optString("version").isNotEmpty()) "  " + a.optString("version") else ""); textSize = 15.5f
                isChecked = a.optBoolean("enabled"); isEnabled = ok && id != "codex"; setPadding(0, dp(10), 0, 0) }
            sws[id] = sw; box.addView(sw)
            box.addView(UI.text(this, if (ok) "可用 · 已验证启动、认证与回复" + (if (a.has("secs")) "（${a.optDouble("secs")}s）" else "") else "不可用：" + a.optString("err").take(80), 12f, if (ok) R.color.muted else R.color.strong)) }
        var def = defAgent
        val defTv = UI.text(this, "默认 Agent：" + agentName(def) + "  ›", 15f, R.color.primary).apply { setPadding(0, dp(16), 0, dp(4)) }
        defTv.setOnClickListener { val L = usable(); AlertDialog.Builder(this).setItems(L.map { it.optString("name") }.toTypedArray()) { _, w -> def = L[w].optString("id"); defTv.text = "默认 Agent：" + agentName(def) + "  ›" }.show() }
        box.addView(defTv)
        box.addView(UI.text(this, "重新检测本机 Agent", 15f, R.color.primary).apply { setPadding(0, dp(10), 0, dp(8)); setOnClickListener { bg { Cx.req(this@CodexPane, "POST", "/agents/probe", JSONObject()) }; toast("正在检测，约 1 分钟后重新打开查看") } })
        AlertDialog.Builder(this).setTitle("Agent").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("保存") { _, _ ->
            val en = JSONArray(); sws.forEach { (k, v) -> if (v.isChecked) en.put(k) }
            bg { Cx.req(this, "POST", "/agents/settings", JSONObject().put("enabled", en).put("default", def)); runOnUiThread { CxPrefs.setAgent(this, ""); refreshMeta() } }
        }.setNegativeButton("取消", null).show()
    }
    private fun pickProject() {
        val names = (0 until projects.length()).map { projects.getJSONObject(it).let { p -> p.getString("name") + "   " + p.getString("path") } } + "＋ 添加目录…"
        AlertDialog.Builder(this).setTitle("选择项目").setItems(names.toTypedArray()) { _, w ->
            if (w == projects.length()) browse("~") else { CxPrefs.setProject(this, projects.getJSONObject(w).getInt("id")); newChat(); buildDrawer() }
        }.show()
    }
    private fun browse(path: String): Unit = bg {
        val r = Cx.req(this, "GET", "/browse?path=" + Uri.encode(path))
        runOnUiThread {
            if (r.first != 200) { toast(Cx.err(r)); return@runOnUiThread }
            val o = JSONObject(r.second); val cur = o.getString("path"); val ds = o.getJSONArray("dirs")
            val items = (0 until ds.length()).map { ds.getJSONObject(it).let { d -> (if (d.optBoolean("git")) "◆ " else "▸ ") + d.getString("name") } }
            val dlg = AlertDialog.Builder(this).setTitle(cur).setItems(items.toTypedArray()) { _, w -> browse(cur + "/" + ds.getJSONObject(w).getString("name")) }
                .setPositiveButton("添加此目录") { _, _ -> addProject(cur) }
            if (cur != "~") dlg.setNeutralButton("上一级") { _, _ -> browse(cur.substringBeforeLast('/')) }
            dlg.setNegativeButton("取消", null).show()
        }
    }
    private fun addProject(path: String): Unit = bg {
        val r = Cx.req(this, "POST", "/projects", JSONObject().put("path", path))
        runOnUiThread { if (r.first == 200) { CxPrefs.setProject(this, JSONObject(r.second).getInt("id")); newChat(); refreshMeta(); toast("已添加") } else toast(Cx.err(r)) }
    }
    private fun sessionMenu(s: JSONObject) {
        val id = s.getString("id")
        AlertDialog.Builder(this).setTitle(s.optString("title")).setItems(arrayOf("重命名", "删除")) { _, w ->
            if (w == 0) prompt("重命名", s.optString("title")) { t -> bg { Cx.req(this, "PATCH", "/sessions/$id", JSONObject().put("title", t)); runOnUiThread { buildDrawer(); if (id == sid) { titleTv.text = t } } } }
            else AlertDialog.Builder(this).setMessage(if (s.optBoolean("codex")) "从手机列表移除这份副本？（Codex 里的会话不会被删除）" else "删除这个对话？").setPositiveButton("删除") { _, _ ->
                bg { Cx.req(this, "DELETE", "/sessions/$id"); runOnUiThread { if (id == sid) newChat(); buildDrawer() } } }.setNegativeButton("取消", null).show()
        }.show()
    }
    private fun prompt(title: String, init: String, f: (String) -> Unit) {
        val e = EditText(this).apply { setText(init); setSelection(text.length) }
        AlertDialog.Builder(this).setTitle(title).setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) })
            .setPositiveButton("确定") { _, _ -> f(e.text.toString().trim()) }.setNegativeButton("取消", null).show()
    }
    fun settings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(UI.text(this, "Codex 口令（Token）", 13f, R.color.muted))
        val e = EditText(this).apply { setText(CxPrefs.token(context)); textSize = 13f }; box.addView(e)
        val sw = Switch(this).apply { text = "新对话默认每条命令都审批"; isChecked = CxPrefs.mode(context) == "ask"; textSize = 15f; setPadding(0, dp(12), 0, 0) }; box.addView(sw)
        box.addView(UI.text(this, "关闭 = 在项目内自由运行（Codex 默认，只有高风险操作才会请求审批）", 12f, R.color.muted))
        box.addView(UI.text(this, "Agent 管理（启用 / 默认）  ›", 15f, R.color.primary).apply { setPadding(0, dp(16), 0, dp(4)); setOnClickListener { agentSettings() } })
        AlertDialog.Builder(this).setTitle("设置").setView(box).setPositiveButton("保存") { _, _ ->
            CxPrefs.setToken(this, e.text.toString()); CxPrefs.setMode(this, if (sw.isChecked) "ask" else "auto"); refreshMeta()
        }.setNegativeButton("取消", null).show()
    }

    // ───────── 模型 / 模式 ─────────
    private fun curModel() = session?.optString("model")?.ifEmpty { null } ?: CxPrefs.model(this)
    private fun curEffort() = session?.optString("effort")?.ifEmpty { null } ?: CxPrefs.effort(this)
    private fun curMode() = session?.optString("mode")?.ifEmpty { null } ?: CxPrefs.mode(this)
    private fun modelName(id: String): String { for (i in 0 until models.length()) models.getJSONObject(i).let { if (it.getString("id") == id) return it.optString("name", id) }; return id.ifEmpty { "默认模型" } }
    private fun updateTitle() {
        titleTv.text = session?.optString("title")?.ifEmpty { null } ?: "新对话"
        val ag = curAgent(); input.hint = "给 ${agentName(ag)} 发消息"
        if (ag != "codex") { val mo = cfgValueName("model"); subTv.text = "${projName(session?.optInt("project_id") ?: CxPrefs.project(this))} · ${agentIcon(ag)} ${agentName(ag)}${if (mo.isNotEmpty()) " · $mo" else ""} ▾"; return }
        subTv.text = "${projName(session?.optInt("project_id") ?: CxPrefs.project(this))} · ${modelName(curModel())} ${curEffort()}${if (curMode() == "ask") " · 审批" else ""}${if (session?.optString("backend") == "desktop") " · 🖥同步" else ""} ▾"
    }
    fun pickDefaultModel(done: () -> Unit) = pickModel(done, true)
    /** 非 Codex: configOptions 里某项当前值的显示名 */
    private fun cfgFlat(o: JSONObject): List<JSONObject> { val L = ArrayList<JSONObject>(); val a = o.optJSONArray("options") ?: return L
        for (i in 0 until a.length()) a.getJSONObject(i).let { x -> if (!x.has("value") && x.has("options")) x.getJSONArray("options").let { y -> for (j in 0 until y.length()) L.add(y.getJSONObject(j).put("group", x.optString("name"))) } else L.add(x) }; return L }
    private fun cfgCur(o: JSONObject): String { val id = o.optString("id"); return if (sid == null) CxPrefs.agentCfg(this, curAgent()).optString(id, o.optString("currentValue")) else o.optString("currentValue") }
    private fun cfgValueName(id: String): String { for (i in 0 until cfgOpts.length()) cfgOpts.getJSONObject(i).let { o -> if (o.optString("id") == id) { val v = cfgCur(o); return cfgFlat(o).firstOrNull { it.optString("value") == v }?.optString("name") ?: v } }; return "" }
    private fun loadCfg(then: (() -> Unit)? = null) { val s = sid; val ag = curAgent(); bg {
        val opts = if (s != null) Cx.req(this, "GET", "/sessions/$s/config").let { if (it.first == 200) JSONObject(it.second).optJSONArray("options") else null } else null
        runOnUiThread { cfgOpts = opts?.takeIf { it.length() > 0 } ?: agentObj(ag)?.optJSONArray("config") ?: JSONArray(); updateTitle(); then?.invoke() } } }
    private fun pickConfig() {
        val ag = curAgent()
        if (cfgOpts.length() == 0) { loadCfg { if (cfgOpts.length() > 0) pickConfig() else toast("${agentName(ag)} 没有可调整的选项") }; return }
        val L = (0 until cfgOpts.length()).map { cfgOpts.getJSONObject(it) }.filter { it.optString("type", "select") == "select" }
        AlertDialog.Builder(this).setTitle("${agentIcon(ag)} ${agentName(ag)}").setItems(L.map { it.optString("name") + "：" + (cfgFlat(it).firstOrNull { x -> x.optString("value") == cfgCur(it) }?.optString("name") ?: cfgCur(it)) }.toTypedArray()) { _, w ->
            val o = L[w]; val vals = cfgFlat(o); val cur = cfgCur(o)
            AlertDialog.Builder(this).setTitle(o.optString("name")).setItems(vals.map { (if (it.optString("group").isNotEmpty() && vals.map { v -> v.optString("group") }.distinct().size > 1) it.optString("group") + " · " else "") + it.optString("name") + (if (it.optString("value") == cur) "  ✓" else "") }.toTypedArray()) { _, k ->
                val v = vals[k].optString("value"); val s = sid
                if (s == null) { val c = CxPrefs.agentCfg(this, ag).put(o.optString("id"), v); CxPrefs.setAgentCfg(this, ag, c); updateTitle(); pickConfig() }
                else bg { val r = Cx.req(this, "POST", "/sessions/$s/config", JSONObject().put("configId", o.optString("id")).put("value", v))
                    runOnUiThread { if (r.first == 200) { JSONObject(r.second).optJSONArray("options")?.let { cfgOpts = it }; updateTitle(); pickConfig() } else toast(Cx.err(r)) } }
            }.show()
        }.setNeutralButton("切换 Agent") { _, _ -> if (usable().size > 1) pickAgent() else toast("只有一个可用 Agent") }.show()
    }
    private fun pickModel(after: (() -> Unit)? = null, def: Boolean = false) {
        if (!def && curAgent() != "codex") { pickConfig(); return }
        if (models.length() == 0) { toast("模型列表加载中"); refreshMeta(); return }
        val names = (0 until models.length()).map { models.getJSONObject(it).let { m -> m.optString("name") + (if (m.getString("id") == curModel()) "  ✓" else "") } }
        AlertDialog.Builder(this).setTitle("模型").setItems(names.toTypedArray()) { _, w ->
            val m = models.getJSONObject(w); val effs = m.optJSONArray("efforts") ?: JSONArray()
            val el = (0 until effs.length()).map { effs.getString(it) }
            val cn = mapOf("none" to "无", "low" to "低", "medium" to "中", "high" to "高", "xhigh" to "超高", "max" to "最大", "ultra" to "极限")
            if (el.isEmpty()) { setModel(m.getString("id"), "", def); after?.invoke() }
            else AlertDialog.Builder(this).setTitle("推理强度").setItems(el.map { (cn[it] ?: it) + "  $it" + (if (it == curEffort()) "  ✓" else "") }.toTypedArray()) { _, k -> setModel(m.getString("id"), el[k], def); after?.invoke() }.show()
        }.show()
    }
    private fun setModel(m: String, e: String, def: Boolean = false) {
        CxPrefs.setModel(this, m, e); val s = sid
        if (s != null && !def) { session?.put("model", m)?.put("effort", e); bg { Cx.req(this, "PATCH", "/sessions/$s", JSONObject().put("model", m).put("effort", e)) } }
        updateTitle()
    }
    fun openLink(raw: String) {
        var t = raw.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) { try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t))) } catch (_: Exception) {}; return }
        if (t.startsWith("#") || t.startsWith("mailto:")) return
        t = t.removePrefix("vscode://file").removePrefix("cursor://file").removePrefix("file://")
        if (t.startsWith("codex://")) { val q = Uri.parse(t); t = q.getQueryParameter("path") ?: q.path ?: return }
        t = Uri.decode(t); var line = 0
        Regex("(?:#L(\\d+).*|:(\\d+)(?::\\d+)?)$").find(t)?.let { line = (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() ?: 0; t = t.substring(0, it.range.first) }
        val s = sid ?: return toast("请先打开一个对话")
        act.startActivity(Intent(act, FileActivity::class.java).putExtra("sid", s).putExtra("path", t).putExtra("line", line))
    }
    private fun moreMenu() {
        val ask = curMode() == "ask"
        val items = arrayOf(if (ask) "切换为：项目内自由运行" else "切换为：每条命令审批", if (CxPrefs.think(this)) "隐藏思考过程" else "显示思考过程", "重命名对话", "刷新", "文件")
        AlertDialog.Builder(this).setItems(items) { _, w -> when (w) {
            0 -> { val nm = if (ask) "auto" else "ask"; val s = sid
                if (s != null) { session?.put("mode", nm); bg { Cx.req(this, "PATCH", "/sessions/$s", JSONObject().put("mode", nm)) } } else CxPrefs.setMode(this, nm)
                updateTitle(); toast(if (nm == "ask") "已切换为每条命令审批" else "已切换为自由运行") }
            1 -> { val v = !CxPrefs.think(this); CxPrefs.setThink(this, v); thoughts.forEach { it.visibility = if (v) View.VISIBLE else View.GONE } }
            2 -> sid?.let { s -> prompt("重命名", titleTv.text.toString()) { t -> session?.put("title", t); titleTv.text = t; bg { Cx.req(this, "PATCH", "/sessions/$s", JSONObject().put("title", t)) } } }
            3 -> { openSession(sid); refreshMeta() }
            4 -> sid?.let { act.startActivity(Intent(act, FileActivity::class.java).putExtra("sid", it)) } ?: toast("请先打开一个对话")
        } }.show()
    }

    // ───────── 会话与流 ─────────
    private fun newChat() { drawer.closeDrawers(); openSession(null) }
    private fun clearChat() {
        hideTyping(); h.removeCallbacks(tw); twOn = false; pendingEcho = null
        list.removeAllViews(); tools.clear(); perms.clear(); thoughts.clear(); curMsg = null; curThought = null; lastSeq = 0; setRunning(false)
    }
    // ── 会话切换: 骨架屏 + 内存缓存 + 分页历史 + 后台 Markdown ──
    private class Cached(val meta: JSONObject, val hist: JSONObject)
    private val memCache = object : java.util.LinkedHashMap<String, Cached>(8, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Cached>?) = size > 6 }
    private var histFirst = 0; private var histMore = false; private var olderBtn: TextView? = null; private var loadingOlder = false
    private var skeleton: View? = null
    private fun showSkeleton() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, 0) }
        val anims = ArrayList<android.animation.ObjectAnimator>()
        fun bar(wFrac: Float, hDp: Int, right: Boolean = false, top: Int = 10) {
            val r = LinearLayout(this).apply { gravity = if (right) Gravity.END else Gravity.START }
            val v = View(this).apply { background = UI.round(context, col(R.color.surface2), 10) }
            r.addView(v, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * wFrac).toInt(), dp(hDp)))
            box.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) })
            anims.add(android.animation.ObjectAnimator.ofFloat(v, "alpha", 1f, 0.35f).apply { duration = 750; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE; startDelay = (anims.size * 90).toLong() })
        }
        bar(0.55f, 40, true, 6); bar(0.85f, 14, top = 22); bar(0.78f, 14); bar(0.62f, 14); bar(0.9f, 34, top = 16); bar(0.45f, 34, true, 22); bar(0.8f, 14, top = 22); bar(0.7f, 14)
        val spin = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; alpha = 0f; setPadding(0, dp(28), 0, 0)
            addView(android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleSmall), LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(UI.text(context, "  正在加载对话…", 13f, R.color.muted)) }
        box.addView(spin, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        spin.animate().alpha(1f).setStartDelay(700).setDuration(250).start()  // 慢加载才出现
        anims.forEach { it.start() }
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener { override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { anims.forEach { it.cancel() } } })
        skeleton = box; list.addView(box)
    }
    private fun hideSkeleton() { skeleton?.let { list.removeView(it) }; skeleton = null }
    private fun openSession(id: String?) {
        Md.onLink = { t -> h.post { openLink(t) } }
        gen++; sid = id; session = null; cfgOpts = JSONArray(); CxPrefs.setSession(this, id); visibleSession = id; clearChat(); updateTitle()
        if (id == null && newAgent() != "codex") loadCfg()
        if (id == null) { emptyState(); return }
        val g = gen; val t0 = System.currentTimeMillis()
        val c = memCache[id]
        if (c != null) { session = c.meta; updateTitle(); renderHistory(c.hist, true); android.util.Log.i("leoperf", "cache render=${System.currentTimeMillis() - t0}") }
        else showSkeleton()
        val ctx = this
        bg {
            var m: Pair<Int, String>? = null
            val th = Thread { m = Cx.req(ctx, "GET", "/sessions/$id") }.apply { start() }
            val e = Cx.req(this, "GET", "/sessions/$id/history?turns=6"); th.join()
            val t1 = System.currentTimeMillis()
            if (g != gen) return@bg
            val mm = m!!
            val ho = if (e.first == 200) JSONObject(e.second) else null
            ho?.optJSONArray("events")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { ev -> if (ev.optString("type") == "msg") MdView.warm(ctx, ev.optJSONObject("data")?.optString("text") ?: "") } }
            val t2 = System.currentTimeMillis()
            runOnUiThread {
                if (g != gen) return@runOnUiThread
                if (mm.first == 404) { CxPrefs.setSession(this, null); openSession(null); return@runOnUiThread }
                if (mm.first != 200 || ho == null) { hideSkeleton(); if (c == null) sys("加载失败：" + Cx.err(if (mm.first != 200) mm else e)); return@runOnUiThread }
                val meta = JSONObject(mm.second); session = meta; updateTitle()
                meta.optInt("project_id", -1).let { pj -> if (pj >= 0 && pj != CxPrefs.project(this)) { CxPrefs.setProject(this, pj); buildDrawer() } else if (autoOpened != sid) loadThreads() }; if (curAgent() != "codex") loadCfg()
                val same = c != null && c.hist.optInt("last") == ho.optInt("last")
                memCache[id] = Cached(meta, ho)
                val t3 = System.currentTimeMillis()
                if (!same) { clearChatKeep(); renderHistory(ho, false) } else status(ho)
                android.util.Log.i("leoperf", "v14 net=${t1 - t0} md=${t2 - t1} render=${System.currentTimeMillis() - t3} n=${ho.optJSONArray("events")?.length()} bytes=${e.second.length} cached=${c != null}")
                startStream()
            }
        }
    }
    private fun clearChatKeep() { val p = pendingBanner; clearChat(); pendingBanner = p }
    private var histMode = false
    private fun renderHistory(ho: JSONObject, fromCache: Boolean) {
        hideSkeleton()
        histFirst = ho.optInt("first"); histMore = ho.optBoolean("more")
        if (histMore) addOlderBtn()
        val a = ho.getJSONArray("events"); histMode = true
        for (i in 0 until a.length()) apply(a.getJSONObject(i))
        histMode = false; lastSeq = ho.optInt("last")
        if (!fromCache) status(ho)
        pendingBanner?.let { list.addView(UI.text(this, it, 12.5f, R.color.muted).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(10)) }, if (olderBtn != null) 1 else 0); if (!fromCache) pendingBanner = null }
        renderNow(); scrollEnd(true); scroll.post { scroll.scrollTo(0, maxOf(0, list.height - scroll.height)) }
    }
    private fun addOlderBtn() {
        val b = UI.text(this, "加载更早的消息", 13.5f, R.color.primary).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(12)); setOnClickListener { loadOlder() } }
        olderBtn = b; list.addView(b, 0)
    }
    private fun loadOlder() {
        val s = sid ?: return; if (loadingOlder || !histMore) return
        loadingOlder = true; olderBtn?.text = "加载中…"; val g = gen; val ctx = this
        bg {
            val r = Cx.req(this, "GET", "/sessions/$s/history?turns=6&before=$histFirst")
            val ho = if (r.first == 200) JSONObject(r.second) else null
            ho?.optJSONArray("events")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { ev -> if (ev.optString("type") == "msg") MdView.warm(ctx, ev.optJSONObject("data")?.optString("text") ?: "") } }
            runOnUiThread {
                loadingOlder = false
                if (g != gen) return@runOnUiThread
                if (ho == null) { olderBtn?.text = "加载失败，点此重试"; return@runOnUiThread }
                // 把旧消息渲染进临时容器再插到顶部, 保持当前阅读位置
                val keep = list; val tmp = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val sv = arrayOf<Any?>(curMsg, curMsgSb, curThought, curThoughtSb, shown)
                closeBlocks(); list = tmp; histMode = true
                val a = ho.getJSONArray("events"); for (i in 0 until a.length()) apply(a.getJSONObject(i))
                closeBlocks(); histMode = false; list = keep
                curMsg = sv[0] as MdView?; curMsgSb = sv[1] as StringBuilder; curThought = sv[2] as TextView?; curThoughtSb = sv[3] as StringBuilder; shown = sv[4] as Int
                olderBtn?.let { list.removeView(it) }; olderBtn = null
                histFirst = ho.optInt("first"); histMore = ho.optBoolean("more")
                val before = list.height; val views = (0 until tmp.childCount).map { tmp.getChildAt(it) }; tmp.removeAllViews()
                for ((k, v) in views.withIndex()) list.addView(v, k)
                if (histMore) addOlderBtn()
                list.post { scroll.scrollBy(0, list.height - before) }
            }
        }
    }
    private fun emptyState() {
        list.addView(UI.text(this, "有什么可以帮你？", 24f, R.color.text, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(140), 0, dp(6)) })
        list.addView(UI.text(this, "在 ${projName(CxPrefs.project(this))} 里工作 · 所有操作在你的 Mac 上执行", 13.5f, R.color.muted).apply { gravity = Gravity.CENTER })
    }
    private fun status(o: JSONObject) {
        setRunning(o.optBoolean("running"))
        val p = o.optJSONArray("pending") ?: JSONArray(); val live = (0 until p.length()).map { p.getString(it) }.toSet()
        perms.keys.retainAll(live); showNextPerm()
    }
    private fun startStream() {
        val s = sid ?: return; val g = ++gen
        Thread {
            var fails = 0
            while (g == gen) {
                try {
                    if (fails < 3) {
                        val c = URL(CxPrefs.base(this) + "/sessions/$s/stream?since=$lastSeq").openConnection() as HttpURLConnection
                        c.connectTimeout = 10000; c.readTimeout = 45000; c.setRequestProperty("X-Token", CxPrefs.token(this)); c.setRequestProperty("Accept", "text/event-stream")
                        if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                        fails = 0
                        c.inputStream.bufferedReader().use { r ->
                            var ev = ""; val data = StringBuilder()
                            while (g == gen) {
                                val line = r.readLine() ?: break
                                when {
                                    line.startsWith("event:") -> ev = line.substring(6).trim()
                                    line.startsWith("data:") -> data.append(line.substring(5).trim())
                                    line.isEmpty() && data.isNotEmpty() -> { val o = JSONObject(data.toString()); val e = ev; data.setLength(0)
                                        runOnUiThread { if (g == gen) { if (e == "status") status(o) else { live = true; apply(o); live = false; scheduleRender() } } } }
                                }
                            }
                        }
                        c.disconnect()
                    } else {
                        val r = Cx.req(this, "GET", "/sessions/$s/events?since=$lastSeq&wait=20", readTimeout = 35000)
                        if (r.first != 200) throw Exception("HTTP ${r.first}")
                        val o = JSONObject(r.second); val a = o.getJSONArray("events")
                        runOnUiThread { if (g == gen) { live = true; for (i in 0 until a.length()) apply(a.getJSONObject(i)); live = false; status(o); scheduleRender() } }
                        if (++fails > 40) fails = 0  // 偶尔回到 SSE
                    }
                } catch (e: Exception) { fails++; try { Thread.sleep(if (fails > 5) 5000 else 1500) } catch (_: Exception) {} }
            }
        }.start()
    }

    private fun apply(ev: JSONObject) {
        val seq = ev.optInt("seq"); if (!histMode) { if (seq <= lastSeq) return; lastSeq = seq }
        val d = ev.optJSONObject("data") ?: JSONObject()
        if (ev.has("full_seq")) d.put("_fs", ev.optInt("full_seq"))
        when (ev.optString("type")) {
            "user" -> { closeBlocks(); if (pendingEcho != null && pendingEcho == d.optString("text")) pendingEcho = null else userBubble((if (d.optString("from") == "desktop") "🖥 " else "") + d.optString("text"), d.optInt("images")) }
            "msg" -> { curThought = null; hideTyping()
                if (curMsg == null) { curMsgSb = StringBuilder(); shown = 0; curMsg = MdView(this).apply { background = UI.card(context, 20); setPadding(dp(16), dp(12), dp(16), dp(12)); UI.lift(this) }
                    list.addView(curMsg, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }) }
                curMsgSb.append(d.optString("text"))
                if (live) { if (!twOn) { twOn = true; h.post(tw) } } else renderPending = true }
            "thought" -> { if (CxPrefs.think(this)) hideTyping(); if (curThought == null) { curThoughtSb = StringBuilder(); curThought = UI.text(this, "", 13.5f, R.color.muted).apply {
                    setPadding(dp(12), dp(6), dp(8), dp(6)); background = UI.round(context, col(R.color.bg), 10); visibility = if (CxPrefs.think(context)) View.VISIBLE else View.GONE }
                    thoughts.add(curThought!!); list.addView(curThought, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }) }
                curThoughtSb.append(d.optString("text")); curThought!!.text = "💭 " + curThoughtSb }
            "tool" -> { hideTyping(); finishMsg(); curMsg = null; curThought = null; toolCard(d) }
            "plan" -> { closeBlocks(); val a = d.optJSONArray("entries") ?: JSONArray()
                val sb = StringBuilder("📋 计划\n"); for (i in 0 until a.length()) a.getJSONObject(i).let { sb.append(when (it.optString("status")) { "completed" -> "✓ "; "in_progress" -> "▶ "; else -> "○ " }).append(it.optString("content")).append('\n') }
                list.addView(UI.text(this, sb.trim(), 14f, R.color.text).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); background = UI.round(context, col(R.color.bg), 10) }, UI.lp(this, 0, 6, 0, 0)) }
            "ask" -> { closeBlocks(); hideTyping(); askCard(d); if (live) scrollEnd(true) }
            "ask_done" -> askDone(d)
            "perm" -> { perms[d.optString("rid")] = d; sys("⚠️ 请求审批：" + d.optString("title")) }
            "perm_done" -> { perms.remove(d.optString("rid")); sys("审批：" + d.optString("name")) }
            "turn" -> { if (d.optString("state") == "running") { setRunning(true); if (live && curMsg == null) showTyping() } else { hideTyping(); closeBlocks(); setRunning(false)
                for ((_, p) in tools.toList()) if (p.second.optString("status").let { it != "completed" && it != "failed" }) { p.second.put("status", "stopped"); toolCard(p.second) }
                when (d.optString("stopReason")) { "cancelled" -> sys("已停止"); "error" -> {}; else -> {} } } }
            "title" -> { session?.put("title", d.optString("title")); updateTitle() }
            "error" -> hideTyping().let { sys("❌ " + d.optString("msg"), R.color.strong) }
        }
    }
    private fun finishMsg() { val m = curMsg ?: return; h.removeCallbacks(tw); twOn = false; shown = curMsgSb.length; m.setMd(curMsgSb.toString()) }
    private fun closeBlocks() { finishMsg(); curMsg = null; curThought = null }
    private fun scheduleRender() { if (!renderPending) { showNextPerm(); if (!twOn) stick(); return }; if (!h.hasCallbacks(renderR)) h.postDelayed(renderR, 60) }
    private val renderR = Runnable { renderNow(); scrollEnd(false) }
    private fun renderNow() { renderPending = false; finishMsg(); showNextPerm() }
    private fun scrollEnd(force: Boolean) { if (force) atBottom = true; if (atBottom) scroll.post { scroll.smoothScrollTo(0, maxOf(0, list.height - scroll.height)) } }
    private fun sys(s: String, color: Int = R.color.muted) {
        closeBlocks(); list.addView(UI.text(this, s, 13f, color).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)) })
    }
    private fun userBubble(t: String, imgs: Int) {
        val tv = UI.text(this, Md.linkify(this, SpannableStringBuilder((if (imgs > 0) "🖼 ×$imgs\n" else "") + t), color = 0xFFFFFFFF.toInt()), 16f, android.R.color.white).apply { setTextIsSelectable(true); Md.touch(this); highlightColor = 0x55FFFFFF
            background = UI.grad(context, 18); setPadding(dp(14), dp(10), dp(14), dp(10)); maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt() }
        val row = LinearLayout(this).apply { gravity = Gravity.END; addView(tv) }
        list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
    }
    private fun toolCard(d: JSONObject) {
        val id = d.optString("id"); val ex = tools[id]
        val card = ex?.first ?: LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UI.card(context) }.also {
            list.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }) }
        val open = card.tag == true
        tools[id] = Pair(card, d); card.removeAllViews()
        val st = d.optString("status"); val icon = when { st == "stopped" -> "■"; st == "failed" || (d.has("exit") && d.optInt("exit") != 0) -> "✗"; st == "completed" -> "✓"; else -> "⟳" }
        val kind = when (d.optString("kind")) { "read" -> "读取"; "edit" -> "编辑"; "execute" -> "运行"; "search" -> "搜索"; "fetch" -> "联网"; "delete" -> "删除"; "move" -> "移动"; else -> "工具" }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { card.tag = !(card.tag == true); val cur = tools[id]!!.second; toolCard(cur)
                if (card.tag == true && cur.optBoolean("more") && cur.has("_fs")) { val s0 = sid; bg { val r = Cx.req(this@CodexPane, "GET", "/sessions/$s0/tool/${cur.optInt("_fs")}")
                    if (r.first == 200) runOnUiThread { if (s0 == sid && tools[id]?.first === card) { val full = JSONObject(r.second); if (full.length() > 0) toolCard(full) } } } } } }
        head.addView(UI.text(this, icon, 13f, if (icon == "✗") R.color.strong else if (icon == "✓") R.color.down else R.color.primary, true), LinearLayout.LayoutParams(dp(18), WRAP_CONTENT))
        head.addView(UI.text(this, "$kind  " + d.optString("title").lineSequence().first(), 13f, R.color.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(UI.text(this, if (open) "⌃" else "⌄", 14f, R.color.muted))
        card.addView(head)
        val fl = LinkedHashSet<String>(); d.optJSONArray("diffs")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).optString("path").let { if (it.isNotBlank()) fl.add(it) } }
        d.optJSONArray("files")?.let { a -> for (i in 0 until a.length()) a.optString(i).let { if (it.isNotBlank()) fl.add(it) } }
        if (fl.isNotEmpty()) card.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(dp(30), 0, dp(12), dp(8))
            addView(LinearLayout(context).apply { for (f in fl.take(12)) addView(UI.text(context, "📄 " + f.substringAfterLast('/'), 12.5f, R.color.primary).apply {
                setPadding(dp(10), dp(4), dp(10), dp(4)); background = UI.round(context, col(R.color.primaryC), 12); setOnClickListener { openLink(f) } }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(6) }) }) })
        if (open) {
            val sb = SpannableStringBuilder()
            if (d.has("cmd")) sb.append("$ ").append(d.optString("cmd")).append("\n\n") else if (d.optString("title").contains("\n")) sb.append(d.optString("title")).append("\n\n")
            d.optJSONArray("diffs")?.let { a -> for (i in 0 until a.length()) diffSpan(sb, a.getJSONObject(i)) }
            if (d.has("output")) sb.append(d.optString("output"))
            if (d.has("exit")) sb.append("\n[exit ${d.optInt("exit")}]")
            val tv = UI.text(this, Md.linkify(this, SpannableStringBuilder(sb.trimEnd())), 12f, R.color.text).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(12), 0, dp(12), dp(10)); Md.touch(this) }
            card.addView(HorizontalScrollView(this).apply { addView(tv) })
        }
    }
    private fun diffSpan(sb: SpannableStringBuilder, o: JSONObject) {
        sb.append("✎ ").append(o.optString("path")).append('\n')
        val old = o.optString("old").lines(); val new = o.optString("new").lines()
        val red = col(R.color.strong); val green = col(R.color.down)
        val oldSet = old.toHashSet(); val newSet = new.toHashSet()
        for (l in old) if (l !in newSet) line(sb, "- $l", red)
        for (l in new) if (l !in oldSet) line(sb, "+ $l", green)
        sb.append('\n')
    }
    private fun line(sb: SpannableStringBuilder, s: String, c: Int) { val st = sb.length; sb.append(s).append('\n'); sb.setSpan(ForegroundColorSpan(c), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    // ───────── 提问 / 输入请求 ─────────
    private val askCards = HashMap<String, LinearLayout>()
    private fun askCard(d: JSONObject) {
        val rid = d.optString("rid")
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = UI.round(context, col(R.color.primaryC), 14) }
        askCards[rid] = card
        list.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        card.addView(UI.text(this, "❓ " + d.optString("title", "需要你的输入"), 13f, R.color.primary, true))
        if (d.optString("text").isNotEmpty()) card.addView(UI.text(this, d.optString("text"), 13.5f, R.color.text).apply { setTextIsSelectable(true); setPadding(0, dp(6), 0, 0) })
        val qs = d.optJSONArray("questions") ?: JSONArray()
        val sel = HashMap<String, MutableList<String>>(); val texts = HashMap<String, EditText>()
        for (i in 0 until qs.length()) {
            val q = qs.getJSONObject(i); val qid = q.optString("id"); val multi = q.optBoolean("multi"); sel[qid] = mutableListOf()
            card.addView(UI.text(this, q.optString("title"), 15.5f, R.color.text, true).apply { setPadding(0, dp(10), 0, dp(4)) })
            val opts = q.optJSONArray("options") ?: JSONArray(); val btns = ArrayList<TextView>()
            for (j in 0 until opts.length()) {
                val o = opts.getJSONObject(j); val label = o.optString("label")
                val b = UI.text(this, label + (if (o.optString("desc").isNotEmpty()) "\n" + o.optString("desc") else ""), 14f, R.color.text).apply {
                    setPadding(dp(12), dp(9), dp(12), dp(9)); background = UI.round(context, col(R.color.surface), 10) }
                b.setOnClickListener {
                    val l = sel[qid]!!
                    if (multi) { if (l.contains(label)) l.remove(label) else l.add(label) } else { l.clear(); l.add(label) }
                    for ((k, bb) in btns.withIndex()) { val on = l.contains(opts.getJSONObject(k).optString("label"))
                        bb.background = UI.round(this, col(if (on) R.color.primary else R.color.surface), 10); bb.setTextColor(if (on) 0xFFFFFFFF.toInt() else col(R.color.text)) }
                }
                btns.add(b); card.addView(b, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
            }
            if (q.optBoolean("other", true) || opts.length() == 0) {
                val e = EditText(this).apply { hint = if (opts.length() > 0) "其他 / 补充说明（可选）" else "输入回答"; textSize = 14f
                    if (q.optBoolean("secret")) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                texts[qid] = e; card.addView(e, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
            }
        }
        if (d.optString("note").isNotEmpty()) card.addView(UI.text(this, d.optString("note"), 12f, R.color.muted).apply { setPadding(0, dp(6), 0, 0) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        fun submit(skip: Boolean) {
            val ans = JSONObject()
            for ((qid, l) in sel) { val all = l.toMutableList(); texts[qid]?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { all.add(it) }; if (all.isNotEmpty()) ans.put(qid, JSONArray(all)) }
            if (!skip && ans.length() == 0) { toast("请先选择或输入"); return }
            row.alpha = 0.4f; for (k in 0 until row.childCount) row.getChildAt(k).isEnabled = false
            bg { val r = Cx.req(this, "POST", "/ask/$rid", JSONObject().put("answers", ans).put("skip", skip))
                runOnUiThread { if (r.first != 200) { toast(Cx.err(r)); row.alpha = 1f; for (k in 0 until row.childCount) row.getChildAt(k).isEnabled = true } } }
        }
        row.addView(UI.filledButton(this, "提交") { submit(false) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f))
        row.addView(UI.filledButton(this, "跳过", R.color.surface2, R.color.text) { submit(true) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        card.addView(row)
    }
    private fun askDone(d: JSONObject) {
        val card = askCards.remove(d.optString("rid")) ?: return
        val title = (card.getChildAt(0) as? TextView)?.text ?: ""
        val q = (0 until card.childCount).map { card.getChildAt(it) }.filterIsInstance<TextView>().getOrNull(1)?.text ?: ""
        card.removeAllViews(); card.background = UI.round(this, col(R.color.bg), 14)
        card.addView(UI.text(this, title.toString().replace("❓", "✓"), 13f, R.color.muted, true))
        if (q.isNotEmpty()) card.addView(UI.text(this, q, 14f, R.color.muted).apply { setPadding(0, dp(4), 0, 0) })
        card.addView(UI.text(this, (if (d.optString("by") == "desktop") "已在桌面端回答：" else "已回答：") + d.optString("summary"), 14.5f, R.color.text).apply { setPadding(0, dp(4), 0, 0) })
    }

    // ───────── 审批 ─────────
    private fun showNextPerm() {
        if (permShown != null && perms.containsKey(permShown)) return
        val (rid, d) = perms.entries.firstOrNull()?.toPair() ?: return
        permShown = rid
        val dlg = BottomSheetDialog(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(24)); setBackgroundColor(col(R.color.surface)) }
        box.addView(UI.text(this, "Codex 请求执行", 13f, R.color.muted))
        box.addView(UI.text(this, d.optString("title"), 17f, R.color.text, true).apply { setPadding(0, dp(4), 0, dp(8)) })
        if (d.optString("detail").isNotEmpty()) box.addView(ScrollView(this).apply { addView(UI.text(context, d.optString("detail"), 12.5f).apply { typeface = Typeface.MONOSPACE
            background = UI.round(context, col(R.color.bg), 10); setPadding(dp(10), dp(8), dp(10), dp(8)) }) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
        val opts = d.optJSONArray("options") ?: JSONArray()
        val order = listOf("allow_once", "allow_always", "reject_once", "reject_always")
        val sorted = (0 until opts.length()).map { opts.getJSONObject(it) }.sortedBy { order.indexOf(it.optString("kind")).let { i -> if (i < 0) 9 else i } }
        for (o in sorted) {
            val k = o.optString("kind"); val label = when (k) { "allow_once" -> "允许一次"; "allow_always" -> "始终允许"; "reject_once" -> "拒绝"; "reject_always" -> "始终拒绝"; else -> o.optString("name") }
            box.addView(UI.filledButton(this, label, if (k.startsWith("allow")) (if (k == "allow_once") R.color.primary else R.color.surface2) else R.color.surface2,
                if (k == "allow_once") android.R.color.white else if (k.startsWith("reject")) R.color.strong else R.color.text) {
                answer(rid, o.optString("id")); dlg.dismiss() }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        dlg.setContentView(box); dlg.setOnDismissListener { if (permShown == rid) permShown = null }
        dlg.setCancelable(true); dlg.show()
    }
    private fun answer(rid: String, opt: String) { perms.remove(rid); permShown = null
        bg { val r = Cx.req(this, "POST", "/perm/$rid", JSONObject().put("option", opt)); if (r.first != 200) runOnUiThread { toast(Cx.err(r)) } } }

    // ───────── 发送 / 停止 / 图片 ─────────
    private fun send() {
        val t = input.text.toString().trim(); if (t.isEmpty() && images.isEmpty()) return
        val body = JSONObject().put("text", t)
        if (images.isNotEmpty()) body.put("images", JSONArray().apply { images.forEach { put(JSONObject().put("mime", it.first).put("data", it.second)) } })
        if (sid == null) list.removeAllViews()
        closeBlocks(); userBubble(t, images.size); pendingEcho = t; showTyping(); scrollEnd(true)
        input.setText(""); images.clear(); thumbs.removeAllViews(); thumbs.visibility = View.GONE; setRunning(true)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        val pid = CxPrefs.project(this)
        bg {
            var s = sid
            if (s == null) {
                val r = Cx.req(this, "POST", "/sessions", JSONObject().put("project", pid).put("model", CxPrefs.model(this)).put("effort", CxPrefs.effort(this)).put("mode", CxPrefs.mode(this)).put("agent", newAgent()).put("config", CxPrefs.agentCfg(this, newAgent())))
                if (r.first != 200) { runOnUiThread { setRunning(false); hideTyping(); pendingEcho = null; input.setText(t); toast("创建对话失败：" + Cx.err(r)) }; return@bg }
                val o = JSONObject(r.second); s = o.getString("id")
                runOnUiThread { sid = s; session = o; CxPrefs.setSession(this, s); visibleSession = s; updateTitle(); startStream() }
            }
            val r = Cx.req(this, "POST", "/sessions/$s/prompt", body, 60000)
            runOnUiThread { if (r.first != 200) { setRunning(false); hideTyping(); pendingEcho = null; toast("发送失败：" + Cx.err(r)) } else { scrollEnd(true); buildDrawer() } }
        }
    }
    private fun stop() { val s = sid ?: return; bg { Cx.req(this, "POST", "/sessions/$s/cancel", JSONObject()) }; toast("正在停止…") }
    private fun pickImage() = act.startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 7)
    fun onActivityResult(rq: Int, rc: Int, data: Intent?) {
        val u = data?.data ?: return; if (rq != 7 || rc != android.app.Activity.RESULT_OK) return
        bg {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, o) }
                var ss = 1; while (maxOf(o.outWidth, o.outHeight) / ss > 3200) ss *= 2
                var bm = contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = ss }) }!!
                val k = 1600f / maxOf(bm.width, bm.height); if (k < 1) bm = Bitmap.createScaledBitmap(bm, (bm.width * k).toInt(), (bm.height * k).toInt(), true)
                val bo = ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.JPEG, 82, bo)
                val b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                runOnUiThread { images.add(Pair("image/jpeg", b64)); thumbs.visibility = View.VISIBLE
                    thumbs.addView(ImageView(this).apply { setImageBitmap(bm); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = UI.round(context, col(R.color.surface2), 8)
                        setOnClickListener { val i = thumbs.indexOfChild(this); if (i >= 0) { images.removeAt(i); thumbs.removeView(this) }; if (images.isEmpty()) thumbs.visibility = View.GONE } },
                        LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(6) }) }
            } catch (e: Exception) { runOnUiThread { toast("图片读取失败") } }
        }
    }

    // ───────── Diff ─────────
    private fun showDiff() {
        val s = sid ?: run { toast("先开始一个对话"); return }
        val dlg = Dialog(this, android.R.style.Theme_DeviceDefault_DayNight)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.surface)) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(12), dp(6)) }
        bar.addView(iconBtn("✕") { dlg.dismiss() })
        val tt = UI.text(this, "改动 (git diff)", 17f, R.color.text, true); bar.addView(tt, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        box.addView(bar)
        val body = UI.text(this, "加载中…", 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(12), dp(8), dp(12), dp(40)) }
        box.addView(ScrollView(this).apply { addView(HorizontalScrollView(context).apply { addView(body) }) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        dlg.setContentView(box); dlg.show()
        bg {
            val r = Cx.req(this, "GET", "/sessions/$s/diff", readTimeout = 30000)
            runOnUiThread {
                if (r.first != 200) { body.text = Cx.err(r); return@runOnUiThread }
                val o = JSONObject(r.second)
                if (!o.optBoolean("git")) { body.text = "该项目不是 git 仓库，无法显示 diff。"; return@runOnUiThread }
                tt.text = "改动 · " + o.optString("branch")
                val diff = o.optString("diff"); if (diff.isBlank()) { body.text = "没有未提交的改动"; return@runOnUiThread }
                val sb = SpannableStringBuilder(); sb.append(o.optString("stat")).append('\n')
                val red = col(R.color.strong); val green = col(R.color.down); val blue = col(R.color.primary); val hb = col(R.color.surface2)
                for (l in diff.lines().take(6000)) {
                    val st = sb.length; sb.append(l).append('\n')
                    when {
                        l.startsWith("diff --git") -> { sb.setSpan(BackgroundColorSpan(hb), st, sb.length, 0); sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, sb.length, 0) }
                        l.startsWith("+++") || l.startsWith("---") -> sb.setSpan(ForegroundColorSpan(col(R.color.muted)), st, sb.length, 0)
                        l.startsWith("@@") -> sb.setSpan(ForegroundColorSpan(blue), st, sb.length, 0)
                        l.startsWith("+") -> sb.setSpan(ForegroundColorSpan(green), st, sb.length, 0)
                        l.startsWith("-") -> sb.setSpan(ForegroundColorSpan(red), st, sb.length, 0)
                    }
                }
                body.text = sb
            }
        }
    }
}
