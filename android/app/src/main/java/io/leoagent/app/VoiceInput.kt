package io.leoagent.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 微信式语音输入：⌨/🎤 切换 + 「按住 说话」，上滑取消，松开转文字（Mac 本地 SenseVoice, POST /api/stt） */
class VoiceInput(private val act: AppCompatActivity, private val input: EditText, comp: LinearLayout, private val onSend: () -> Unit) {
    private val c = act
    private fun dp(v: Int) = UI.dp(c, v)
    private fun col(id: Int) = UI.col(c, id)
    private val sp = c.getSharedPreferences("codex", 0)
    private var voice = false
    private val toggle: TextView
    private val hold: TextView
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var rec: AudioRecord? = null
    @Volatile private var level = 0f
    private var pcm = ByteArrayOutputStream()
    private var t0 = 0L; private var cancel = false
    private val MAX_S = 600
    @Volatile private var sid = ""; @Volatile private var sent = 0; @Volatile private var live = ""; @Volatile private var upl: Thread? = null
    private var partialTv: TextView? = null
    private var overlay: View? = null; private var tip: TextView? = null; private var timer: TextView? = null; private var wave: Wave? = null; private var bubble: LinearLayout? = null

    init {
        toggle = TextView(c).apply { gravity = Gravity.CENTER; UI.glyph(c, this, R.drawable.vi_mic, col(R.color.text), UI.card(c, 20))
            setOnClickListener { setVoice(!voice) }
            setOnLongClickListener { settings(); true } }
        val i = comp.indexOfChild(input)
        comp.addView(toggle, i, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(8), 0, 0, 0) })
        hold = TextView(c).apply { text = "按住 说话"; textSize = 16f; typeface = UI.BOLD; gravity = Gravity.CENTER; setTextColor(col(R.color.text))
            background = UI.card(c, 22); visibility = View.GONE; setOnTouchListener(::touch) }
        comp.addView(hold, i + 2, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
    }
    private fun autoSend() = sp.getBoolean("voice_autosend", false)
    private fun settings() {
        val sw = Switch(c).apply { text = "识别后自动发送"; isChecked = autoSend(); setPadding(dp(20), dp(16), dp(20), dp(8)) }
        AlertDialog.Builder(c).setTitle("语音输入").setView(sw).setPositiveButton("好") { _, _ -> sp.edit().putBoolean("voice_autosend", sw.isChecked).apply() }.show()
    }
    private fun setVoice(v: Boolean) {
        voice = v; UI.glyph(c, toggle, if (v) R.drawable.vi_keyboard else R.drawable.vi_mic, col(R.color.text), UI.card(c, 20))
        input.visibility = if (v) View.GONE else View.VISIBLE; hold.visibility = if (v) View.VISIBLE else View.GONE
        val imm = c.getSystemService(InputMethodManager::class.java)
        if (v) imm.hideSoftInputFromWindow(input.windowToken, 0) else { input.requestFocus(); imm.showSoftInput(input, 0) }
        if (v && !granted()) act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7301)
    }
    private fun granted() = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("ClickableViewAccessibility")
    private fun touch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { Http.warm(CxPrefs.base(c) + "/agents", CxPrefs.token(c)); if (!granted()) { act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7301); return true }
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); start() }
            MotionEvent.ACTION_MOVE -> if (rec != null) { val cn = e.y < -dp(60); if (cn != cancel) { cancel = cn; styleCancel() } }
            MotionEvent.ACTION_UP -> if (rec != null) finish(!cancel)
            MotionEvent.ACTION_CANCEL -> if (rec != null) finish(false)
        }
        return true
    }
    @SuppressLint("MissingPermission")
    private fun start() {
        val sr = 16000; val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200) * 2) } catch (x: Exception) { null }
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) { Toast.makeText(c, "麦克风不可用", Toast.LENGTH_SHORT).show(); r?.release(); return }
        pcm = ByteArrayOutputStream(); cancel = false; sid = java.util.UUID.randomUUID().toString(); sent = 0; live = ""; t0 = System.currentTimeMillis(); rec = r; r.startRecording()
        Thread {
            val buf = ShortArray(1600); val bb = ByteBuffer.allocate(3200).order(ByteOrder.LITTLE_ENDIAN)
            while (rec === r) { val n = r.read(buf, 0, buf.size); if (n <= 0) continue
                var s = 0.0; bb.clear(); for (k in 0 until n) { s += buf[k] * buf[k].toDouble(); bb.putShort(buf[k]) }
                level = (sqrt(s / n) / 6000.0).toFloat().coerceIn(0f, 1f); synchronized(pcm) { pcm.write(bb.array(), 0, n * 2) } }
        }.start()
        val mySid = sid
        upl = Thread { // 边说边识别：把新录到的 PCM 增量发给 Mac 流式识别，取回实时结果
            while (true) { val rec0 = rec != null && sid == mySid
                val d = synchronized(pcm) { val a = pcm.toByteArray(); if (a.size > sent) a.copyOfRange(sent, a.size) else null }
                if (d == null) { if (!rec0) break; Thread.sleep(120); continue }
                val r = post("?op=chunk&sid=$mySid", d, "application/octet-stream", 15000)
                if (r.has("error")) { if (!rec0) break; Thread.sleep(500); continue }
                sent += d.size; val pt = r.optString("partial")
                if (sid == mySid) { live = pt; act.runOnUiThread { partialTv?.let { it.text = pt.ifEmpty { "正在听…" } } } }
                if (rec0) Thread.sleep(150) }
        }.apply { start() }
        hold.text = "松开 结束"; hold.background = UI.round(c, col(R.color.primaryC), 22); hold.setTextColor(col(R.color.primary)); showOverlay(); h.post(tick)
    }
    private val tick = object : Runnable { override fun run() { if (rec == null) return
        val s = (System.currentTimeMillis() - t0) / 1000; timer?.text = if (s >= MAX_S - 10) "${MAX_S - s}″ 后自动结束" else String.format("%d:%02d", s / 60, s % 60)
        wave?.push(level); if (s >= MAX_S) finish(true) else h.postDelayed(this, 50) } }
    private fun finish(ok: Boolean) {
        val r = rec ?: return; rec = null; try { r.stop() } catch (_: Exception) {}; r.release()
        hideOverlay(); hold.text = "按住 说话"; hold.background = UI.card(c, 22); hold.setTextColor(col(R.color.text))
        val ms = System.currentTimeMillis() - t0
        if (!ok) return
        if (ms < 700) { Toast.makeText(c, "说话时间太短", Toast.LENGTH_SHORT).show(); return }
        val data = synchronized(pcm) { pcm.toByteArray() }
        hold.text = "识别中…"; hold.isEnabled = false
        val mySid = sid; val u = upl
        Thread {
            try { u?.join(20000) } catch (_: Exception) {}
            // 流式会话完整（所有 PCM 已送达）则让服务端对整段做 SenseVoice 精识别；否则整段重传
            var res = if (u != null && !u.isAlive && sent >= data.size) post("?op=final&sid=$mySid", byteArrayOf(0), "text/plain", 60000) else JSONObject().put("error", "x")
            if (res.has("error")) res = upload(wav(data))
            if (res.has("error") && live.isNotBlank()) res = JSONObject().put("text", live)
            act.runOnUiThread { hold.text = "按住 说话"; hold.isEnabled = true
                val txt = res.optString("text").trim()
                if (res.has("error") || txt.isEmpty()) { Toast.makeText(c, if (res.has("error")) "识别失败：" + res.optString("error") else "没听清，请再说一次", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                val cur = input.text.toString(); input.setText(if (cur.isBlank()) txt else "$cur $txt"); input.setSelection(input.text.length)
                if (autoSend()) onSend() else setVoice(false)
            }
        }.start()
    }
    private fun wav(p: ByteArray): ByteArray { val b = ByteBuffer.allocate(44 + p.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + p.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(p.size).put(p); return b.array() }
    private fun upload(body: ByteArray) = post("", body, "audio/wav", 90000)
    private fun post(q: String, body: ByteArray, ct: String, rt: Int): JSONObject = try {
        val (code, s) = Http.call(CxPrefs.base(c) + "/stt" + q, "POST", mapOf("X-Token" to CxPrefs.token(c)), body, ct, rt)
        try { JSONObject(s) } catch (_: Exception) { JSONObject().put("error", "HTTP $code") }
    } catch (e: Exception) { JSONObject().put("error", e.message ?: "网络错误") }

    private fun showOverlay() {
        val root = act.findViewById<ViewGroup>(android.R.id.content)
        val fl = FrameLayout(c).apply { setBackgroundColor(0x801D1F3A.toInt()) }
        val b = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(18), dp(20), dp(16)); background = UI.grad(c, DS.R_LG); UI.lift(this); elevation = dp(8).toFloat() }
        wave = Wave(c).also { b.addView(it, LinearLayout.LayoutParams(dp(150), dp(44))) }
        timer = TextView(c).apply { textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; text = "0:00" }.also { b.addView(it) }
        partialTv = TextView(c).apply { textSize = 15f; setTextColor(Color.WHITE); text = "正在听…"; maxLines = 6; ellipsize = android.text.TextUtils.TruncateAt.START; setPadding(0, dp(8), 0, 0) }
            .also { b.addView(it, LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT)) }
        bubble = b
        fl.addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        tip = TextView(c).apply { textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; text = "松开 发送 · 上滑 取消" }
        fl.addView(tip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(140) })
        root.addView(fl, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); overlay = fl
    }
    private fun styleCancel() {
        bubble?.background = if (cancel) UI.round(c, col(R.color.strong), DS.R_LG) else UI.grad(c, DS.R_LG)
        tip?.text = if (cancel) "松开手指，取消发送" else "松开 发送 · 上滑 取消"; tip?.setTextColor(if (cancel) col(R.color.strong) else Color.WHITE)
        hold.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
    private fun hideOverlay() { overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }; overlay = null; wave = null; tip = null; timer = null; bubble = null; partialTv = null }

    private class Wave(c: android.content.Context) : View(c) {
        private val v = FloatArray(24); private var i = 0
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        fun push(l: Float) { v[i % v.size] = l; i++; invalidate() }
        override fun onDraw(cv: Canvas) { val n = v.size; val w = width / n.toFloat(); p.strokeWidth = w * 0.5f
            for (k in 0 until n) { val l = v[(i + k) % n]; val hh = (0.12f + 0.88f * l) * height; val x = w * (k + 0.5f)
                cv.drawLine(x, (height - hh) / 2, x, (height + hh) / 2, p) } }
    }
}
