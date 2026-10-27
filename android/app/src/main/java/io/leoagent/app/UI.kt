package io.leoagent.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** A「静默协作」：连续阅读、靛蓝动作、就近审批。 */
object DS {
    // Page title 32 · section 20 · body 16–17 · metadata 12–14.
    val TYPE = floatArrayOf(12f, 13f, 14f, 15f, 16f, 17f, 20f, 22f, 28f, 32f)
    fun snap(s: Float): Float { if (s > 34f) return s; var b = TYPE[0]; for (t in TYPE) if (kotlin.math.abs(t - s) <= kotlin.math.abs(b - s)) b = t; return b }
    const val S1 = 4; const val S2 = 8; const val S3 = 12; const val S4 = 16; const val S5 = 20; const val S6 = 24; const val S8 = 32
    const val R_SM = 12; const val R_MD = 12; const val R_LG = 16; const val R_PILL = 99
    const val TOUCH = 48; const val ROW = 56; const val BTN = 48
}
object UI {
    val REG: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MED: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    fun col(c: Context, id: Int) = c.getColor(id)
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
    fun tierColor(c: Context, tier: String) = col(c, when (tier.lowercase()) { "strong", "exit", "manual" -> R.color.strong; "weak" -> R.color.weak; "test" -> R.color.primary; else -> R.color.info })
    fun tierBg(c: Context, tier: String) = col(c, when (tier.lowercase()) { "strong", "exit", "manual" -> R.color.strongC; "weak" -> R.color.weakC; "test" -> R.color.primaryC; else -> R.color.infoC })
    fun round(c: Context, color: Int, r: Int) = GradientDrawable().apply { cornerRadius = dp(c, r).toFloat(); setColor(color) }
    fun pageBg(c: Context) = ColorDrawable(col(c, R.color.bg))
    fun card(c: Context, r: Int = DS.R_MD) = GradientDrawable().apply { cornerRadius = dp(c, r).toFloat(); setColor(col(c, R.color.surface)); setStroke(maxOf(1, dp(c, 1) / 2), col(c, R.color.border)) }
    fun outline(c: Context, bg: Int = R.color.surface, r: Int = DS.R_SM) = GradientDrawable().apply {
        cornerRadius = dp(c, r).toFloat(); setColor(col(c, bg)); setStroke(maxOf(1, dp(c, 1)), col(c, R.color.border))
    }
    // Keep this helper for existing call sites; the selected direction uses a solid fill.
    fun grad(c: Context, r: Int) = round(c, col(c, R.color.primaryFill), r)
    fun lift(v: View) { v.elevation = 0f }
    fun ripple(c: Context, bg: Int, r: Int) = RippleDrawable(ColorStateList.valueOf(0x22808080), round(c, bg, r), round(c, Color.WHITE, r))
    /** Keyboard focus feedback. Non-editable actions must not take touch focus: it consumes the first tap. */
    fun focusForeground(c: Context, r: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = dp(c, r).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(maxOf(dp(c, 2), 2), col(c, R.color.primary))
        })
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }
    fun lp(c: Context, l: Int = 16, t: Int = 0, r: Int = 16, b: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b)) }

    fun text(c: Context, s: CharSequence, size: Float = 15f, color: Int = R.color.text, bold: Boolean = false) = TextView(c).apply {
        val sz = DS.snap(size)
        text = s; textSize = sz; setTextColor(col(c, color)); typeface = if (bold) (if (sz >= 20f) BOLD else MED) else REG
        letterSpacing = when { sz >= 28f -> -0.02f; sz >= 20f -> -0.01f; sz <= 12f -> 0.01f; else -> 0f }
        setLineSpacing(0f, if (sz >= 20f) 1.08f else 1.18f)
    }
    fun mono(c: Context, s: CharSequence, size: Float, color: Int, bold: Boolean = true) = text(c, s, size, color, bold).apply { fontFeatureSettings = "tnum" }

    /** 大标题 + 可选副标题 */
    fun largeTitle(c: Context, parent: LinearLayout, title: String, sub: String? = null) {
        parent.addView(text(c, title, 32f, R.color.text, true).apply { setPadding(dp(c, DS.S5), dp(c, DS.S3), dp(c, DS.S5), 0) })
        if (sub != null) parent.addView(text(c, sub, 13f, R.color.muted).apply { setPadding(dp(c, DS.S5), dp(c, DS.S1), dp(c, DS.S5), 0) })
    }
    fun header(c: Context, parent: LinearLayout, s: String, right: String? = null) {
        val t = text(c, s, 13f, R.color.muted, false).apply { letterSpacing = 0.02f }
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(c, DS.S5), dp(c, DS.S6), dp(c, DS.S5), dp(c, DS.S2)) }
        row.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (right != null) row.addView(text(c, right, 13f, R.color.muted))
        parent.addView(row)
    }
    fun footer(c: Context, parent: LinearLayout, s: String) = parent.addView(text(c, s, 13f, R.color.muted).apply { setPadding(dp(c, DS.S5), dp(c, DS.S2), dp(c, DS.S5), 0) })

    /** 平面分组列表，行间使用细分隔线。 */
    fun group(c: Context, parent: LinearLayout, header: String? = null, right: String? = null): LinearLayout {
        if (header != null) header(c, parent, header, right)
        val g = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(c, R.color.surface)); clipToOutline = true }
        parent.addView(g, lp(c, t = if (header == null) 12 else 0)); return g
    }
    /** 卡片 = 带内边距的分组 */
    fun card(c: Context, parent: LinearLayout, header: String? = null, pad: Int = 16): LinearLayout {
        val g = group(c, parent, header); g.setPadding(dp(c, pad), dp(c, pad - 2), dp(c, pad), dp(c, pad)); return g
    }
    private fun sep(c: Context, g: LinearLayout) {
        if (g.childCount == 0) return
        g.addView(View(c).apply { setBackgroundColor(col(c, R.color.border)) }, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(c, 1) / 2)).apply { leftMargin = dp(c, 16) })
    }
    /** 列表行: 左标题(可带副标题) + 右值 + 可选 chevron */
    fun row(c: Context, g: LinearLayout, title: CharSequence, value: CharSequence? = null, valueColor: Int = R.color.muted,
            sub: CharSequence? = null, titleColor: Int = R.color.text, onClick: (() -> Unit)? = null): LinearLayout {
        sep(c, g)
        val rightText = value?.let { mono(c, it, 14f, valueColor, false).apply {
            gravity = Gravity.END; setPadding(dp(c, DS.S3), 0, 0, 0)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        } }
        val r = object : LinearLayout(c) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Use this row's available width, including drawers and narrow windows.
                rightText?.maxWidth = ((View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight) * 0.42f).toInt().coerceAtLeast(0)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(c, DS.ROW); setPadding(dp(c, DS.S4), dp(c, DS.S3), dp(c, DS.S4), dp(c, DS.S3)) }
        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(c, title, 16f, titleColor))
        if (sub != null) left.addView(text(c, sub, 13f, R.color.muted).apply { setPadding(0, dp(c, 2), 0, 0) })
        r.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (rightText != null) r.addView(rightText)
        if (onClick != null) {
            r.addView(android.widget.ImageView(c).apply { setImageResource(R.drawable.ms_chevron_right); rotation = 0f; imageTintList = ColorStateList.valueOf(col(c, R.color.muted)); alpha = 0.6f }, LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)).apply { leftMargin = dp(c, DS.S2) })
            r.background = RippleDrawable(ColorStateList.valueOf(0x22808080), null, null)
            r.foreground = focusForeground(c, DS.R_SM)
            r.isFocusable = true
            r.isFocusableInTouchMode = false
            r.setOnClickListener { onClick() }
        }
        g.addView(r); return r
    }
    /** 列表内操作：蓝色文字与完整行触控。 */
    fun action(c: Context, g: LinearLayout, title: String, color: Int = R.color.primary, onClick: () -> Unit) =
        row(c, g, title, titleColor = color, onClick = null).apply {
            background = RippleDrawable(ColorStateList.valueOf(0x22808080), null, null)
            foreground = focusForeground(c, DS.R_SM)
            isFocusable = true
            isFocusableInTouchMode = false
            contentDescription = title
            setOnClickListener { onClick() }
        }
    fun chip(c: Context, s: String, fg: Int, bg: Int) = TextView(c).apply {
        text = s; textSize = 12f; setTextColor(fg); typeface = MED; setPadding(dp(c, DS.S2 + 2), dp(c, DS.S1), dp(c, DS.S2 + 2), dp(c, DS.S1)); fontFeatureSettings = "tnum"
        background = GradientDrawable().apply { cornerRadius = dp(c, 99).toFloat(); setColor(bg) }
    }
    fun hrow(c: Context, vararg v: View, gap: Int = 6) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        v.forEachIndexed { i, x -> addView(x, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(c, gap) }) }
    }
    fun filledButton(c: Context, s: String, bg: Int = R.color.primary, fg: Int = android.R.color.white, onClick: () -> Unit) = TextView(c).apply {
        text = s; textSize = 17f; typeface = MED; gravity = Gravity.CENTER; setTextColor(col(c, fg)); minHeight = dp(c, DS.BTN)
        background = if (bg == R.color.primary) RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), grad(c, DS.R_SM), null) else ripple(c, col(c, bg), DS.R_SM)
        foreground = focusForeground(c, DS.R_SM)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = false
        contentDescription = s
        setOnClickListener { onClick() }
    }
    fun secondaryButton(c: Context, s: String, onClick: () -> Unit) = filledButton(c, s, R.color.surface, R.color.text, onClick).apply {
        background = RippleDrawable(ColorStateList.valueOf(0x22808080), outline(c), null)
    }
    /** Material Symbols Rounded 图标按钮 (48dp 触控区) */
    fun icon(c: Context, res: Int, tint: Int = R.color.text, size: Int = 24) = android.widget.ImageView(c).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(col(c, tint)); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        val p = dp(c, (DS.TOUCH - size) / 2); setPadding(p, p, p, p)
        minimumWidth = dp(c, DS.TOUCH); minimumHeight = dp(c, DS.TOUCH)
        isFocusable = true; isFocusableInTouchMode = false
        foreground = focusForeground(c, DS.R_SM)
    }
    /** 在 TextView 中央叠加图标 (保留原 TextView 类型与点击逻辑) */
    fun glyph(c: Context, tv: TextView, res: Int, tint: Int, bg: android.graphics.drawable.Drawable?, size: Int = 22) {
        val ic = c.getDrawable(res)!!.mutate().apply { setTint(tint) }
        val ld = android.graphics.drawable.LayerDrawable(if (bg != null) arrayOf(bg, ic) else arrayOf(ic))
        val k = if (bg != null) 1 else 0; ld.setLayerGravity(k, Gravity.CENTER); ld.setLayerSize(k, dp(c, size), dp(c, size))
        tv.text = ""; tv.background = ld
    }
    fun empty(c: Context, g: LinearLayout, s: String) = g.addView(text(c, s, 15f, R.color.muted).apply { gravity = Gravity.CENTER; setPadding(dp(c, 16), dp(c, 22), dp(c, 16), dp(c, 22)) })

    fun upDown(v: Double) = if (v > 0) R.color.up else if (v < 0) R.color.down else R.color.muted
    fun pct(v: Double) = String.format("%+.2f%%", v)
    fun f(o: Any?, n: Int = 2): String = when (o) { null, JSONObject.NULL -> "--"; is Number -> String.format("%.${n}f", o.toDouble()); else -> o.toString() }
}
