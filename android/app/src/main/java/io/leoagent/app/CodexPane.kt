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
import okhttp3.Call
import okhttp3.Request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Agent 对话与左侧导航抽屉（由 MainActivity 托管）。 */
class CodexPane(val act: AppCompatActivity, private val openSettings: () -> Unit) : android.content.ContextWrapper(act) {
    companion object { @Volatile var visibleSession: String? = null }
    private lateinit var drawer: DrawerLayout
    private lateinit var side: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var conversation: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var projectTv: TextView
    private lateinit var composerAgent: TextView
    private lateinit var approvalHost: LinearLayout
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
    @Volatile private var lastSeq = 0L
    private var running = false
    @Volatile private var gen = 0
    private val tools = HashMap<String, Pair<LinearLayout, JSONObject>>()
    private val perms = LinkedHashMap<String, JSONObject>()
    private var permShown: String? = null
    private var permDialog: BottomSheetDialog? = null
    private val responseInFlight = HashSet<String>()
    @Volatile private var streamCall: Call? = null
    private var streamThread: Thread? = null
    private var streamOwner = -1
    private var renderedEpoch = ""
    private var curMsg: MdView? = null; private var curMsgSb = StringBuilder()
    private var curThought: TextView? = null; private var curThoughtSb = StringBuilder()
    private val thoughts = ArrayList<View>()
    private var renderPending = false
    private var live = false
    private var pendingEcho: String? = null
    private fun stick() { if (atBottom) scroll.post { scroll.scrollTo(0, maxOf(0, conversation.height - scroll.height)) } }
    private val images = ArrayList<Pair<String, String>>()
    private var atBottom = true
    private var unread = 0
    private var taskId: String? = null
    private var taskState: String = "idle"
    private var deliveryUnknown = false
    private var connectionMessage = ""
    private lateinit var connectionTv: TextView
    private lateinit var taskStatusTv: TextView
    private lateinit var fab: FrameLayout
    private lateinit var fabBadge: TextView
    private lateinit var pip: ScreenPip
    private fun updateFab() {
        if (!::fab.isInitialized) return
        if (atBottom) unread = 0
        val show = !atBottom && conversation.height > scroll.height * 2
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
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START; minimumHeight = dp(48); isFocusable = true; isFocusableInTouchMode = false
            foreground = UI.focusForeground(context, DS.R_SM)
            background = UI.ripple(context, col(R.color.surface), 10); setPadding(dp(8), dp(2), dp(8), dp(2)); setOnClickListener { pickModel() } }
        titleTv = UI.text(this, "新对话", 16f, R.color.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.START }
        subTv = UI.text(this, "", 12.5f, R.color.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.START }
        tcol.addView(titleTv); tcol.addView(subTv)
        bar.addView(tcol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(iconBtn("▣") { pip.toggle() }.apply { textSize = 20f; contentDescription = "桌面画中画" })
        bar.addView(iconBtn("±") { showDiff() })
        bar.addView(iconBtn("⋯") { moreMenu() })
        main.addView(bar)
        projectTv = UI.text(this, "选择项目", 13f, R.color.primary).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            val folder = getDrawable(R.drawable.ms_folder)!!.mutate().apply { setTint(col(R.color.muted)); setBounds(0, 0, dp(18), dp(18)) }
            setCompoundDrawables(folder, null, null, null); compoundDrawablePadding = dp(8)
            background = UI.ripple(context, col(R.color.surface2), 0)
            foreground = UI.focusForeground(context, DS.R_SM)
            isFocusable = true; isFocusableInTouchMode = false
            contentDescription = "选择项目"; setOnClickListener { pickProject() }
        }
        main.addView(projectTv)
        connectionTv = UI.text(this, "", 12.5f, R.color.muted).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(4), dp(12), dp(4)); visibility = View.GONE
        }
        taskStatusTv = UI.text(this, "", 13f, R.color.primary).apply {
            gravity = Gravity.START; setPadding(dp(20), dp(4), dp(20), dp(20)); visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener { if (taskState == "unknown") reviewUnknownTask() else if (deliveryUnknown) reviewPending() }
        }
        main.addView(connectionTv)
        main.addView(View(this).apply { setBackgroundColor(col(R.color.border)) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
        conversation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(list); addView(taskStatusTv) }
        scroll = ScrollView(this).apply { addView(conversation); isFillViewport = true
            viewTreeObserver.addOnScrollChangedListener { atBottom = !canScrollVertically(1) || (conversation.height - (scrollY + height) < dp(80)); updateFab(); if (scrollY < dp(40) && histMore && !loadingOlder && list.height > height) loadOlder() } }
        list.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(p: View?, c: View?) { if (!atBottom && c !== skeleton) { unread++; updateFab() } }
            override fun onChildViewRemoved(p: View?, c: View?) {} })
        val chatFrame = FrameLayout(this)
        chatFrame.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        fab = FrameLayout(this).apply { visibility = View.GONE; elevation = dp(6).toFloat(); background = UI.round(context, col(R.color.surface), 22)
            addView(TextView(context).apply { text = "↓"; textSize = 20f; typeface = UI.BOLD; gravity = Gravity.CENTER; setTextColor(col(R.color.primary)) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            setOnClickListener { unread = 0; atBottom = true; scroll.smoothScrollTo(0, maxOf(0, conversation.height - scroll.height)); updateFab() }; contentDescription = "回到最新" }
        fabBadge = TextView(this).apply { textSize = 10f; setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER; background = UI.round(context, col(R.color.primaryFill), 9); setPadding(dp(4), 0, dp(4), 0); visibility = View.GONE; elevation = dp(7).toFloat() }
        chatFrame.addView(fab, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(16)) })
        chatFrame.addView(fabBadge, FrameLayout.LayoutParams(WRAP_CONTENT, dp(18), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(12), dp(46)) })
        pip = ScreenPip(chatFrame)
        main.addView(chatFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        approvalHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        main.addView(approvalHost, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // 文本独占一行，附件、Agent、语音和发送在下方，窄屏也保留完整输入宽度。
        thumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(6), dp(14), 0); visibility = View.GONE }
        main.addView(thumbs)
        val comp = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(6)); background = UI.outline(context, R.color.composerBg, 16) }
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "给 Codex 发消息"; textSize = 16f; setTextColor(col(R.color.text)); setHintTextColor(col(R.color.muted)); maxLines = 4; minHeight = dp(48)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = null; setPadding(dp(8), dp(12), dp(8), dp(12)); contentDescription = "任务描述"
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        comp.addView(inputRow)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(circle("＋", R.color.composerBg, R.color.muted) { pickImage() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        composerAgent = UI.text(this, "Codex ▾", 13f, R.color.muted).apply {
            gravity = Gravity.CENTER; minimumHeight = dp(48); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0); isFocusable = true; isFocusableInTouchMode = false
            background = UI.ripple(context, col(R.color.composerBg), 10); foreground = UI.focusForeground(context, 10)
            contentDescription = "选择 Agent 或模型"; setOnClickListener { if (sid == null && usable().size > 1) pickAgent() else pickModel() }
        }
        controls.addView(composerAgent, LinearLayout.LayoutParams(0, dp(48), 1f))
        VoiceInput(act, input, inputRow, controls) { if (!running) send() }
        sendBtn = circle("↑", R.color.primary, android.R.color.white) { if (running) stop() else send() }
        controls.addView(sendBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        comp.addView(controls)
        main.addView(comp, UI.lp(this, 14, 12, 14, 12))
        drawer.addView(main, DrawerLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UI.pageBg(context) }
        drawer.addView(side, DrawerLayout.LayoutParams(minOf(dp(320), resources.displayMetrics.widthPixels - dp(32)), MATCH_PARENT, Gravity.START))
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() { override fun onDrawerOpened(v: View) { loadThreads() } })
        root = drawer
        sid = initSid ?: CxPrefs.session(this)
        buildDrawer(); refreshMeta(); openSession(sid)
        return drawer
    }
    fun back(): Boolean { if (drawer.isDrawerOpen(GravityCompat.START)) { drawer.closeDrawers(); return true }; return false }
    fun open(id: String) { drawer.closeDrawers(); if (id != sid) openSession(id) }
    private var paneVisible = false
    private var stopped = true
    private var paneConnection = Cx.connection(this)
    fun onShow() {
        paneVisible = true
        val currentConnection = Cx.connection(this)
        val changed = paneConnection != currentConnection
        if (changed) {
            paneConnection = currentConnection; gen++; streamCall?.cancel()
            memCache.clear(); responseInFlight.clear(); deliveryUnknown = false
            stopped = false; refreshMeta(); openSession(CxPrefs.session(this))
        }
        Http.warm(CxPrefs.base(this) + "/agents", CxPrefs.token(this)); visibleSession = sid; pip.resume()
        if (stopped) { stopped = false; if (sid != null) { if (session == null) openSession(sid) else startStream() } }
    }
    fun onHide() { paneVisible = false; pip.pause(); visibleSession = null; gen++; streamCall?.cancel(); stopped = true; if (running || perms.isNotEmpty()) CodexService.start(this) }

    // ───────── 组件 ─────────
    private val GLYPH = mapOf("☰" to R.drawable.ms_menu, "±" to R.drawable.ms_difference, "⋯" to R.drawable.ms_more_horiz, "✕" to R.drawable.ms_close,
        "＋" to R.drawable.ms_add, "↑" to R.drawable.ms_arrow_upward, "■" to R.drawable.ms_stop, "▣" to R.drawable.ms_fullscreen)
    private val GLYPH_LABEL = mapOf(
        "☰" to "打开对话列表",
        "±" to "查看文件差异",
        "⋯" to "更多操作",
        "✕" to "关闭",
        "＋" to "添加图片",
        "↑" to "发送消息",
        "■" to "停止任务",
    )
    private fun iconBtn(s: String, f: () -> Unit) = TextView(this).apply {
        text = s; textSize = 22f; gravity = Gravity.CENTER; setTextColor(col(R.color.text)); layoutParams = LinearLayout.LayoutParams(dp(DS.TOUCH), dp(DS.TOUCH))
        GLYPH[s]?.let { UI.glyph(context, this, it, col(R.color.text), android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22808080), null, UI.round(context, android.graphics.Color.WHITE, 22)), 24) } ?: run { background = UI.ripple(context, android.graphics.Color.TRANSPARENT, 22) }
        contentDescription = GLYPH_LABEL[s] ?: s
        isFocusable = true; isFocusableInTouchMode = false
        foreground = UI.focusForeground(context, DS.R_SM)
        setOnClickListener { f() }
    }
    private fun circle(s: String, bgc: Int, fg: Int, f: () -> Unit) = TextView(this).apply {
        text = s; textSize = 20f; typeface = UI.BOLD; gravity = Gravity.CENTER; setTextColor(col(fg)); tag = intArrayOf(bgc, fg)
        GLYPH[s]?.let { UI.glyph(context, this, it, col(fg), if (bgc == R.color.primary) UI.grad(context, 12) else UI.round(context, col(bgc), 12), 22) } ?: run { background = UI.ripple(context, col(bgc), 20) }
        contentDescription = GLYPH_LABEL[s] ?: s
        isFocusable = true; isFocusableInTouchMode = false
        foreground = UI.focusForeground(context, DS.R_SM)
        setOnClickListener { f() }
    }
    private fun setRunning(r: Boolean) {
        running = r
        UI.glyph(this, sendBtn, if (r) R.drawable.ms_stop else R.drawable.ms_arrow_upward, 0xFFFFFFFF.toInt(), UI.grad(this, 12), if (r) 20 else 22)
        sendBtn.contentDescription = if (r) "停止任务" else "发送消息"
    }
    private fun updateTaskStatus() {
        if (!::taskStatusTv.isInitialized) return
        taskStatusTv.text = if (deliveryUnknown) "提交结果尚未确认 · 点此核对"
        else if (responseInFlight.isNotEmpty()) "已提交回复，等待电脑回执"
        else when (taskState) {
            "submitting" -> "正在发送…"
            "queued" -> "任务已接受，正在准备"
            "running" -> "Agent 正在执行"
            "waiting_approval" -> if (::approvalHost.isInitialized && approvalHost.isShown) "" else "等待你的审批"
            "waiting_input" -> "等待你的回答"
            "cancelling" -> "正在停止，等待 Agent 确认"
            "recovering" -> "电脑正在核对任务状态"
            "unknown" -> "本轮结果未知，项目保持占用 · 点此核对"
            "interrupted" -> "本轮已人工核对并结束，已有改动仍保留"
            "failed" -> "本轮执行失败"
            "cancelled" -> "本轮已停止"
            else -> ""
        }
        taskStatusTv.visibility = if (taskStatusTv.text.isEmpty()) View.GONE else View.VISIBLE
        stick()
    }
    private fun setConnection(message: String) {
        connectionMessage = message
        connectionTv.text = message
        connectionTv.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }

    // ───────── 元数据 / 抽屉 ─────────
    private fun refreshMeta() {
        if (!Prefs.configured(this)) return
        val conn = Cx.connection(this)
        bg {
        val p = Cx.req(this, "GET", "/projects", connection = conn); val m = Cx.req(this, "GET", "/models", connection = conn); val ag = Cx.req(this, "GET", "/agents", connection = conn)
        if (ag.first == 200) JSONObject(ag.second).let { o -> runOnUiThread { if (!conn.isCurrent(this)) return@runOnUiThread; agents = o.optJSONArray("agents") ?: JSONArray(); defAgent = o.optString("default", "codex"); if (sid == null && newAgent() != "codex") loadCfg() } }
        runOnUiThread {
            if (!conn.isCurrent(this)) return@runOnUiThread
            if (p.first == 200) projects = JSONArray(p.second) else toast("连接失败：" + Cx.err(p))
            if (m.first == 200) JSONObject(m.second).let { models = it.optJSONArray("models") ?: JSONArray()
                if (CxPrefs.model(this).isEmpty()) { val d = it.optString("default"); val mm = Regex("(.*)\\[(.*)]").find(d)
                    if (mm != null) CxPrefs.setModel(this, mm.groupValues[1], "medium") } }
            if (CxPrefs.project(this) < 0 && projects.length() > 0) CxPrefs.setProject(this, projects.getJSONObject(0).getInt("id"))
            updateTitle(); buildDrawer(); if (sid == null) { list.removeAllViews(); emptyState() }
        }
    }
    }
    private fun projName(id: Int): String { for (i in 0 until projects.length()) projects.getJSONObject(i).let { if (it.getInt("id") == id) return it.getString("name") }; return "选择项目" }
    private fun buildDrawer() {
        side.removeAllViews()
        val na = newAgent(); val multi = usable().size > 1
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(12), dp(12), dp(8)) }
        heading.addView(UI.text(this, "项目与会话", 17f, R.color.text, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        heading.addView(UI.text(this, agentName(na) + if (multi) " ▾" else "", 13f, R.color.muted).apply {
            minimumHeight = dp(48); gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0)
            if (multi) { isFocusable = true; isFocusableInTouchMode = false; contentDescription = "选择 Agent"; foreground = UI.focusForeground(context, DS.R_SM); setOnClickListener { pickAgent() } }
        })
        side.addView(heading)
        val pid = CxPrefs.project(this)
        val pg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(16))
            background = UI.ripple(context, col(R.color.bg), 0); isFocusable = true; isFocusableInTouchMode = false
            contentDescription = "选择项目，${projName(pid)}"; foreground = UI.focusForeground(context, DS.R_SM); setOnClickListener { pickProject() }
        }
        pg.addView(UI.text(this, if (multi && CxPrefs.agentFilter(this)) "当前项目，已按 Agent 筛选" else "当前项目", 12f, R.color.muted))
        pg.addView(UI.text(this, projName(pid), 28f, R.color.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        pg.addView(UI.text(this, projPath(pid).orEmpty(), 13f, R.color.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setPadding(0, dp(4), 0, 0) })
        side.addView(pg)
        val search = EditText(this).apply { hint = "搜索会话"; textSize = 14.5f; isSingleLine = true; minHeight = dp(48); contentDescription = "搜索会话"; setTextColor(col(R.color.text)); setHintTextColor(col(R.color.muted))
            background = UI.round(context, col(R.color.surface2), 10); setPadding(dp(12), dp(8), dp(12), dp(8)); setText(threadQuery) }
        side.addView(search, UI.lp(this, 20, 0, 20, 8))
        val sl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8)) }
        side.addView(ScrollView(this).apply { addView(sl) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        side.addView(UI.filledButton(this, "新对话") { newChat() }.apply { textSize = 16f }, UI.lp(this, 20, 12, 20, 6))
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UI.round(context, col(R.color.surface), 12) }
        side.addView(g, UI.lp(this, 12, 6, 12, 14))
        UI.row(this, g, "设置", null) {
            drawer.closeDrawers()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
            openSettings()
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { threadQuery = e.toString().trim(); h.removeCallbacks(searchR); h.postDelayed(searchR, 300) }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {} })
        threadList = sl; loadThreads()
    }
    private var threadQuery = ""
    private var threadGeneration = 0
    private var threadList: LinearLayout? = null
    private val expanded = HashSet<String>()
    private val searchR = Runnable { loadThreads() }
    /** 会话列表 = Codex 自己的线程库 (与桌面端一致: 置顶 / 项目分组 / 对话, 最近优先) */
    private fun loadThreads() {
        val sl = threadList ?: return
        if (!Prefs.configured(this)) { sl.removeAllViews(); UI.empty(this, sl, "连接服务器后显示会话"); return }
        val query = threadQuery; val generation = ++threadGeneration
        val filter = if (CxPrefs.agentFilter(this) && usable().size > 1) newAgent() else null
        val connection = Cx.connection(this)
        bg {
            var r = Cx.req(this, "GET", "/threads?q=" + Uri.encode(query) + (filter?.let { "&agent=$it" } ?: ""), connection = connection)
            var phoneOnly = false
            // Desktop thread storage is optional; the bridge still owns usable phone sessions.
            if (r.first in setOf(404, 500, 501, 503) && filter == null) {
                val local = Cx.req(this, "GET", "/sessions", connection = connection)
                if (local.first == 200) {
                    val sessions = runCatching { JSONArray(local.second) }.getOrNull()
                    if (sessions != null) {
                        val rows = JSONArray()
                        for (i in 0 until sessions.length()) {
                            val t = sessions.getJSONObject(i)
                            if (query.isNotEmpty() && !(t.optString("title") + " " + t.optString("project")).contains(query, ignoreCase = true)) continue
                            t.put("sid", t.optString("id")).put("id", JSONObject.NULL).put("origin", "phone")
                            rows.put(t)
                        }
                        r = 200 to rows.toString(); phoneOnly = true
                    }
                }
            }
            runOnUiThread {
                if (generation != threadGeneration || !connection.isCurrent(this)) return@runOnUiThread
                sl.removeAllViews()
                if (r.first != 200) {
                    UI.row(this, sl, "会话列表暂不可用", sub = "点击重试，或检查电脑连接") { loadThreads() }
                    return@runOnUiThread
                }
                if (phoneOnly) sl.addView(UI.text(this, "桌面会话暂不可用，以下显示手机会话", 13f, R.color.muted).apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
                val a = JSONArray(r.second); val all = (0 until a.length()).map { a.getJSONObject(it) }
                if (all.isEmpty()) { sl.addView(UI.text(this, if (query.isEmpty()) "还没有会话" else "没有匹配的会话", 14f, R.color.muted).apply { setPadding(dp(24), dp(10), dp(24), 0) }); return@runOnUiThread }
                val pinned = all.filter { it.optBoolean("pinned") }
                val inProj = all.filter { !it.optBoolean("pinned") && !it.isNull("project") && it.optString("project").isNotEmpty() }
                val chats = all.filter { !it.optBoolean("pinned") && (it.isNull("project") || it.optString("project").isEmpty()) }
                if (query.isNotEmpty()) { all.forEach { threadRow(sl, it) }; return@runOnUiThread }   // 搜索: 平铺
                val openSet = folderOpen()
                fun isCur(t: JSONObject) = (t.optString("sid").isNotEmpty() && t.optString("sid") == sid) || (!t.isNull("id") && t.optString("id") == session?.optString("acp_sid"))
                val anyCur = all.any { isCur(it) }
                fun section(name: String, items: List<JSONObject>, key: String, limit: Int) {
                    if (items.isEmpty()) return
                    val hasCur = items.any { isCur(it) } || (!anyCur && sid != null && key == session?.optString("project"))
                    if (hasCur && autoOpened != sid) { openSet.add(key); autoOpened = sid; saveFolders(openSet) }
                    val open = key in openSet
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48); isFocusable = true; isFocusableInTouchMode = false; foreground = UI.focusForeground(context, DS.R_SM)
                        setPadding(dp(12), 0, dp(12), 0); background = UI.ripple(context, col(R.color.bg), 12)
                        setOnClickListener { if (key in openSet) openSet.remove(key) else openSet.add(key); saveFolders(openSet); loadThreads() } }
                    row.addView(UI.icon(this, if (key == "_chats") R.drawable.ms_forum else R.drawable.ms_folder, R.color.muted, 18).apply {
                        minimumWidth = 0; minimumHeight = 0; setPadding(0, 0, 0, 0); isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(10) })
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
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; minimumHeight = dp(64); setPadding(dp(14), dp(12), dp(14), dp(12))
            isFocusable = true; isFocusableInTouchMode = false; foreground = UI.focusForeground(context, DS.R_SM)
            background = UI.ripple(context, col(if (cur) R.color.primaryC else R.color.bg), 12) }
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
        box.tag = sidT
        box.contentDescription = "打开会话，${t.optString("title")}"
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
    // ───────── 模型 / 模式 ─────────
    private fun curModel() = session?.optString("model")?.ifEmpty { null } ?: CxPrefs.model(this)
    private fun curEffort() = session?.optString("effort")?.ifEmpty { null } ?: CxPrefs.effort(this)
    private fun curMode() = session?.optString("mode")?.ifEmpty { null } ?: CxPrefs.mode(this)
    private fun modelName(id: String): String { for (i in 0 until models.length()) models.getJSONObject(i).let { if (it.getString("id") == id) return it.optString("name", id) }; return id.ifEmpty { "默认模型" } }
    private fun updateTitle() {
        titleTv.text = session?.optString("title")?.ifEmpty { null } ?: "新对话"
        val pid = session?.optInt("project_id") ?: CxPrefs.project(this)
        projectTv.text = projName(pid) + if (curMode() == "ask") "  /  逐条审批" else "  /  项目内运行"
        projectTv.contentDescription = "选择项目，${projName(pid)}"
        val ag = curAgent(); input.hint = "给 ${agentName(ag)} 发消息"
        composerAgent.text = "${agentName(ag)} ▾"
        subTv.text = if (ag != "codex") "${agentName(ag)} ${cfgValueName("model")} ▾"
            else "${modelName(curModel())} · ${curEffort()}${if (session?.optString("backend") == "desktop") " · 桌面同步" else ""} ▾"

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
        pendingEcho = null; renderedEpoch = ""
        h.removeCallbacks(renderR); renderScheduled = false; renderPending = false
        clearApproval()
        list.removeAllViews(); tools.clear(); perms.clear(); askCards.clear(); thoughts.clear(); curMsg = null; curThought = null; lastSeq = 0; setRunning(false)
        taskId = null; taskState = "idle"; olderBtn = null; updateTaskStatus()
    }
    // ── 会话切换: 骨架屏 + 内存缓存 + 分页历史 + 后台 Markdown ──
    private class Cached(val meta: JSONObject, val hist: JSONObject)
    private val memCache = object : java.util.LinkedHashMap<String, Cached>(8, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Cached>?) = size > 6 }
    private var histFirst = 0L; private var histMore = false; private var olderBtn: TextView? = null; private var loadingOlder = false
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
        if (sid != id && (running || perms.isNotEmpty())) CodexService.start(this)
        Md.onLink = { t -> h.post { openLink(t) } }
        gen++; streamCall?.cancel(); sid = id; session = null; cfgOpts = JSONArray(); deliveryUnknown = false; CxPrefs.setSession(this, id); visibleSession = if (paneVisible) id else null; clearChat(); updateTitle()
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
            val recovered = AgentRepository.recover(this, id)
            val e = AgentRepository.snapshot(this, id); th.join()
            val t1 = System.currentTimeMillis()
            if (g != gen) return@bg
            val mm = m!!
            val ho = if (e.first == 200) JSONObject(e.second) else null
            ho?.optJSONArray("events")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { ev -> if (ev.optString("type") == "msg") MdView.warm(ctx, ev.optJSONObject("data")?.optString("text") ?: "") } }
            val t2 = System.currentTimeMillis()
            runOnUiThread {
                if (g != gen) return@runOnUiThread
                if (mm.first == 404) { toast("该消息关联的对话已不存在"); CxPrefs.setSession(this, null); openSession(null); return@runOnUiThread }
                if (mm.first != 200 || ho == null) { hideSkeleton(); if (c == null) sys("加载失败：" + Cx.err(if (mm.first != 200) mm else e)); return@runOnUiThread }
                val meta = JSONObject(mm.second); session = meta; updateTitle()
                deliveryUnknown = recovered.any { it.unknown }
                meta.optInt("project_id", -1).let { pj -> if (pj >= 0 && pj != CxPrefs.project(this)) { CxPrefs.setProject(this, pj); buildDrawer() } else if (autoOpened != sid) loadThreads() }; if (curAgent() != "codex") loadCfg()
                val same = c != null && c.hist.optLong("last") == ho.optLong("last") &&
                    c.hist.optString("data_epoch") == ho.optString("data_epoch")
                memCache[id] = Cached(meta, ho)
                val t3 = System.currentTimeMillis()
                if (!same) { clearChatKeep(); renderHistory(ho, false) } else status(ho)
                android.util.Log.i("leoperf", "v14 net=${t1 - t0} md=${t2 - t1} render=${System.currentTimeMillis() - t3} n=${ho.optJSONArray("events")?.length()} bytes=${e.second.length} cached=${c != null}")
                startStream(initialSnapshot = ho)
            }
        }
    }
    private fun clearChatKeep() { val p = pendingBanner; clearChat(); pendingBanner = p }
    private var histMode = false
    private fun renderHistory(ho: JSONObject, fromCache: Boolean) {
        hideSkeleton()
        renderedEpoch = ho.optString("data_epoch")
        histFirst = ho.optLong("first"); histMore = ho.optBoolean("more")
        if (histMore) addOlderBtn()
        val a = ho.getJSONArray("events"); histMode = true
        for (i in 0 until a.length()) apply(a.getJSONObject(i))
        histMode = false; lastSeq = ho.optLong("last")
        if (!fromCache) status(ho)
        pendingBanner?.let { list.addView(UI.text(this, it, 12.5f, R.color.muted).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(10)) }, if (olderBtn != null) 1 else 0); if (!fromCache) pendingBanner = null }
        renderNow(); scrollEnd(true); scroll.post { scroll.scrollTo(0, maxOf(0, conversation.height - scroll.height)) }
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
                val sv = arrayOf<Any?>(curMsg, curMsgSb, curThought, curThoughtSb)
                closeBlocks(); list = tmp; histMode = true
                val a = ho.getJSONArray("events"); for (i in 0 until a.length()) apply(a.getJSONObject(i))
                closeBlocks(); histMode = false; list = keep
                curMsg = sv[0] as MdView?; curMsgSb = sv[1] as StringBuilder; curThought = sv[2] as TextView?; curThoughtSb = sv[3] as StringBuilder
                olderBtn?.let { list.removeView(it) }; olderBtn = null
                histFirst = ho.optLong("first"); histMore = ho.optBoolean("more")
                val before = list.height; val views = (0 until tmp.childCount).map { tmp.getChildAt(it) }; tmp.removeAllViews()
                for ((k, v) in views.withIndex()) list.addView(v, k)
                if (histMore) addOlderBtn()
                list.post { scroll.scrollBy(0, list.height - before) }
            }
        }
    }
    private fun emptyState() {
        val configured = Prefs.configured(this)
        val needsProject = CxPrefs.project(this) < 0
        list.addView(UI.text(this, if (!configured) "连接你的电脑" else if (needsProject) "先选择一个项目" else "有什么可以帮你？", 24f, R.color.text, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(100), 0, dp(10)) })
        list.addView(UI.text(this, when {
            !configured -> "在电脑运行 leoagent-server pair，扫描二维码或粘贴配对链接"
            needsProject -> "选择电脑上的项目目录，Agent 会在该目录中执行任务"
            else -> "在 ${projName(CxPrefs.project(this))} 里工作 · 由你的电脑执行"
        }, 13.5f, R.color.muted).apply { gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), dp(14)) })
        if (!configured || needsProject) list.addView(UI.filledButton(this, if (!configured) "连接电脑" else "选择项目") {
            if (!configured) act.startActivity(Intent(act, SettingsActivity::class.java)) else pickProject()
        }, UI.lp(this, t = 10))
    }
    private fun status(o: JSONObject) {
        // A snapshot taken before the prompt arrived must not hide its local sending feedback.
        val incomingTask = o.optJSONObject("task")?.optString("id")?.takeIf { it.isNotEmpty() }
        val awaitingLocal = pendingEcho != null && taskState in setOf("submitting", "queued") &&
            (taskId == null || incomingTask != taskId)
        if (!awaitingLocal) {
            setRunning(o.optBoolean("running"))
            taskState = o.optString("task_state", o.optJSONObject("task")?.optString("state") ?: "idle")
            taskId = incomingTask
            if (taskState == "unknown") setRunning(false)
        }
        val p = o.optJSONArray("pending") ?: JSONArray(); val live = (0 until p.length()).map { p.getString(it) }.toSet()
        perms.keys.retainAll(live)
        o.optJSONArray("interactions")?.let { interactions ->
            val liveAsks = HashSet<String>()
            for (i in 0 until interactions.length()) {
                val item = interactions.getJSONObject(i)
                val spec = item.optJSONObject("spec") ?: continue
                val id = item.optString("id")
                if (item.optString("state", "pending") != "pending") continue
                if (item.optString("kind") == "perm") {
                    if (id !in responseInFlight) perms[id] = spec
                } else {
                    liveAsks.add(id)
                    if (!askCards.containsKey(id)) askCard(spec)
                }
            }
            askCards.keys.toList().filter { it !in liveAsks }.forEach { expireAsk(it) }
            val liveIds = (0 until interactions.length()).map { interactions.getJSONObject(it).optString("id") }.toSet()
            responseInFlight.retainAll(liveIds)
        }
        if (permShown != null && !perms.containsKey(permShown)) {
            clearApproval()
        }
        updateTaskStatus(); showNextPerm()
    }
    private fun startStream(refresh: Boolean = true, initialSnapshot: JSONObject? = null) {
        if (!paneVisible) return
        val s = sid ?: return
        if (!refresh && streamOwner == gen && streamThread?.isAlive == true) return
        streamCall?.cancel()
        val g = ++gen
        streamOwner = g
        val host = Prefs.url(this); val token = Prefs.token(this)
        fun active() = g == gen && paneVisible && !stopped && host == Prefs.url(this)
        fun ui(action: () -> Unit): Boolean {
            val done = CountDownLatch(1)
            h.post { try { if (active()) action() } finally { done.countDown() } }
            return done.await(5, TimeUnit.SECONDS) && active()
        }
        streamThread = Thread {
            var fails = 0
            var resnapshot = initialSnapshot == null
            var rebuild = false
            var epoch = initialSnapshot?.optString("data_epoch") ?: ""
            var generation = initialSnapshot?.optString("server_generation") ?: ""
            while (active()) {
                try {
                    if (resnapshot) {
                        val recovered = AgentRepository.recover(this, s)
                        val snapshot = AgentRepository.snapshot(this, s)
                        if (snapshot.first != 200) throw Exception("snapshot ${snapshot.first}")
                        val data = JSONObject(snapshot.second)
                        epoch = data.getString("data_epoch")
                        generation = data.optString("server_generation")
                        if (!ui {
                            deliveryUnknown = recovered.any { it.unknown }
                            // Keep the visible cursor until SSE has replayed every missing event.
                            // The snapshot cursor is global and may include unseen session events.
                            val keep = !rebuild && lastSeq <= data.optLong("last") &&
                                (renderedEpoch == epoch || (renderedEpoch.isEmpty() && pendingEcho != null && lastSeq == 0L))
                            if (keep) { renderedEpoch = epoch; status(data) }
                            else { clearChatKeep(); renderHistory(data, false) }
                            setConnection("")
                        }) break
                        resnapshot = false; rebuild = false
                    }
                    if (fails < 3) {
                        val client = Http.client.newBuilder().readTimeout(45, TimeUnit.SECONDS)
                            .followRedirects(false).followSslRedirects(false).build()
                        val call = client.newCall(Request.Builder().url("$host/api/v1/sessions/$s/stream?after=$lastSeq")
                            .header("User-Agent", "leoagent-android").header("X-Token", token).header("X-Data-Epoch", epoch).header("Accept", "text/event-stream").build())
                        streamCall = call
                        try {
                            call.execute().use { response ->
                                if (response.code == 409) { resnapshot = true; rebuild = true }
                                if (response.code != 200) throw Exception("stream ${response.code}")
                                fails = 0
                                if (!ui { setConnection("") }) return@Thread
                                response.body!!.charStream().buffered().use { reader ->
                                    var event = ""
                                    val data = StringBuilder()
                                    while (active()) {
                                        val line = reader.readLine() ?: break
                                        when {
                                            line.startsWith("event:") -> event = line.substring(6).trim()
                                            line.startsWith("data:") -> {
                                                if (data.isNotEmpty()) data.append('\n')
                                                data.append(line.substring(5).trimStart())
                                            }
                                            line.isEmpty() && data.isNotEmpty() -> {
                                                val o = JSONObject(data.toString())
                                                data.setLength(0)
                                                if (event == "status" && o.optString("server_generation") != generation) {
                                                    resnapshot = true; break
                                                }
                                                if (!ui {
                                                    if (event == "status") status(o)
                                                    else { live = true; apply(o); live = false; scheduleRender() }
                                                }) return@Thread
                                                event = ""
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            call.cancel()
                            if (streamCall === call) streamCall = null
                        }
                    } else {
                        val (code, text) = Http.call("$host/api/v1/sessions/$s/events?after=$lastSeq&wait=20",
                            headers = mapOf("User-Agent" to "leoagent-android", "X-Token" to token, "X-Data-Epoch" to epoch), readTimeoutMs = 35000)
                        if (code == 409) { resnapshot = true; rebuild = true }
                        if (code != 200) throw Exception("events $code")
                        val o = JSONObject(text)
                        if (o.optString("server_generation") != generation) resnapshot = true
                        else if (!ui {
                            live = true
                            val events = o.getJSONArray("events")
                            for (i in 0 until events.length()) apply(events.getJSONObject(i))
                            live = false; status(o); scheduleRender(); setConnection("")
                        }) break
                        if (++fails > 10) fails = 0
                    }
                } catch (_: Exception) {
                    if (!active()) break
                    fails++
                    ui { setConnection("连接暂时中断，正在重连；任务状态以电脑端为准") }
                    val delay = minOf(30000L, 500L * (1L shl minOf(fails, 6))) + kotlin.random.Random.nextLong(250)
                    var elapsed = 0L
                    while (active() && elapsed < delay) { Thread.sleep(200); elapsed += 200 }
                }
            }
        }.apply { start() }
    }

    private fun apply(ev: JSONObject) {
        val seq = ev.optLong("seq"); if (!histMode) { if (seq <= lastSeq) return; lastSeq = seq }
        val d = ev.optJSONObject("data") ?: JSONObject()
        if (ev.has("full_seq")) d.put("_fs", ev.optInt("full_seq"))
        when (ev.optString("type")) {
            "task" -> if (!histMode) {
                taskId = d.optString("task_id").takeIf { it.isNotEmpty() }
                taskState = d.optString("state", taskState)
                setRunning(taskState in setOf("queued", "running", "waiting_approval", "waiting_input", "cancelling", "recovering"))
                if (!running) finishMsg()
                updateTaskStatus()
            }
            "user" -> { closeBlocks(); if (pendingEcho != null && pendingEcho == d.optString("text")) pendingEcho = null else userBubble((if (d.optString("from") == "desktop") "🖥 " else "") + d.optString("text"), d.optInt("images")) }
            "msg" -> { curThought = null
                if (curMsg == null) {
                    val label = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(20), 0, dp(4)) }
                    label.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(8) })
                    label.addView(UI.text(this, agentName(curAgent()), 13f, R.color.text, true)); list.addView(label)
                    curMsgSb = StringBuilder(); curMsg = MdView(this).apply { setPadding(0, dp(4), 0, dp(12)) }
                    list.addView(curMsg, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
                curMsgSb.append(d.optString("text"))
                renderPending = true }
            "thought" -> { if (curThought == null) { curThoughtSb = StringBuilder(); curThought = UI.text(this, "", 13.5f, R.color.muted).apply {
                    setPadding(dp(12), dp(6), dp(8), dp(6)); background = UI.round(context, col(R.color.bg), 10); visibility = if (CxPrefs.think(context)) View.VISIBLE else View.GONE }
                    thoughts.add(curThought!!); list.addView(curThought, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }) }
                curThoughtSb.append(d.optString("text")); curThought!!.text = "思考：" + curThoughtSb }
            "tool" -> { finishMsg(); curMsg = null; curThought = null; toolCard(d) }
            "plan" -> { closeBlocks(); val a = d.optJSONArray("entries") ?: JSONArray()
                val sb = StringBuilder("执行计划\n"); for (i in 0 until a.length()) a.getJSONObject(i).let { sb.append(when (it.optString("status")) { "completed" -> "✓ "; "in_progress" -> "▶ "; else -> "○ " }).append(it.optString("content")).append('\n') }
                list.addView(UI.text(this, sb.trim(), 14f, R.color.text).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); background = UI.round(context, col(R.color.bg), 10) }, UI.lp(this, 0, 6, 0, 0)) }
            "ask" -> { closeBlocks(); askCard(d); if (live) scrollEnd(true) }
            "ask_done" -> askDone(d)
            "perm" -> { if (d.optString("rid") !in responseInFlight) perms[d.optString("rid")] = d; sys("请求审批：" + d.optString("title")) }
            "perm_done" -> { perms.remove(d.optString("rid")); responseInFlight.remove(d.optString("rid")); sys("审批：" + d.optString("name")) }
            "turn" -> { if (d.optString("state") == "running") { setRunning(true) } else { closeBlocks(); setRunning(false)
                for ((_, p) in tools.toList()) if (p.second.optString("status").let { it != "completed" && it != "failed" }) { p.second.put("status", "stopped"); toolCard(p.second) }
                when (d.optString("stopReason")) { "cancelled" -> sys("已停止"); "error" -> {}; else -> {} } } }
            "title" -> { session?.put("title", d.optString("title")); updateTitle() }
            "error" -> sys("出错：" + d.optString("msg"), R.color.strong)
            "deferred" -> sys(if (d.optString("state") == "blocked") "后续回答尚未发送：原任务未正常完成，请核对后重新提交。" else "回答已保存，等待当前任务结束后发送。")
        }
    }
    private fun finishMsg() { curMsg?.setMd(curMsgSb.toString()) }
    private fun closeBlocks() { finishMsg(); curMsg = null; curThought = null }
    private var renderScheduled = false
    private fun scheduleRender() {
        if (!renderPending) { showNextPerm(); stick(); return }
        if (!renderScheduled) {
            renderScheduled = true
            h.postDelayed(renderR, 32)
        }
    }
    private val renderR = Runnable { renderScheduled = false; renderNow(); stick() }
    private fun renderNow() { renderPending = false; finishMsg(); showNextPerm() }
    private fun scrollEnd(force: Boolean) { if (force) atBottom = true; if (atBottom) scroll.post { scroll.smoothScrollTo(0, maxOf(0, conversation.height - scroll.height)) } }
    private fun sys(s: String, color: Int = R.color.muted) {
        closeBlocks(); list.addView(UI.text(this, s, 13f, color).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)) })
    }
    private fun userBubble(t: String, imgs: Int) {
        val tv = UI.text(this, Md.linkify(this, SpannableStringBuilder((if (imgs > 0) "🖼 ×$imgs\n" else "") + t), color = col(R.color.primary)), 16f, R.color.text).apply { setTextIsSelectable(true); Md.touch(this); highlightColor = col(R.color.primaryC)
            background = UI.round(context, col(R.color.userBg), 16); setPadding(dp(15), dp(12), dp(15), dp(12)); maxWidth = (resources.displayMetrics.widthPixels * 0.85).toInt() }
        val row = LinearLayout(this).apply { gravity = Gravity.END; addView(tv) }
        list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
    }
    private fun toolCard(d: JSONObject) {
        val id = d.optString("id"); val ex = tools[id]
        val card = ex?.first ?: LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UI.round(context, col(R.color.surface2), 12) }.also {
            list.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }) }
        val open = card.tag == true
        tools[id] = Pair(card, d); card.removeAllViews()
        val st = d.optString("status"); val icon = when { st == "stopped" -> "■"; st == "failed" || (d.has("exit") && d.optInt("exit") != 0) -> "✗"; st == "completed" -> "✓"; else -> "⟳" }
        val kind = when (d.optString("kind")) { "read" -> "读取"; "edit" -> "编辑"; "execute" -> "运行"; "search" -> "搜索"; "fetch" -> "联网"; "delete" -> "删除"; "move" -> "移动"; else -> "工具" }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48)
            isFocusable = true; isFocusableInTouchMode = false; foreground = UI.focusForeground(context, DS.R_SM); contentDescription = "展开或收起工具详情"; setPadding(dp(12), dp(8), dp(12), dp(8))
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
            addView(LinearLayout(context).apply { for (f in fl.take(12)) addView(UI.text(context, f.substringAfterLast('/'), 12.5f, R.color.primary).apply {
                minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isFocusableInTouchMode = false; foreground = UI.focusForeground(context, DS.R_SM); setPadding(dp(10), dp(4), dp(10), dp(4)); background = UI.round(context, col(R.color.primaryC), 12); setOnClickListener { openLink(f) } }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(6) }) }) })
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
        if (askCards.containsKey(rid)) return
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = UI.round(context, col(R.color.primaryC), 14) }
        askCards[rid] = card
        list.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        card.addView(UI.text(this, d.optString("title", "需要你的输入"), 13f, R.color.primary, true))
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
                        bb.background = UI.round(this, col(if (on) R.color.primaryFill else R.color.surface), 10); bb.setTextColor(if (on) 0xFFFFFFFF.toInt() else col(R.color.text)) }
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
            val current = sid ?: return
            responseInFlight.add(rid)
            bg {
                val r = AgentRepository.command(this, current, "interaction", "/interactions/$rid/respond",
                    JSONObject().put("answers", ans).put("skip", skip))
                runOnUiThread {
                    if (sid != current) return@runOnUiThread
                    if (!r.accepted) toast(r.message)
                    if (r.unknown) deliveryUnknown = true
                    if (r.body.optString("code") in setOf("interaction_expired", "interaction_already_handled")) {
                        responseInFlight.remove(rid)
                        expireAsk(rid)
                    } else if (!r.accepted && !r.unknown) {
                        responseInFlight.remove(rid); row.alpha = 1f
                        for (k in 0 until row.childCount) row.getChildAt(k).isEnabled = true
                    }
                    updateTaskStatus(); startStream()
                }
            }
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
    private fun expireAsk(rid: String) {
        val card = askCards.remove(rid) ?: return
        val title = (card.getChildAt(0) as? TextView)?.text ?: "问题"
        card.removeAllViews()
        card.background = UI.round(this, col(R.color.bg), 14)
        card.addView(UI.text(this, title, 13f, R.color.muted, true))
        card.addView(UI.text(this, "请求已失效或已在其他设备处理", 13f, R.color.muted))
    }

    // ───────── 审批 ─────────
    private fun clearApproval() {
        permDialog?.dismiss(); permDialog = null; permShown = null
        if (::approvalHost.isInitialized) { approvalHost.removeAllViews(); approvalHost.visibility = View.GONE }
    }
    private fun showNextPerm() {
        if (permShown != null && !perms.containsKey(permShown)) clearApproval()
        if (perms.isEmpty()) { clearApproval(); return }
        if (!paneVisible || permShown != null) return
        val (rid, d) = perms.entries.first().toPair()
        val current = sid; val connection = Cx.connection(this)
        permShown = rid
        approvalHost.removeAllViews(); approvalHost.visibility = View.VISIBLE
        approvalHost.setBackgroundColor(col(R.color.approvalBg))
        approvalHost.setPadding(dp(20), dp(14), dp(20), dp(16))
        approvalHost.addView(UI.text(this, "等待你的批准" + if (perms.size > 1) "（${perms.size} 项）" else "", 13f, R.color.primary, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        approvalHost.addView(UI.text(this, d.optString("title"), 17f, R.color.text, true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0)
        })
        if (d.optString("detail").isNotBlank()) approvalHost.addView(UI.text(this, d.optString("detail"), 14f, R.color.text).apply {
            typeface = Typeface.MONOSPACE; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(2))
        }, UI.lp(this, 0, 8, 0, 0))
        val pid = session?.optInt("project_id") ?: CxPrefs.project(this)
        approvalHost.addView(UI.text(this, "项目：${projName(pid)}", 12f, R.color.muted).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setPadding(0, dp(2), 0, 0)
        })
        val opts = d.optJSONArray("options") ?: JSONArray()
        val order = listOf("allow_once", "reject_once", "allow_always", "reject_always")
        val sorted = (0 until opts.length()).map { opts.getJSONObject(it) }.sortedBy { order.indexOf(it.optString("kind")).let { i -> if (i < 0) 9 else i } }
        fun label(o: JSONObject) = when (o.optString("kind")) {
            "allow_once" -> "允许一次"; "allow_always" -> "始终允许"; "reject_once" -> "拒绝"; "reject_always" -> "始终拒绝"; else -> o.optString("name", "选择")
        }
        fun respond(o: JSONObject) {
            fun stillCurrent() = sid == current && connection.isCurrent(this) && perms.containsKey(rid) && permShown == rid
            if (!stillCurrent()) return
            if (o.optString("kind").startsWith("reject")) {
                AlertDialog.Builder(this).setTitle("拒绝本次操作？")
                    .setMessage("这条请求不会执行。Agent 已产生的文件改动仍然保留。")
                    .setNegativeButton("返回审批", null)
                    .setPositiveButton("确认拒绝") { _, _ -> if (stillCurrent()) answer(rid, o.optString("id")) }.show()
            } else answer(rid, o.optString("id"))
        }
        // Full request and every provider option remain available without covering the conversation automatically.
        approvalHost.addView(UI.filledButton(this, "查看完整请求", R.color.approvalBg, R.color.primary) {
            if (sid != current || !perms.containsKey(rid)) return@filledButton
            val dlg = BottomSheetDialog(this); permDialog = dlg
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24)); setBackgroundColor(col(R.color.surface)) }
            box.addView(UI.text(this, "${agentName(curAgent())} 请求执行", 17f, R.color.text, true))
            val detail = UI.text(this, d.optString("title") + "\n\n" + d.optString("detail"), 14f).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE; setPadding(0, dp(12), 0, dp(12)) }
            box.addView(ScrollView(this).apply { addView(detail) }, LinearLayout.LayoutParams(MATCH_PARENT, minOf(dp(200), resources.displayMetrics.heightPixels / 4)))
            val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (o in sorted) options.addView(UI.filledButton(this, label(o), if (o.optString("kind") == "allow_once") R.color.primary else R.color.surface2,
                if (o.optString("kind") == "allow_once") android.R.color.white else R.color.text) { respond(o); dlg.dismiss() }, UI.lp(this, 0, 8, 0, 0))
            box.addView(ScrollView(this).apply { addView(options) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            dlg.setContentView(box); dlg.setOnDismissListener { if (permDialog === dlg) permDialog = null }; dlg.show()
        }.apply { textSize = 13f; gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val quick = listOf("reject_once", "allow_once").mapNotNull { kind -> sorted.firstOrNull { it.optString("kind") == kind } }.ifEmpty { sorted.take(2) }
        for ((i, o) in quick.withIndex()) {
            val primary = o.optString("kind") == "allow_once"
            val button = if (primary) UI.filledButton(this, label(o)) { respond(o) } else UI.secondaryButton(this, label(o)) { respond(o) }
            button.textSize = 16f
            actions.addView(button, LinearLayout.LayoutParams(0, WRAP_CONTENT, if (primary) 1.6f else 1f).apply { if (i > 0) leftMargin = dp(10) })
        }
        approvalHost.addView(actions)
        updateTaskStatus()
    }
    private fun answer(rid: String, opt: String) { perms.remove(rid); clearApproval(); showNextPerm()
        val current = sid ?: return
        responseInFlight.add(rid)
        updateTaskStatus()
        bg {
            val r = AgentRepository.command(this, current, "interaction", "/interactions/$rid/respond", JSONObject().put("option", opt))
            runOnUiThread {
                if (sid != current) return@runOnUiThread
                if (!r.accepted) toast(r.message)
                if (r.unknown) deliveryUnknown = true else if (!r.accepted) responseInFlight.remove(rid)
                updateTaskStatus(); startStream()
            }
        }
    }

    // ───────── 发送 / 停止 / 图片 ─────────
    private fun send() {
        if (!Prefs.configured(this)) { act.startActivity(Intent(act, SettingsActivity::class.java)); return }
        if (sid == null && CxPrefs.project(this) < 0) { toast("请先选择电脑上的项目目录"); pickProject(); return }
        if (taskState == "unknown") { reviewUnknownTask(); return }
        if (deliveryUnknown) { reviewPending(); return }
        val t = input.text.toString().trim(); if (t.isEmpty() && images.isEmpty()) return
        val submittedImages = images.toList()
        val body = JSONObject().put("text", t)
        if (images.isNotEmpty()) body.put("images", JSONArray().apply { images.forEach { put(JSONObject().put("mime", it.first).put("data", it.second)) } })
        if (sid == null) list.removeAllViews()
        closeBlocks(); userBubble(t, images.size); pendingEcho = t; taskId = null; taskState = "submitting"; updateTaskStatus(); scrollEnd(true)
        input.setText(""); images.clear(); thumbs.removeAllViews(); thumbs.visibility = View.GONE; setRunning(true)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        val pid = CxPrefs.project(this)
        val conn = Cx.connection(this)
        val submittedSid = sid
        val creation = JSONObject().put("project", pid).put("model", CxPrefs.model(this)).put("effort", CxPrefs.effort(this)).put("mode", CxPrefs.mode(this)).put("agent", newAgent()).put("config", CxPrefs.agentCfg(this, newAgent()))
        bg {
            runCatching { MessageStore.prepare(this, conn) }
            var s = submittedSid
            if (s == null) {
                val r = Cx.req(this, "POST", "/sessions", creation, connection = conn)
                if (r.first != 200) { runOnUiThread { if (!conn.isCurrent(this) || sid != submittedSid) return@runOnUiThread; setRunning(false); taskState = "idle"; updateTaskStatus(); pendingEcho = null; input.setText(t); toast("创建对话失败：" + Cx.err(r)) }; return@bg }
                val o = JSONObject(r.second); s = o.getString("id")
                runOnUiThread { if (!conn.isCurrent(this) || sid != submittedSid) return@runOnUiThread; sid = s; session = o; CxPrefs.setSession(this, s); visibleSession = if (paneVisible) s else null; updateTitle(); startStream(refresh = false) }
            }
            val r = AgentRepository.command(this, s!!, "prompt", "/sessions/$s/tasks", body, expectedConnection = conn)
            runOnUiThread {
                if (!conn.isCurrent(this) || sid != s) return@runOnUiThread
                if (!r.accepted) {
                    if (r.unknown) {
                        deliveryUnknown = true
                        setConnection("提交结果暂未确认，正在查询原操作回执")
                    } else {
                        pendingEcho = null; setRunning(false); taskState = "failed"
                        if (input.text.isEmpty()) input.setText(t)
                        if (images.isEmpty()) images.addAll(submittedImages)
                    }
                    toast(r.message)
                } else {
                    val acceptedId = r.body.optString("task_id").takeIf { it.isNotEmpty() }
                    // SSE may already have delivered running or a terminal state before this receipt.
                    if (taskId != acceptedId) { taskId = acceptedId; taskState = "queued"; setRunning(true) }
                    scrollEnd(true); buildDrawer()
                }
                updateTaskStatus(); startStream(refresh = r.unknown)
            }
        }
    }
    private fun stop() {
        val id=taskId
        if (id != null) {
            val current = sid ?: return
            bg {
                val r = AgentRepository.command(this, current, "cancel", "/tasks/$id/cancel", JSONObject())
                runOnUiThread {
                    if (sid != current) return@runOnUiThread
                    if (!r.accepted) toast(r.message)
                    if (r.unknown) deliveryUnknown = true
                    updateTaskStatus(); startStream()
                }
            }
        } else {
            val s = sid ?: return
            bg { Cx.req(this, "POST", "/sessions/$s/cancel", JSONObject()) }
        }
        toast("正在停止…")
    }
    private fun reviewUnknownTask() {
        val current = sid ?: return
        val id = taskId ?: return
        AlertDialog.Builder(this).setTitle("核对本轮结果")
            .setMessage("本轮可能已经修改文件或执行命令。请先查看输出和文件差异；解除占用不会撤销已有改动。电脑会核对原 Agent 是否结束，无法确认时会继续保留占用。")
            .setNeutralButton("查看文件差异") { _, _ -> showDiff() }
            .setNegativeButton("暂不处理", null)
            .setPositiveButton("已核对，结束本轮") { _, _ ->
                bg {
                    val r = AgentRepository.command(this, current, "resolve", "/tasks/$id/resolve",
                        JSONObject().put("acknowledge_unknown_effects", true))
                    runOnUiThread {
                        if (sid != current) return@runOnUiThread
                        if (!r.accepted) toast(r.message)
                        if (r.unknown) deliveryUnknown = true
                        startStream()
                    }
                }
            }.show()
    }
    private fun reviewPending() {
        val current = sid ?: return
        AlertDialog.Builder(this).setTitle("核对未确认操作")
            .setMessage("手机保留了原操作 ID 和参数，重新连接会查询原回执。若电脑数据或配对身份已改变，请先在电脑核对任务和文件结果；清除本机记录不会停止任务，也不会撤销已有改动。")
            .setNeutralButton("重新查询") { _, _ -> startStream() }
            .setNegativeButton("取消", null)
            .setPositiveButton("我已核对，清除等待记录") { _, _ ->
                bg {
                    try {
                        AgentRepository.clearReviewedPending(this, current)
                        runOnUiThread { if (sid == current) { deliveryUnknown = false; updateTaskStatus(); startStream() } }
                    } catch (_: Exception) { runOnUiThread { toast("无法更新本机操作记录") } }
                }
            }.show()
    }
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
        val dlg = Dialog(this, R.style.AppTheme)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(16), dp(6)) }
        bar.addView(iconBtn("✕") { dlg.dismiss() }.apply { contentDescription = "关闭文件改动" })
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(UI.text(this, "文件改动", 17f, R.color.text, true))
        val sub = UI.text(this, projName(session?.optInt("project_id") ?: CxPrefs.project(this)), 12f, R.color.muted)
        heading.addView(sub); bar.addView(heading, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); box.addView(bar)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(32)) }
        box.addView(ScrollView(this).apply { addView(content); isFillViewport = true }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        dlg.setContentView(box); dlg.show(); dlg.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
        fun load() {
            content.removeAllViews(); content.addView(UI.text(this, "正在读取项目改动…", 16f, R.color.muted))
            bg {
                val r = Cx.req(this, "GET", "/sessions/$s/diff", readTimeout = 30000)
                runOnUiThread {
                    if (!dlg.isShowing) return@runOnUiThread
                    content.removeAllViews()
                    if (r.first != 200) {
                        content.addView(UI.text(this, Cx.err(r), 16f))
                        content.addView(UI.secondaryButton(this, "重试") { load() }, UI.lp(this, 0, 16, 0, 0)); return@runOnUiThread
                    }
                    val o = runCatching { JSONObject(r.second) }.getOrNull()
                    if (o == null) { content.addView(UI.text(this, "无法读取改动，请关闭后重试", 16f)); return@runOnUiThread }
                    if (!o.optBoolean("git")) { content.addView(UI.text(this, "该项目不是 Git 仓库，无法显示文件改动。", 16f, R.color.muted)); return@runOnUiThread }
                    sub.text = listOf(projName(session?.optInt("project_id") ?: CxPrefs.project(this)), o.optString("branch")).filter { it.isNotBlank() }.joinToString(" / ")
                    val diff = o.optString("diff")
                    if (diff.isBlank()) { content.addView(UI.text(this, "没有未提交的改动", 20f, R.color.text, true)); return@runOnUiThread }
                    val allLines = diff.lines(); val limited = allLines.take(6000).joinToString("\n")
                    val chunks = limited.split(Regex("(?m)(?=^diff --git )")).filter { it.isNotBlank() }
                    content.addView(UI.text(this, "项目文件改动", 28f, R.color.text, true))
                    content.addView(UI.text(this, "以下是项目当前未提交的改动。查看后可返回对话继续处理。", 14f, R.color.muted).apply { setPadding(0, dp(8), 0, dp(16)) })
                    if (o.optString("stat").isNotBlank()) content.addView(UI.text(this, o.optString("stat").trim(), 13f, R.color.muted).apply { typeface = Typeface.MONOSPACE; setPadding(0, 0, 0, dp(12)) })
                    for (chunk in chunks) {
                        val lines = chunk.lines()
                        val name = lines.firstOrNull { it.startsWith("+++ b/") }?.removePrefix("+++ b/")
                            ?: lines.firstOrNull { it.startsWith("--- a/") }?.removePrefix("--- a/") ?: lines.first()
                        val adds = lines.count { it.startsWith("+") && !it.startsWith("+++") }
                        val deletes = lines.count { it.startsWith("-") && !it.startsWith("---") }
                        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60)
                            isFocusable = true; isFocusableInTouchMode = false; foreground = UI.focusForeground(context, DS.R_SM); contentDescription = "展开或收起文件改动，$name" }
                        row.addView(UI.text(this, name, 16f, R.color.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                        row.addView(UI.text(this, "+$adds −$deletes", 13f, R.color.down).apply { setPadding(dp(12), 0, 0, 0) })
                        val body = UI.text(this, "", 14f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(12), dp(12), dp(12), dp(16)) }
                        val spans = SpannableStringBuilder()
                        for (line in lines.drop(1)) {
                            val from = spans.length; spans.append(line).append('\n')
                            val color = when {
                                line.startsWith("+++") || line.startsWith("---") -> R.color.muted
                                line.startsWith("@@") -> R.color.primary
                                line.startsWith("+") -> R.color.down
                                line.startsWith("-") -> R.color.strong
                                else -> R.color.text
                            }
                            spans.setSpan(ForegroundColorSpan(col(color)), from, spans.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        body.text = spans
                        val code = HorizontalScrollView(this).apply { setBackgroundColor(col(R.color.surface2)); addView(body) }
                        row.setOnClickListener { code.visibility = if (code.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
                        block.addView(row); block.addView(code); content.addView(block, UI.lp(this, 0, 8, 0, 12))
                    }
                    if (allLines.size > 6000) content.addView(UI.text(this, "改动较多，此处展示前 6000 行；完整内容请在电脑查看。", 14f, R.color.muted))
                }
            }
        }
        load()
    }
}
