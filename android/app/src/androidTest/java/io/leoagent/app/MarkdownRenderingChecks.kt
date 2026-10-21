package io.leoagent.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TableLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import java.io.File

/** Draw the real native Markdown view; verify readable text, not just resource values. */
object MarkdownRenderingChecks {
    fun run(base: Context) {
        val source = """
            主要门槛有这些：

            | 环节 | 用户需要做什么 |
            |---|---|
            | 电脑端安装 | 安装依赖，运行脚本构建常驻服务 |
            | Agent 准备 | 安装 Agent 及 ACP 适配器，完成登录 |
            | 手机远程访问 | 配置 HTTPS 代理，再填写公网地址 |
            | Android 安装 | 安装 APK，允许安装未知应用 |
            | 配对 | 手机扫描，两端核对验证码并确认 |
            | 持续使用 | 电脑保持在线、唤醒，服务持续运行 |

            使用 `leoagent-server pair` 配对；切换深色和浅色主题后，行内代码也应清晰可读。

            ```sh
            leoagent-server pair
            # 手机扫描二维码
            ```
        """.trimIndent()
        for ((label, mode) in listOf("light" to Configuration.UI_MODE_NIGHT_NO,
            "dark" to Configuration.UI_MODE_NIGHT_YES, "light-again" to Configuration.UI_MODE_NIGHT_NO)) {
            val config = Configuration(base.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            }
            val c = ContextThemeWrapper(base.createConfigurationContext(config), R.style.AppTheme)
            val view = MdView(c).apply {
                setPadding(16, 16, 16, 16)
                setBackgroundColor(c.getColor(R.color.surface))
                setMd(source)
            }
            val width = (360 * c.resources.displayMetrics.density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(base.cacheDir, "markdown-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()

            fun readable(text: TextView, background: Int, description: String) {
                val ratio = ColorUtils.calculateContrast(text.currentTextColor, background)
                check(ratio >= 4.5) { "$label $description contrast=$ratio (minimum 4.5)" }
            }
            val table = (view.getChildAt(1) as HorizontalScrollView).getChildAt(0) as TableLayout
            for (ri in 0 until table.childCount) {
                val row = table.getChildAt(ri) as ViewGroup
                for (ci in 0 until row.childCount) {
                    val cell = row.getChildAt(ci) as TextView
                    val background = (cell.background as GradientDrawable).color!!.defaultColor
                    readable(cell, background, "table row $ri column $ci")
                }
            }
            val code = view.getChildAt(3) as ViewGroup
            readable(code.findViewWithTag("code"), (code.background as GradientDrawable).color!!.defaultColor, "code block")
        }
    }
}
