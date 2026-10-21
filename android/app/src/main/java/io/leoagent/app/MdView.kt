package io.leoagent.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.spans.CodeSpan
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.network.OkHttpNetworkSchemeHandler
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin

/** 完整 GFM 渲染: 文本段走 Markwon, 表格/代码块拆成独立可横向滚动视图; 流式时按块 diff 只重绘变化的块 */
class MdView(c: Context) : LinearLayout(c) {
    data class Block(val type: Int, val src: String, val lang: String = "")
    companion object {
        const val TEXT = 0; const val CODE = 1; const val TABLE = 2
        private fun nightMode(c: Context) = c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        private val renderers = HashMap<Int, Markwon>()
        fun markwon(c: Context): Markwon = synchronized(renderers) {
            renderers.getOrPut(nightMode(c)) {
                build(c.applicationContext.createConfigurationContext(c.resources.configuration))
            }
        }
        private fun build(c: Context): Markwon {
            val ts = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, c.resources.displayMetrics)
            return Markwon.builder(c)
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TaskListPlugin.create(UI.col(c, R.color.primary), UI.col(c, R.color.primary), UI.col(c, R.color.surface)))
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(ImagesPlugin.create { it.addSchemeHandler(OkHttpNetworkSchemeHandler.create()) })
                .usePlugin(JLatexMathPlugin.create(ts) { it.inlinesEnabled(true) })
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureConfiguration(b: MarkwonConfiguration.Builder) { b.linkResolver { _, link -> Md.onLink?.invoke(link) } }
                    override fun configureTheme(b: io.noties.markwon.core.MarkwonTheme.Builder) {
                        b.codeBackgroundColor(UI.col(c, R.color.surface2)).codeTextColor(UI.col(c, R.color.text)).linkColor(UI.col(c, R.color.primary))
                            .blockQuoteColor(UI.col(c, R.color.primary2)).headingBreakHeight(0).thematicBreakColor(UI.col(c, R.color.border))
                            .bulletWidth((ts / 3).toInt()).headingTextSizeMultipliers(floatArrayOf(1.5f, 1.3f, 1.15f, 1.05f, 1f, 0.9f)) }
                }).build()
        }
        private val cache = object : java.util.LinkedHashMap<Pair<Int, String>, Spanned>(256, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Pair<Int, String>, Spanned>?) = size > 300 }
        fun text(c: Context, src: String): Spanned {
            val key = nightMode(c) to src
            synchronized(cache) { cache[key] }?.let { return it }
            val sp = SpannableStringBuilder(markwon(c).toMarkdown(src))
            val col = UI.col(c, R.color.primary)
            for (cs in sp.getSpans(0, sp.length, CodeSpan::class.java)) { val s = sp.getSpanStart(cs); val e = sp.getSpanEnd(cs); val t = sp.substring(s, e)
                if (Md.isPathLike(t)) sp.setSpan(LinkSpan(t.trim(), col), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            Md.linkify(c, sp)
            if (src.length > 30) synchronized(cache) { cache[key] = sp }
            return sp
        }
        fun warm(c: Context, src: String) { for (b in split(src)) if (b.type == TEXT && b.src.length > 30) text(c, b.src) }
        private val DELIM = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
        private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([\\w+#.-]*).*$")
        fun split(src: String): List<Block> {
            val out = ArrayList<Block>(); val lines = src.split("\n"); val buf = StringBuilder(); var i = 0
            fun flush() { val t = buf.toString().trim('\n'); if (t.isNotBlank()) out.add(Block(TEXT, t)); buf.setLength(0) }
            while (i < lines.size) {
                val l = lines[i]; val f = FENCE.matchEntire(l)
                if (f != null) { flush(); val mark = f.groupValues[1]; val code = StringBuilder(); i++
                    while (i < lines.size && !lines[i].trim().startsWith(mark)) { code.append(lines[i]).append('\n'); i++ }
                    i++; out.add(Block(CODE, code.toString().trimEnd('\n'), f.groupValues[2])); continue }
                if (l.contains('|') && i + 1 < lines.size && lines[i + 1].contains('-') && DELIM.matches(lines[i + 1])) {
                    flush(); val t = StringBuilder(l).append('\n').append(lines[i + 1]); i += 2
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { t.append('\n').append(lines[i]); i++ }
                    out.add(Block(TABLE, t.toString())); continue }
                buf.append(l).append('\n'); i++
            }
            flush(); return out
        }
        fun cells(row: String): List<String> {
            var r = row.trim(); if (r.startsWith("|")) r = r.drop(1); if (r.endsWith("|") && !r.endsWith("\\|")) r = r.dropLast(1)
            val out = ArrayList<String>(); val cur = StringBuilder(); var code = false; var k = 0
            while (k < r.length) { val ch = r[k]
                when { ch == '\\' && k + 1 < r.length && r[k + 1] == '|' -> { cur.append('|'); k++ }
                    ch == '`' -> { code = !code; cur.append(ch) }
                    ch == '|' && !code -> { out.add(cur.toString().trim()); cur.setLength(0) }
                    else -> cur.append(ch) }
                k++ }
            out.add(cur.toString().trim()); return out
        }
        private val KW = Regex("\\b(fun|val|var|def|class|object|interface|return|if|else|elif|for|while|in|is|import|from|package|let|const|function|async|await|try|catch|except|finally|raise|throw|new|public|private|protected|static|void|int|long|float|double|bool|boolean|string|true|false|null|None|True|False|nil|self|this|struct|enum|impl|pub|fn|mut|use|match|when|switch|case|break|continue|with|as|lambda|yield|type|go|func|defer|select|override|suspend|data|echo|then|fi|do|done|esac|export|SELECT|FROM|WHERE|INSERT|UPDATE|DELETE|JOIN|ORDER|BY|GROUP|AND|OR|NOT)\\b")
        private val TOK = Regex("(\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'|`[^`]*`)|(//[^\\n]*|#(?![!{\\[])[^\\n]*|--\\s[^\\n]*|/\\*[\\s\\S]*?\\*/)|(\\b\\d+(?:\\.\\d+)?\\b)")
        fun highlight(code: String, lang: String): CharSequence {
            val sb = SpannableStringBuilder(code); if (code.length > 30000) return sb
            val hashComment = lang.lowercase() in setOf("py", "python", "sh", "bash", "zsh", "shell", "yaml", "yml", "toml", "rb", "ruby", "r", "conf", "ini", "dockerfile", "makefile", "")
            val taken = BooleanArray(code.length)
            for (m in TOK.findAll(code)) { val g = when { m.groups[1] != null -> 0xFF0F9D58.toInt(); m.groups[2] != null -> if (m.value.startsWith("#") && !hashComment) continue else 0xFF9AA0A6.toInt(); else -> 0xFFE8710A.toInt() }
                sb.setSpan(ForegroundColorSpan(g), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); for (k in m.range) taken[k] = true }
            for (m in KW.findAll(code)) if (!taken[m.range.first]) { sb.setSpan(ForegroundColorSpan(0xFF7C4DFF.toInt()), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            return sb
        }
    }

    private var src = ""
    private val dp = c.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dp).toInt()
    init { orientation = VERTICAL }
    val source get() = src

    fun setMd(s: String) {
        src = s; val bl = split(s)
        for ((i, b) in bl.withIndex()) {
            val v = getChildAt(i); val old = v?.tag as? Block
            if (old == b) continue
            if (v != null && old != null && old.type == b.type) { bind(v, b); continue }
            val nv = create(b); bind(nv, b)
            if (v != null) removeViewAt(i)
            addView(nv, i, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (i > 0) topMargin = dp(8) })
        }
        while (childCount > bl.size) removeViewAt(childCount - 1)
    }
    /** 流式快速路径: 只在最后一块是普通文本且追加内容不含块语法时直接 append */
    fun appendFast(s: String): Boolean {
        val v = getChildAt(childCount - 1) as? TextView ?: return false
        if (s.any { it == '\n' || it == '|' || it == '`' || it == '$' || it == '*' || it == '_' || it == '~' || it == '[' }) return false
        v.append(s); src += s; return true
    }

    private fun tv(size: Float = 16f) = UI.text(context, "", size).apply { setTextIsSelectable(true); Md.touch(this) }
    private fun create(b: Block): View = when (b.type) {
        TEXT -> tv().apply { setLineSpacing(0f, 1.25f) }
        CODE -> LinearLayout(context).apply { orientation = VERTICAL
            background = GradientDrawable().apply { setColor(UI.col(context, R.color.md_code_background)); cornerRadius = dp(12).toFloat(); setStroke(dp(1).coerceAtLeast(1), UI.col(context, R.color.border)) }
            val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(6), 0) }
            head.addView(UI.text(context, "", 12f, R.color.muted).apply { tag = "lang" }, LayoutParams(0, WRAP_CONTENT, 1f))
            head.addView(UI.text(context, "复制", 12f, R.color.primary).apply { setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { val code = ((this@apply.parent.parent as View).tag as? Block)?.src ?: ""
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", code))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show() } })
            addView(head)
            val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
            hs.addView(tv(13f).apply { typeface = Typeface.MONOSPACE; setPadding(dp(12), dp(4), dp(12), dp(12)); setHorizontallyScrolling(true); setLineSpacing(0f, 1.15f); tag = "code" })
            addView(hs) }
        else -> HorizontalScrollView(context).apply { isFillViewport = true; isHorizontalScrollBarEnabled = false
            addView(TableLayout(context).apply { isStretchAllColumns = true }) }
    }
    private fun bind(v: View, b: Block) {
        v.tag = b
        when (b.type) {
            TEXT -> { val t = v as TextView; markwon(context).setParsedMarkdown(t, text(context, b.src)) }
            CODE -> { val g = v as LinearLayout; g.findViewWithTag<TextView>("lang").text = b.lang.ifEmpty { "code" }
                g.findViewWithTag<TextView>("code").text = highlight(b.src.ifEmpty { " " }, b.lang) }
            else -> bindTable((v as HorizontalScrollView).getChildAt(0) as TableLayout, b.src)
        }
    }
    private fun bindTable(t: TableLayout, s: String) {
        t.removeAllViews(); val rows = s.split("\n"); val al = cells(rows[1]).map { val l = it.startsWith(":"); val r = it.endsWith(":"); if (l && r) Gravity.CENTER_HORIZONTAL else if (r) Gravity.END else Gravity.START }
        val n = cells(rows[0]).size; val border = UI.col(context, R.color.border); val mw = markwon(context)
        val data = listOf(rows[0]) + rows.drop(2)
        for ((ri, row) in data.withIndex()) {
            val tr = TableRow(context); val cs = cells(row)
            for (ci in 0 until n) { val head = ri == 0
                val cell = tv(14f).apply { setPadding(dp(10), dp(7), dp(10), dp(7)); minWidth = dp(56); maxWidth = dp(260)
                    gravity = (al.getOrNull(ci) ?: Gravity.START) or Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply { setColor(UI.col(context, if (head) R.color.surface2 else if (ri % 2 == 0) R.color.md_table_stripe else R.color.surface)); setStroke(1, border) }
                    val sp = text(context, cs.getOrNull(ci) ?: ""); mw.setParsedMarkdown(this, sp); if (head) setTypeface(typeface, Typeface.BOLD) }
                tr.addView(cell, TableRow.LayoutParams(WRAP_CONTENT, MATCH_PARENT)) }
            t.addView(tr)
        }
        t.background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setStroke(dp(1), border) }
        t.clipToOutline = true; t.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    }
}
