package io.leoagent.app

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.*

/** 轻量 Markdown -> Spannable: 代码块/行内代码/粗体/标题/列表/引用 */
class LinkSpan(val target: String, private val color: Int) : ClickableSpan() {
    override fun onClick(w: android.view.View) { Md.onLink?.invoke(target) }
    override fun updateDrawState(ds: android.text.TextPaint) { ds.color = color; ds.isUnderlineText = false }
}
object Md {
    /** 点击文件/链接的全局处理器 (CodexPane 设置) */
    @Volatile var onLink: ((String) -> Unit)? = null
    private const val EXT = "md|markdown|png|jpe?g|gif|svg|webp|bmp|ico|html?|pdf|css|scss|js|mjs|ts|tsx|jsx|vue|py|kt|kts|java|json|txt|log|csv|ya?ml|sh|go|rs|swift|sql|toml|xml|c|h|cpp|rb|php|ini|cfg|conf|gradle|ipynb|docx?|xlsx?|pptx?|zip"
    val PATH = Regex("file://[^\\s)\\]>\"'`]+|(?<![\\w/.])(?:~|/(?:Users|tmp|private|Volumes|opt|var|home))/[^\\s)\\]>\"'`，。；：、（）]+|(?<![\\w/~.:@-])(?:\\.{1,2}/)?[\\w.\\-]+(?:/[\\w.\\-]+)*\\.(?:$EXT)(?::\\d+(?::\\d+)?|#L\\d+)?(?![\\w/])", RegexOption.IGNORE_CASE)
    fun isPathLike(s: String) = PATH.matchEntire(s.trim()) != null
    /** 给已有文本中的路径加可点击 span */
    fun linkify(c: Context, sb: SpannableStringBuilder, from: Int = 0, color: Int? = null): SpannableStringBuilder {
        val col = color ?: UI.col(c, R.color.primary); val t = sb.toString()
        for (m in PATH.findAll(t, from)) { var e = m.range.last + 1; while (e > m.range.first && t[e - 1] in ".,;:!?。，") e--
            if (e - m.range.first < 3 || sb.getSpans(m.range.first, e, ClickableSpan::class.java).isNotEmpty()) continue
            sb.setSpan(LinkSpan(t.substring(m.range.first, e), col), m.range.first, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return sb
    }
    /** 可选择 TextView 中也能点击 LinkSpan */
    fun touch(tv: android.widget.TextView) { tv.setOnTouchListener { v, e ->
        val t = v as android.widget.TextView; val sp = t.text as? Spanned ?: return@setOnTouchListener false
        if (e.action == android.view.MotionEvent.ACTION_UP && e.eventTime - e.downTime < 350) { val l = t.layout ?: return@setOnTouchListener false
            val x = e.x - t.totalPaddingLeft + t.scrollX; val y = e.y.toInt() - t.totalPaddingTop + t.scrollY
            val line = l.getLineForVertical(y); if (x <= l.getLineRight(line)) { val off = l.getOffsetForHorizontal(line, x)
                val ls = sp.getSpans(off, off, ClickableSpan::class.java); if (ls.isNotEmpty()) { ls[0].onClick(t); return@setOnTouchListener true } } }
        false } }
    private val cache = object : java.util.LinkedHashMap<String, CharSequence>(256, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, CharSequence>?) = size > 400 }
    fun cached(src: String): CharSequence? = synchronized(cache) { cache[src] }
    /** 后台线程预渲染 */
    fun warm(c: Context, src: String) { if (src.length > 30 && cached(src) == null) { val r = render0(c, src); synchronized(cache) { cache[src] = r } } }
    fun render(c: Context, src: String): CharSequence = cached(src) ?: render0(c, src).also { if (src.length > 30) synchronized(cache) { cache[src] = it } }
    private fun render0(c: Context, src: String): CharSequence {
        val sb = SpannableStringBuilder(); val codeBg = UI.col(c, R.color.surface2); val muted = UI.col(c, R.color.muted)
        val lines = src.split("\n"); var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.trimStart().startsWith("```")) {
                val buf = StringBuilder(); i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { buf.append(lines[i]).append('\n'); i++ }
                i++
                val st = sb.length; sb.append(buf.toString().trimEnd('\n').ifEmpty { " " })
                sb.setSpan(TypefaceSpan("monospace"), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.86f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(codeBg), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append('\n'); continue
            }
            val h = Regex("^(#{1,6})\\s+(.*)").find(l)
            when {
                h != null -> { val st = sb.length; inline(c, sb, h.groupValues[2], codeBg)
                    sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(if (h.groupValues[1].length <= 2) 1.18f else 1.06f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                Regex("^\\s*[-*+]\\s+").containsMatchIn(l) && !Regex("^\\s*[-*]{3,}\\s*$").matches(l) -> {
                    val ind = l.length - l.trimStart().length; sb.append("  ".repeat(ind / 2)).append("•  "); inline(c, sb, l.trimStart().drop(2).trimStart(), codeBg) }
                l.startsWith(">") -> { val st = sb.length; inline(c, sb, l.drop(1).trimStart(), codeBg)
                    sb.setSpan(ForegroundColorSpan(muted), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                Regex("^\\s*[-*_]{3,}\\s*$").matches(l) -> sb.append("———")
                else -> inline(c, sb, l, codeBg)
            }
            if (i < lines.size - 1) sb.append('\n'); i++
        }
        return linkify(c, sb)
    }
    private val INL = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*|(!?)\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")
    private fun inline(c: Context, sb: SpannableStringBuilder, s: String, codeBg: Int) {
        var last = 0
        for (m in INL.findAll(s)) {
            sb.append(s, last, m.range.first); val st = sb.length
            when {
                m.groupValues[1].isNotEmpty() -> { sb.append(m.groupValues[1])
                    sb.setSpan(TypefaceSpan("monospace"), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(codeBg), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (isPathLike(m.groupValues[1])) sb.setSpan(LinkSpan(m.groupValues[1].trim(), UI.col(c, R.color.primary)), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                m.groupValues[2].isNotEmpty() -> { sb.append(m.groupValues[2]); sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                else -> { val img = m.groupValues[3] == "!"; val target = m.groupValues[5]
                    sb.append(if (img) "🖼 " + m.groupValues[4].ifEmpty { target.substringAfterLast('/') } else m.groupValues[4].ifEmpty { target })
                    sb.setSpan(LinkSpan(target, UI.col(c, R.color.primary)), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
            last = m.range.last + 1
        }
        sb.append(s, last, s.length)
    }
}
