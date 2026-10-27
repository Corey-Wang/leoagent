package io.leoagent.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

/** 文件浏览 + 内置查看器: Markdown / 图片 / HTML 原型 / PDF / 代码 / 其它 */
class FileActivity : AppCompatActivity() {
    private lateinit var sid: String
    private var path: String? = null
    private var line = 0
    private var info: JSONObject? = null
    private lateinit var titleTv: TextView; private lateinit var subTv: TextView
    private lateinit var bar: LinearLayout; private lateinit var right: LinearLayout
    private lateinit var body: FrameLayout
    private var web: WebView? = null
    private var pdf: PdfRenderer? = null; private var pfd: ParcelFileDescriptor? = null
    private var gen = 0
    private var full = false
    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun dp(v: Int) = UI.dp(this, v)
    private fun col(id: Int) = getColor(id)
    private val origin get() = Prefs.url(this)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sid = intent.getStringExtra("sid") ?: return finish()
        path = intent.getStringExtra("path")?.takeIf { it.isNotBlank() }; line = intent.getIntExtra("line", 0)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(8), dp(6)) }
        bar.addView(btn("‹", 30f) { onBackPressedCompat() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        titleTv = UI.text(this, "文件", 16.5f, R.color.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; gravity = Gravity.CENTER }
        subTv = UI.text(this, "", 11.5f, R.color.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.START; gravity = Gravity.CENTER }
        tcol.addView(titleTv); tcol.addView(subTv)
        bar.addView(tcol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumWidth = dp(48) }
        bar.addView(right)
        root.addView(bar)
        root.addView(View(this).apply { setBackgroundColor(col(R.color.border)) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
        body = FrameLayout(this); root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (path == null) browse(null, true) else openPath(path!!)
    }

    private val GLYPH = mapOf("‹" to R.drawable.ms_chevron_left, "⋯" to R.drawable.ms_more_horiz, "⟳" to R.drawable.ms_refresh, "⤢" to R.drawable.ms_fullscreen)
    private fun btn(s: String, size: Float = 20f, onClick: () -> Unit) = UI.text(this, s, size, R.color.primary).apply {
        gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0); minWidth = dp(DS.TOUCH); minHeight = dp(DS.TOUCH)
        GLYPH[s]?.let { UI.glyph(context, this, it, col(R.color.primary), UI.ripple(context, Color.TRANSPARENT, 22), if (s == "‹") 28 else 24) } ?: run { background = UI.ripple(context, Color.TRANSPARENT, 20) }
        contentDescription = when (s) { "‹" -> "返回"; "⋯" -> "更多文件操作"; "⟳" -> "刷新文件"; "⤢" -> "切换全屏"; else -> s }
        isFocusable = true; isFocusableInTouchMode = false; foreground = UI.focusForeground(context, DS.R_SM)
        setOnClickListener { onClick() } }
    private fun setRight(vararg v: View) { right.removeAllViews(); v.forEach { right.addView(it, LinearLayout.LayoutParams(if ((it as? TextView)?.text.isNullOrEmpty()) dp(DS.TOUCH) else WRAP_CONTENT, dp(DS.TOUCH))) } }

    // ───────── 网络 ─────────
    private fun conn(p: String, range: String? = null): HttpURLConnection {
        val u = URL(CxPrefs.base(this) + p).openConnection() as HttpURLConnection
        u.connectTimeout = 10000; u.readTimeout = 60000; u.setRequestProperty("X-Token", CxPrefs.token(this)); u.setRequestProperty("User-Agent", "leoagent-android")
        if (range != null) u.setRequestProperty("Range", range)
        return u
    }
    private fun q(s: String) = Uri.encode(s)
    private fun fileUrl(p: String) = "/sessions/$sid/file?path=" + q(p)
    private fun download(p: String, dst: File, onProg: (Long, Long) -> Unit = { _, _ -> }): Boolean {
        val u = conn(fileUrl(p)); if (u.responseCode != 200) return false
        val total = u.contentLengthLong; var n = 0L; val tmp = File(dst.path + ".part")
        u.inputStream.use { i -> tmp.outputStream().use { o -> val buf = ByteArray(65536); while (true) { val k = i.read(buf); if (k < 0) break; o.write(buf, 0, k); n += k; onProg(n, total) } } }
        return tmp.renameTo(dst)
    }
    private fun cacheFile(): File { val i = info!!; val d = File(cacheDir, "files").apply { mkdirs() }
        return File(d, (i.optString("abs").hashCode().toString(16) + "_" + i.optLong("mtime") + "_" + i.optString("name")).replace("/", "_")) }
    private fun ensureLocal(onProg: (Long, Long) -> Unit = { _, _ -> }): File? { val f = cacheFile(); if (f.exists() && f.length() == info!!.optLong("size")) return f
        return if (download(info!!.optString("abs"), f, onProg)) f else null }

    // ───────── 加载状态 ─────────
    private fun loading(msg: String = "正在加载…"): TextView {
        body.removeAllViews()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        box.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(28), dp(28)))
        val t = UI.text(this, msg, 13.5f, R.color.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        box.addView(t); body.addView(box, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return t
    }
    private fun error(msg: String, retry: (() -> Unit)? = null) {
        body.removeAllViews()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0) }
        box.addView(UI.text(this, "⚠︎", 34f, R.color.muted).apply { gravity = Gravity.CENTER })
        box.addView(UI.text(this, msg, 14.5f, R.color.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(16)) })
        if (retry != null) box.addView(UI.filledButton(this, "重试") { retry() }, LinearLayout.LayoutParams(dp(120), dp(40)))
        body.addView(box, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    // ───────── 浏览 ─────────
    private var recent = true; private var dir: String? = null
    private fun browse(d: String?, rec: Boolean) {
        val g = ++gen; recent = rec; dir = d; titleTv.text = if (rec) "最近文件" else (d?.substringAfterLast('/') ?: "项目文件"); subTv.text = d ?: ""
        setRight(btn(if (rec) "目录" else "最近", 15f) { browse(dir, !recent) })
        loading("正在读取文件列表…")
        thread {
            val r = Cx.req(this, "GET", "/sessions/$sid/files?limit=300&recent=" + (if (rec) "1" else "0") + (d?.let { "&path=" + q(it) } ?: ""), readTimeout = 30000)
            runOnUiThread { if (g != gen) return@runOnUiThread
                if (r.first != 200) return@runOnUiThread error(Cx.err(r)) { browse(d, rec) }
                val o = JSONObject(r.second); val items = o.getJSONArray("items"); val cur = o.getString("abs"); dir = cur
                subTv.text = o.optString("dir"); if (!rec) titleTv.text = cur.substringAfterLast('/')
                body.removeAllViews()
                val sw = SwipeRefreshLayout(this); val sc = ScrollView(this); val ls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(24)) }
                sc.addView(ls); sw.addView(sc); sw.setOnRefreshListener { browse(d, rec) }
                val grp = UI.group(this, ls)
                val rootAbs = o.optString("root").replace("~", System.getProperty("user.home") ?: "~")
                if (!rec && o.optString("dir") != o.optString("root")) fileRow(grp, JSONObject().put("name", "..").put("dir", true).put("kind", "dir").put("abs", cur.substringBeforeLast('/')).put("rel", "上一级"), true)
                if (items.length() == 0) UI.empty(this, ls, "没有文件")
                for (i in 0 until items.length()) fileRow(grp, items.getJSONObject(i), rec)
                body.addView(sw, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        }
    }
    private fun icon(k: String, name: String) = when (k) { "dir" -> "📁"; "md" -> "📝"; "image" -> "🖼"; "html" -> "🌐"; "pdf" -> "📕"; "code" -> if (name.endsWith(".css")) "🎨" else "📄"; else -> "📦" }
    private fun fileRow(g: LinearLayout, it: JSONObject, showRel: Boolean) {
        if (g.childCount > 0) g.addView(View(this).apply { setBackgroundColor(col(R.color.border)) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { leftMargin = dp(52) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = UI.ripple(context, col(R.color.surface), 0) }
        row.addView(UI.text(this, icon(it.optString("kind"), it.optString("name")), 22f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(30), WRAP_CONTENT).apply { rightMargin = dp(10) })
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(UI.text(this, it.optString("name"), 15.5f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
        val meta = listOfNotNull(if (showRel && it.optString("rel").contains('/')) it.optString("rel").substringBeforeLast('/') + "/" else null,
            if (it.has("mtime")) ago(it.optLong("mtime")) else null, if (!it.optBoolean("dir") && it.has("size")) size(it.optLong("size")) else null).joinToString(" · ")
        if (meta.isNotEmpty()) c.addView(UI.text(this, meta, 12f, R.color.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.START })
        row.addView(c, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(UI.text(this, "›", 20f, R.color.muted))
        row.setOnClickListener { _ -> if (it.optBoolean("dir")) browse(it.optString("abs"), false)
            else startActivity(Intent(this, FileActivity::class.java).putExtra("sid", sid).putExtra("path", it.optString("abs"))) }
        g.addView(row)
    }
    private fun ago(t: Long): String { val d = System.currentTimeMillis() / 1000 - t
        return when { d < 60 -> "刚刚"; d < 3600 -> "${d / 60} 分钟前"; d < 86400 -> "${d / 3600} 小时前"; d < 86400 * 7 -> "${d / 86400} 天前"; else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(t * 1000)) } }
    private fun size(n: Long) = when { n < 1024 -> "$n B"; n < 1 shl 20 -> String.format("%.1f KB", n / 1024.0); n < 1 shl 30 -> String.format("%.1f MB", n / 1048576.0); else -> String.format("%.2f GB", n / 1073741824.0) }

    // ───────── 打开文件 ─────────
    private fun openPath(p: String) {
        val g = ++gen; titleTv.text = p.substringAfterLast('/'); subTv.text = p; loading()
        thread {
            val r = Cx.req(this, "GET", "/sessions/$sid/fileinfo?path=" + q(p))
            runOnUiThread { if (g != gen) return@runOnUiThread
                if (r.first != 200) return@runOnUiThread error(Cx.err(r)) { openPath(p) }
                val i = JSONObject(r.second); info = i; titleTv.text = i.optString("name"); subTv.text = i.optString("path")
                if (i.optBoolean("dir")) return@runOnUiThread browse(i.optString("abs"), false)
                val menu = btn("⋯", 22f) { fileMenu() }
                when (i.optString("kind")) {
                    "md" -> { setRight(btn("⟳", 20f) { web?.reload() ?: openPath(p) }, menu); showMd() }
                    "image" -> { setRight(menu); showImage() }
                    "html" -> { setRight(btn("⟳", 20f) { web?.reload() }, btn("⤢", 20f) { setFull(true) }, menu); showHtml() }
                    "pdf" -> { setRight(menu); showPdf() }
                    "code" -> { setRight(menu); showCode() }
                    else -> { setRight(menu); showOther() }
                }
            }
        }
    }
    private fun preview(cb: (JSONObject) -> Unit) { val g = gen; thread {
        val r = Cx.req(this, "GET", "/sessions/$sid/preview?path=" + q(info!!.optString("abs")))
        runOnUiThread { if (g != gen) return@runOnUiThread; if (r.first != 200) error(Cx.err(r)) { openPath(info!!.optString("abs")) } else cb(JSONObject(r.second)) } } }

    // ───────── WebView (沙箱) ─────────
    private var pvPrefix = ""; private var pvRoots = listOf<String>()
    @SuppressLint("SetJavaScriptEnabled")
    private fun newWeb(js: Boolean, zoom: Boolean): WebView {
        web?.destroy()
        val prog = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 5 }
        val w = WebView(this).apply {
            setBackgroundColor(if (js) Color.WHITE else col(R.color.bg))
            settings.apply {
                javaScriptEnabled = js; domStorageEnabled = js
                allowFileAccess = false; allowContentAccess = false
                @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setGeolocationEnabled(false); javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
                builtInZoomControls = zoom; displayZoomControls = false; setSupportZoom(zoom); useWideViewPort = true; loadWithOverviewMode = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() { override fun onProgressChanged(v: WebView, p: Int) { prog.progress = p; prog.visibility = if (p >= 100) View.GONE else View.VISIBLE } }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean = route(req.url, js)
                override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? {
                    val s = req.url.scheme ?: ""; return if (s == "http" || s == "https" || s == "data" || s == "blob" || s == "about") null else WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", null, null)
                }
            }
        }
        body.removeAllViews(); body.addView(w, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        body.addView(prog, FrameLayout.LayoutParams(MATCH_PARENT, dp(3)))
        web = w; return w
    }
    /** 只允许桥接源: 同源 preview 路径内导航; 其它文件交给查看器; 外部链接交给系统浏览器 */
    private fun route(u: Uri, html: Boolean): Boolean {
        val s = u.toString()
        if (s.startsWith(origin + pvPrefix)) {
            val rest = (u.path ?: "").removePrefix(pvPrefix); val ri = rest.substringBefore('/'); val rel = rest.substringAfter('/', "")
            val idx = ri.removePrefix("r").toIntOrNull(); val abs = if (idx != null && idx < pvRoots.size) pvRoots[idx] + "/" + rel else null
            if (html && (rel.endsWith(".html") || rel.endsWith(".htm") || rel.endsWith("/") || !rel.substringAfterLast('/').contains('.'))) return false
            if (abs != null) startActivity(Intent(this, FileActivity::class.java).putExtra("sid", sid).putExtra("path", abs))
            return true
        }
        if (u.scheme == "http" || u.scheme == "https") AlertDialog.Builder(this).setMessage("在浏览器中打开外部链接？\n$s").setPositiveButton("打开") { _, _ -> try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {} }.setNegativeButton("取消", null).show()
        return true
    }
    private fun setFull(on: Boolean) {
        full = on; bar.visibility = if (on) View.GONE else View.VISIBLE
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) { c.hide(WindowInsetsCompat.Type.systemBars()); c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; Toast.makeText(this, "返回键退出全屏", Toast.LENGTH_SHORT).show() }
        else c.show(WindowInsetsCompat.Type.systemBars())
    }
    private fun onBackPressedCompat() { if (full) setFull(false) else finish() }
    @Deprecated("") override fun onBackPressed() { if (full) setFull(false) else if (web?.canGoBack() == true && info?.optString("kind") == "html") web!!.goBack() else super.onBackPressed() }

    private fun showHtml() { loading("正在打开页面…"); preview { o -> pvPrefix = o.getString("prefix"); pvRoots = jl(o); newWeb(js = true, zoom = true).loadUrl(origin + o.getString("url")) } }
    private fun jl(o: JSONObject) = o.getJSONArray("roots").let { a -> (0 until a.length()).map { a.getString(it) } }
    private fun showImage() { loading("正在加载图片…"); preview { o -> pvPrefix = o.getString("prefix"); pvRoots = jl(o)
        val bg = if (night) "#000" else "#F2F2F7"
        newWeb(js = false, zoom = true).loadDataWithBaseURL(origin + o.getString("url"), "<html><head><meta name=viewport content='width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=10'><style>html,body{margin:0;height:100%;background:$bg}body{display:flex;align-items:center;justify-content:center}img{max-width:100%;max-height:100vh;object-fit:contain}.c{background:repeating-conic-gradient(#8881 0 25%,transparent 0 50%) 0 0/16px 16px}</style></head><body><img class=c src='${o.getString("url").substringAfterLast('/')}'></body></html>", "text/html", "utf-8", null) } }
    private fun showMd() {
        loading("正在渲染文档…"); val g = gen
        preview { o -> pvPrefix = o.getString("prefix"); pvRoots = jl(o); thread {
            val text = try { val u = conn(fileUrl(info!!.optString("abs")), "bytes=0-3000000"); u.inputStream.bufferedReader().use { it.readText() } } catch (e: Exception) { null }
            val html = text?.let { MdHtml.page(it, night) }
            runOnUiThread { if (g != gen) return@runOnUiThread; if (html == null) return@runOnUiThread error("读取失败") { showMd() }
                newWeb(js = false, zoom = false).loadDataWithBaseURL(origin + o.getString("url"), html, "text/html", "utf-8", null) } } }
    }
    private fun showCode() {
        loading("正在读取…"); val g = gen; val i = info!!
        thread {
            val cap = 1_500_000L
            val text = try { val u = conn(fileUrl(i.optString("abs")), if (i.optLong("size") > cap) "bytes=0-${cap - 1}" else null); u.inputStream.bufferedReader().use { it.readText() } } catch (e: Exception) { null }
            if (text == null) { runOnUiThread { if (g == gen) error("读取失败") { showCode() } }; return@thread }
            val lines = text.split('\n'); val ext = i.optString("name").substringAfterLast('.', "").lowercase()
            val code = Hl.highlight(text, ext, night, maxChars = 400_000)
            val nums = (1..lines.size).joinToString("\n")
            runOnUiThread { if (g != gen) return@runOnUiThread
                body.removeAllViews()
                val sc = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(col(R.color.surface)) }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val ln = UI.text(this, nums, 12.5f, R.color.muted).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.END; setPadding(dp(8), dp(10), dp(8), dp(24)); setBackgroundColor(col(R.color.bg)); setLineSpacing(0f, 1.2f) }
                val tv = UI.text(this, code, 12.5f).apply { typeface = Typeface.MONOSPACE; setPadding(dp(10), dp(10), dp(16), dp(24)); setTextIsSelectable(true); setHorizontallyScrolling(true); setLineSpacing(0f, 1.2f) }
                row.addView(ln, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT)); row.addView(HorizontalScrollView(this).apply { addView(tv) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                sc.addView(row); body.addView(sc, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                if (i.optLong("size") > cap) subTv.text = "仅显示前 ${size(cap)} · " + i.optString("path")
                if (line > 0) tv.post { tv.layout?.let { l -> sc.scrollTo(0, maxOf(0, l.getLineTop(minOf(line - 1, l.lineCount - 1)) - dp(80))) } }
            }
        }
    }
    private fun showPdf() {
        val t = loading("正在下载 PDF…"); val g = gen
        thread {
            val f = try { ensureLocal { n, tot -> if (tot > 0) runOnUiThread { t.text = "正在下载 PDF… ${n * 100 / tot}%" } } } catch (e: Exception) { null }
            if (f == null) { runOnUiThread { if (g == gen) error("下载失败") { showPdf() } }; return@thread }
            runOnUiThread { if (g != gen) return@runOnUiThread
                try { pfd?.close(); pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); pdf = PdfRenderer(pfd!!) } catch (e: Exception) { return@runOnUiThread error("无法打开 PDF：" + e.message) }
                val r = pdf!!; body.removeAllViews()
                val sc = ScrollView(this).apply { setBackgroundColor(col(R.color.bg)) }; val ls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(30)) }
                sc.addView(ls); body.addView(sc, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                val W = resources.displayMetrics.widthPixels - dp(20); val n = minOf(r.pageCount, 300)
                subTv.text = "共 ${r.pageCount} 页 · " + info!!.optString("path")
                val views = (0 until n).map { k -> val pg = r.openPage(k); val h = (W.toFloat() * pg.height / pg.width).toInt(); pg.close()
                    ImageView(this).apply { setBackgroundColor(Color.WHITE); scaleType = ImageView.ScaleType.FIT_XY; elevation = dp(1).toFloat() }.also { ls.addView(it, LinearLayout.LayoutParams(W, h).apply { bottomMargin = dp(10) }) } }
                thread { for (k in 0 until n) { if (g != gen) break
                    val bmp = synchronized(r) { try { val pg = r.openPage(k); val sc2 = minOf(2f, 2400f / pg.width.coerceAtLeast(1)); val bw = (W * 1.5f).toInt().coerceAtMost((pg.width * 3)); val bh = (bw.toFloat() * pg.height / pg.width).toInt()
                        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE); pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); pg.close(); b } catch (e: Exception) { null } }
                    if (bmp != null) runOnUiThread { if (g == gen) views[k].setImageBitmap(bmp) } } }
            }
        }
    }
    private fun showOther() {
        body.removeAllViews(); val i = info!!
        val ls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(48), 0, 0) }
        ls.addView(UI.text(this, icon(i.optString("kind"), i.optString("name")), 56f).apply { gravity = Gravity.CENTER })
        ls.addView(UI.text(this, i.optString("name"), 18f, R.color.text, true).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(12), dp(24), dp(4)) })
        ls.addView(UI.text(this, "${size(i.optLong("size"))} · ${i.optString("mime")}\n修改于 ${ago(i.optLong("mtime"))}", 13.5f, R.color.muted).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f) })
        ls.addView(UI.filledButton(this, "分享 / 用其它应用打开") { share() }, LinearLayout.LayoutParams(dp(240), dp(48)).apply { topMargin = dp(28) })
        ls.addView(UI.filledButton(this, "下载到手机", R.color.surface2, R.color.primary) { saveToDownloads() }, LinearLayout.LayoutParams(dp(240), dp(48)).apply { topMargin = dp(10) })
        ls.addView(UI.text(this, "以文本方式查看", 14f, R.color.primary).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0); setOnClickListener { showCode() } })
        body.addView(ScrollView(this).apply { addView(ls) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    // ───────── 分享 / 下载 ─────────
    private fun fileMenu() { AlertDialog.Builder(this).setItems(arrayOf("分享 / 用其它应用打开", "下载到手机", "复制路径", "在文件列表中显示")) { _, w -> when (w) {
        0 -> share(); 1 -> saveToDownloads()
        2 -> { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("path", info?.optString("abs"))); Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show() }
        3 -> startActivity(Intent(this, FileActivity::class.java).putExtra("sid", sid).putExtra("path", info!!.optString("abs").substringBeforeLast('/')))
    } }.show() }
    private fun withLocal(cb: (File) -> Unit) { val i = info ?: return; val pd = AlertDialog.Builder(this).setMessage("正在下载 ${i.optString("name")}…").setCancelable(true).show()
        thread { val f = try { ensureLocal() } catch (e: Exception) { null }; runOnUiThread { pd.dismiss(); if (f == null) Toast.makeText(this, "下载失败", Toast.LENGTH_SHORT).show() else cb(f) } } }
    private fun share() = withLocal { f ->
        val u = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = info!!.optString("mime"); putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, info!!.optString("name"))) }
    private fun saveToDownloads() = withLocal { f ->
        try {
            val name = info!!.optString("name")
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, info!!.optString("mime")); put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LeoAgent") }
                val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
                Toast.makeText(this, "已保存到 下载/LeoAgent/$name", Toast.LENGTH_LONG).show()
            } else { val d = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!; f.copyTo(File(d, name), true); Toast.makeText(this, "已保存到 ${d.path}", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) { Toast.makeText(this, "保存失败：" + e.message, Toast.LENGTH_SHORT).show() }
    }
    override fun onDestroy() { gen++; web?.destroy(); try { pdf?.close(); pfd?.close() } catch (_: Exception) {}; super.onDestroy() }
}

/** Markdown -> HTML (文档查看器) */
object MdHtml {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun inline(s0: String): String {
        val codes = ArrayList<String>()
        var s = Regex("`([^`]+)`").replace(s0) { codes.add(it.groupValues[1]); "\u0000${codes.size - 1}\u0000" }
        s = esc(s)
        s = Regex("!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+&quot;[^&]*&quot;)?\\)").replace(s) { "<img alt=\"${it.groupValues[1]}\" src=\"${it.groupValues[2]}\">" }
        s = Regex("\\[([^\\]]+)]\\(([^)\\s]+)(?:\\s+&quot;[^&]*&quot;)?\\)").replace(s) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        s = Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__").replace(s) { "<b>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</b>" }
        s = Regex("(?<![\\w*])\\*([^*\\s][^*]*)\\*(?!\\w)").replace(s) { "<i>${it.groupValues[1]}</i>" }
        s = Regex("~~([^~]+)~~").replace(s) { "<del>${it.groupValues[1]}</del>" }
        s = Regex("\u0000(\\d+)\u0000").replace(s) { "<code>" + esc(codes[it.groupValues[1].toInt()]) + "</code>" }
        return s
    }
    private fun cells(l: String) = l.trim().trim('|').split(Regex("(?<!\\\\)\\|")).map { it.trim() }
    fun html(src: String): String {
        val L = src.replace("\r", "").split('\n'); val o = StringBuilder(); var i = 0
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) o.append("<p>").append(inline(para.toString().trim())).append("</p>\n"); para.clear() }
        while (i < L.size) {
            val l = L[i]; val t = l.trim()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> { flush(); val lang = t.drop(3).trim(); val b = StringBuilder(); i++
                    while (i < L.size && !L[i].trim().startsWith(t.take(3))) { b.append(L[i]).append('\n'); i++ }
                    o.append("<pre><code class=\"lang-${esc(lang)}\">").append(esc(b.toString().trimEnd('\n'))).append("</code></pre>\n") }
                t.isEmpty() -> flush()
                Regex("^#{1,6}\\s").containsMatchIn(t) -> { flush(); val n = t.takeWhile { it == '#' }.length; o.append("<h$n>").append(inline(t.drop(n).trim().trimEnd('#').trim())).append("</h$n>\n") }
                Regex("^([-*_]\\s*){3,}$").matches(t) -> { flush(); o.append("<hr>\n") }
                t.startsWith(">") -> { flush(); val b = StringBuilder(); while (i < L.size && L[i].trim().startsWith(">")) { b.append(L[i].trim().drop(1).trimStart()).append('\n'); i++ }; i--
                    o.append("<blockquote>").append(html(b.toString())).append("</blockquote>\n") }
                t.startsWith("|") && i + 1 < L.size && Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?$").matches(L[i + 1].trim()) -> { flush()
                    val al = cells(L[i + 1]).map { if (it.startsWith(":") && it.endsWith(":")) "center" else if (it.endsWith(":")) "right" else "left" }
                    o.append("<div class=tw><table><thead><tr>"); cells(l).forEachIndexed { k, c -> o.append("<th style=\"text-align:${al.getOrElse(k) { "left" }}\">").append(inline(c)).append("</th>") }
                    o.append("</tr></thead><tbody>"); i += 2
                    while (i < L.size && L[i].trim().startsWith("|")) { o.append("<tr>"); cells(L[i]).forEachIndexed { k, c -> o.append("<td style=\"text-align:${al.getOrElse(k) { "left" }}\">").append(inline(c)).append("</td>") }; o.append("</tr>"); i++ }
                    o.append("</tbody></table></div>\n"); i-- }
                Regex("^\\s*([-*+]|\\d+[.)])\\s+").containsMatchIn(l) -> { flush(); val ol = Regex("^\\s*\\d+[.)]").containsMatchIn(l); o.append(if (ol) "<ol>" else "<ul>")
                    while (i < L.size && Regex("^\\s*([-*+]|\\d+[.)])\\s+").containsMatchIn(L[i])) { var item = L[i].replace(Regex("^\\s*([-*+]|\\d+[.)])\\s+"), "")
                        val ind = L[i].length - L[i].trimStart().length
                        val task = Regex("^\\[([ xX])]\\s+").find(item); if (task != null) item = (if (task.groupValues[1] == " " ) "☐ " else "☑ ") + item.substring(task.range.last + 1)
                        o.append("<li" + (if (ind >= 2) " class=sub" else "") + ">").append(inline(item)).append("</li>"); i++ }
                    o.append(if (ol) "</ol>\n" else "</ul>\n"); i-- }
                else -> para.append(l).append('\n')
            }
            i++
        }
        flush(); return o.toString()
    }
    fun page(src: String, night: Boolean): String {
        val (bg, fg, mu, cb, bd, ac) = if (night) listOf("#000000", "#F2F2F7", "#98989F", "#1C1C1E", "#38383A", "#0A84FF") else listOf("#FFFFFF", "#1C1C1E", "#6E6E73", "#F2F2F7", "#E5E5EA", "#007AFF")
        return """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><style>
body{margin:0;padding:20px 18px 48px;background:$bg;color:$fg;font:16px/1.65 -apple-system,system-ui,"PingFang SC",sans-serif;word-wrap:break-word}
h1,h2,h3,h4{line-height:1.3;margin:1.4em 0 .5em;font-weight:700}h1{font-size:1.75em;margin-top:.3em}h2{font-size:1.38em;padding-bottom:.25em;border-bottom:1px solid $bd}h3{font-size:1.15em}
p{margin:.7em 0}a{color:$ac;text-decoration:none}img{max-width:100%;height:auto;border-radius:10px;display:block;margin:12px auto;background:$cb}
code{font:.86em ui-monospace,Menlo,monospace;background:$cb;padding:.15em .4em;border-radius:6px}pre{background:$cb;padding:14px;border-radius:12px;overflow-x:auto;line-height:1.45}pre code{background:none;padding:0;font-size:.84em}
blockquote{margin:1em 0;padding:.2em 1em;border-left:3px solid $bd;color:$mu}hr{border:0;border-top:1px solid $bd;margin:1.6em 0}
.tw{overflow-x:auto;margin:1em 0}table{border-collapse:collapse;font-size:.92em;min-width:100%}th,td{border:1px solid $bd;padding:7px 11px}th{background:$cb;font-weight:600}
ul,ol{padding-left:1.4em}li{margin:.25em 0}li.sub{margin-left:1.2em}del{color:$mu}
</style></head><body>${html(src)}</body></html>"""
    }
    private operator fun <T> List<T>.component6() = this[5]
}

/** 极简语法高亮 */
object Hl {
    private val KW = ("abstract and as assert async await break case catch class const continue data def default del defer delete do elif else enum except export extends false final finally fn for from func fun function go if impl implements import in init interface is lambda let match mod module mut new nil none not null object of or override package pass private protected pub public raise return self static struct super switch this throw throws trait true try type typeof undefined use val var void when where while with yield True False None").split(' ').toHashSet()
    fun highlight(src: String, ext: String, night: Boolean, maxChars: Int): CharSequence {
        val sb = SpannableStringBuilder(src)
        if (src.length > maxChars || ext in setOf("txt", "log", "csv")) return sb
        val hash = ext in setOf("py", "sh", "rb", "yaml", "yml", "toml", "ini", "cfg", "conf", "r", "pl", "env")
        val css = ext in setOf("css", "scss"); val markup = ext in setOf("html", "htm", "xml", "svg", "vue")
        val cKw = if (night) 0xFFFF7AB2.toInt() else 0xFFAD3DA4.toInt(); val cStr = if (night) 0xFFFC6A5D.toInt() else 0xFFC41A16.toInt()
        val cCom = if (night) 0xFF7F8C98.toInt() else 0xFF5D6C79.toInt(); val cNum = if (night) 0xFFD9C97C.toInt() else 0xFF1C00CF.toInt(); val cTag = if (night) 0xFF5DD8FF.toInt() else 0xFF0B4F79.toInt()
        val com = if (hash) "#[^\\n]*" else if (markup) "<!--[\\s\\S]*?-->" else "//[^\\n]*|/\\*[\\s\\S]*?\\*/"
        val re = Regex("($com)|(\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'|`(?:\\\\.|[^`\\\\])*`)|(\\b\\d+(?:\\.\\d+)?\\b)|(</?[A-Za-z][\\w:-]*|/?>)|([A-Za-z_][\\w]*)")
        for (m in re.findAll(src)) {
            val c = when { m.groups[1] != null -> cCom; m.groups[2] != null -> cStr; m.groups[3] != null -> cNum; m.groups[4] != null -> if (markup) cTag else continue
                m.groups[5] != null -> if (!css && !markup && m.value in KW) cKw else continue; else -> continue }
            sb.setSpan(ForegroundColorSpan(c), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (c == cKw) sb.setSpan(StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }
}
