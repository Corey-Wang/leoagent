package io.leoagent.app

import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import okhttp3.Request

/** 应用内桌面画中画: 轮询 /screen 拉 JPEG (~1.5fps), 隐藏即停止. 只看不控. */
class ScreenPip(private val host: FrameLayout) {
    private val c = host.context
    private val h = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = UI.dp(c, v)
    private var box: FrameLayout? = null
    private lateinit var img: ImageView
    private lateinit var label: TextView
    private var mode = "desktop"
    @Volatile private var gen = 0
    private var full: ImageView? = null
    private var fullDlg: Dialog? = null
    val shown get() = box != null

    fun toggle() { if (box == null) show() else close() }
    fun show() {
        if (box != null) return
        val w = dp(220); val b = FrameLayout(c).apply { background = UI.round(c, Color.BLACK, 12); clipToOutline = true; elevation = dp(8).toFloat() }
        img = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
        b.addView(img, FrameLayout.LayoutParams(-1, -1))
        label = TextView(c).apply { textSize = 11f; setTextColor(Color.WHITE); setShadowLayer(3f, 0f, 0f, Color.BLACK); text = "连接中…"; setPadding(dp(8), dp(6), dp(60), dp(6)) }
        b.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        fun chip(s: String, g: Int, f: () -> Unit) = TextView(c).apply { text = s; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = UI.round(c, 0x88000000.toInt(), 13); setOnClickListener { f() } }.also { b.addView(it, FrameLayout.LayoutParams(dp(26), dp(26), g).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
        chip("✕", Gravity.TOP or Gravity.END) { close() }
        val mt = chip("▭", Gravity.TOP or Gravity.END) { switchMode() }
        (mt.layoutParams as FrameLayout.LayoutParams).rightMargin = dp(34)
        val rz = chip("⤡", Gravity.BOTTOM or Gravity.END) {}
        // 拖动 + 点击放大
        var dx = 0f; var dy = 0f; var moved = false; var sx = 0f; var sy = 0f
        b.setOnTouchListener { v, e -> when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dx = v.x - e.rawX; dy = v.y - e.rawY; sx = e.rawX; sy = e.rawY; moved = false }
            MotionEvent.ACTION_MOVE -> { if (Math.abs(e.rawX - sx) + Math.abs(e.rawY - sy) > dp(6)) moved = true
                v.x = (e.rawX + dx).coerceIn(0f, (host.width - v.width).toFloat().coerceAtLeast(0f)); v.y = (e.rawY + dy).coerceIn(0f, (host.height - v.height).toFloat().coerceAtLeast(0f)) }
            MotionEvent.ACTION_UP -> if (!moved) openFull() }; true }
        // 右下角缩放
        var bw = 0; var rx = 0f
        rz.setOnTouchListener { _, e -> when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { bw = b.width; rx = e.rawX }
            MotionEvent.ACTION_MOVE -> { val nw = (bw + (e.rawX - rx)).toInt().coerceIn(dp(140), host.width - dp(8)); b.layoutParams = b.layoutParams.apply { width = nw; height = nw * 10 / 16 } } }; true }
        host.addView(b, FrameLayout.LayoutParams(w, w * 10 / 16))
        b.x = (host.width - w - dp(12)).toFloat().coerceAtLeast(0f); b.y = dp(12).toFloat()
        box = b; start()
    }
    fun close() { gen++; box?.let { host.removeView(it) }; box = null; fullDlg?.dismiss() }
    /** 页面隐藏时暂停 (省流量), 回来时继续 */
    fun pause() { gen++ }
    fun resume() { if (box != null) start() }
    private fun switchMode() { mode = if (mode == "desktop") "app" else "desktop"; label.text = if (mode == "app") "当前应用…" else "桌面…"; start() }
    private fun start() {
        val g = ++gen
        Thread {
            var fails = 0
            while (g == gen) {
                val t0 = System.currentTimeMillis(); val big = fullDlg != null
                try {
                    val url = CxPrefs.base(c) + "/screen?mode=$mode&w=${if (big) 1280 else 720}&q=${if (big) 60 else 45}"
                    Http.client.newCall(Request.Builder().url(url).header("X-Token", CxPrefs.token(c)).build()).execute().use { r ->
                        val bytes = r.body?.bytes() ?: ByteArray(0)
                        if (r.code == 200) {
                            val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size); fails = 0
                            val front = java.net.URLDecoder.decode(r.header("X-Front") ?: "", "UTF-8")
                            h.post { if (g == gen) { img.setImageBitmap(bm); full?.setImageBitmap(bm); label.text = if (mode == "app") "应用 · $front" else "桌面" } }
                        } else {
                            val msg = try { val j = org.json.JSONObject(String(bytes)); if (j.optString("error") == "capture_failed") "Mac 截屏失败：需授予屏幕录制权限，或 Mac 已锁屏" else j.optString("error") } catch (_: Exception) { "HTTP ${r.code}" }
                            h.post { if (g == gen) label.text = msg }; fails++
                        }
                    }
                } catch (e: Exception) { fails++; h.post { if (g == gen) label.text = "网络错误，重试中…" } }
                val wait = if (fails > 0) minOf(5000L, 1500L * fails) else 650L - (System.currentTimeMillis() - t0)
                if (wait > 0) Thread.sleep(wait)
            }
        }.start()
    }
    private fun openFull() {
        val d = Dialog(c, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val fr = FrameLayout(c).apply { setBackgroundColor(Color.BLACK) }
        val iv = ImageView(c).apply { scaleType = ImageView.ScaleType.MATRIX; setImageDrawable(img.drawable) }
        fr.addView(iv, FrameLayout.LayoutParams(-1, -1))
        val m = Matrix(); var fitted = false
        fun fit() { val dr = iv.drawable ?: return; if (iv.width == 0) return
            val s = minOf(iv.width.toFloat() / dr.intrinsicWidth, iv.height.toFloat() / dr.intrinsicHeight)
            m.setScale(s, s); m.postTranslate((iv.width - dr.intrinsicWidth * s) / 2, (iv.height - dr.intrinsicHeight * s) / 2); iv.imageMatrix = m; fitted = true }
        iv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!fitted) fit() }
        val sg = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { m.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY); iv.imageMatrix = m; return true } })
        val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean { m.postTranslate(-dx, -dy); iv.imageMatrix = m; return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { fit(); return true } })
        iv.setOnTouchListener { _, e -> sg.onTouchEvent(e); if (!sg.isInProgress) gd.onTouchEvent(e); true }
        // 新帧到来时: 保持当前缩放 (尺寸变化时重新适配)
        full = iv
        fun btn(s: String, g: Int, f: () -> Unit) = TextView(c).apply { text = s; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = UI.round(c, 0x66000000, 20); setOnClickListener { f() } }.also { fr.addView(it, FrameLayout.LayoutParams(dp(40), dp(40), g).apply { setMargins(dp(16), dp(36), dp(16), dp(16)) }) }
        btn("✕", Gravity.TOP or Gravity.END) { d.dismiss() }
        btn("▭", Gravity.TOP or Gravity.START) { switchMode(); fitted = false }
        d.setContentView(fr); d.setOnDismissListener { full = null; fullDlg = null; if (box == null) gen++ }
        fullDlg = d; d.show(); start()
    }
}
