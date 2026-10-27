package io.leoagent.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONObject
import java.io.File

/** Real composer, HTTP task worker and gated ACP chunks; no model or production data. */
class StreamingChecks(private val test: Instrumentation) {
    private val c = test.targetContext
    private fun ui(block: () -> Boolean): Boolean { var result = false; test.runOnMainSync { result = block() }; return result }
    private fun await(label: String, timeout: Long = 15000, block: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (block()) return; Thread.sleep(20) }
        error("Timed out: $label")
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun tap(v: View) {
        val xy = IntArray(2)
        test.runOnMainSync { check(v.isShown); v.getLocationOnScreen(xy) }
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, xy[0] + v.width / 2f, xy[1] + v.height / 2f, 0).let {
                it.source = InputDevice.SOURCE_TOUCHSCREEN; test.sendPointerSync(it); it.recycle()
            }
        }
    }
    fun run(dark: Boolean) {
        check(c.packageName == "io.leoagent.app.verification" && Prefs.configured(c))
        test.runOnMainSync { AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO) }
        val savedSid = SecureStore.read(c, "restart_verification")!!.getString("sid")
        val pid = JSONObject(Cx.req(c, "GET", "/sessions/$savedSid").second).getInt("project_id")
        val created = Cx.req(c, "POST", "/sessions", JSONObject().put("project", pid))
        check(created.first == 200)
        val seededSid = JSONObject(created.second).getString("id")
        check(AgentRepository.command(c, seededSid, "prompt", "/sessions/$seededSid/tasks", JSONObject().put("text", "stream history seed")).accepted)
        await("seed completed") { JSONObject(AgentRepository.snapshot(c, seededSid).second).optString("task_state") == "succeeded" }
        CxPrefs.setProject(c, pid)
        val a = test.startActivitySync(Intent(c, MainActivity::class.java).putExtra("tab", "agent").putExtra("sid", seededSid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            lateinit var pane: CodexPane
            fun field(name: String) = CodexPane::class.java.getDeclaredField(name).apply { isAccessible = true }.get(pane)
            test.runOnMainSync { pane = MainActivity::class.java.getDeclaredField("pane").apply { isAccessible = true }.get(a) as CodexPane }
            await("seed visible") { ui { views(field("list") as View).any { it is TextView && it.text.contains("fixture: stream history seed") } } }
            for (fresh in listOf(false, true)) {
                if (fresh) test.runOnMainSync { CodexPane::class.java.getDeclaredMethod("newChat").apply { isAccessible = true }.invoke(pane) }
                val text = "Android stream-check ${if (dark) "dark" else "light"} ${if (fresh) "new" else "existing"}"
                lateinit var list: LinearLayout; lateinit var scroll: ScrollView; lateinit var status: TextView; lateinit var send: TextView
                var history = emptyList<View>(); var top = 0; var height = 0
                test.runOnMainSync {
                    list = field("list") as LinearLayout; scroll = field("scroll") as ScrollView
                    status = field("taskStatusTv") as TextView; send = field("sendBtn") as TextView
                    if (!fresh) history = (0 until list.childCount).map { list.getChildAt(it) }
                    top = scroll.top; height = scroll.height
                    (field("input") as EditText).setText(text)
                }
                tap(send)
                lateinit var echo: TextView
                check(ui {
                    echo = views(list).filterIsInstance<TextView>().single { it.text.toString() == text }
                    status.isShown && status.text.isNotEmpty() && status.parent === scroll.getChildAt(0)
                }) { "Sending feedback must appear immediately inside the conversation" }
                fun stable() = ui {
                    check(echo.isAttachedToWindow && views(list).count { it === echo } == 1) { "Optimistic echo was rebuilt or removed" }
                    check(history.all { it.parent === list }) { "Existing history views were rebuilt" }
                    check(scroll.top == top && scroll.height == height) { "Task status moved the chat viewport" }
                    true
                }
                await("running before first text") { ui { field("taskState") == "running" } }
                stable()
                test.waitForIdleSync()
                test.uiAutomation.takeScreenshot()!!.let { bitmap ->
                    File(c.cacheDir, "stream-${if (dark) "dark" else "light"}-${if (fresh) "new" else "existing"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
                fun release(index: Int) = test.sendStatus(1, Bundle().apply { putString("stream", "STREAM_GATE $text|$index\n") })
                val first = "STREAM_FIRST " + "即时文字".repeat(300)
                val firstStart = SystemClock.elapsedRealtime()
                release(0)
                await("complete first chunk without character backlog", 1000) { ui { views(list).filterIsInstance<TextView>().any { it.text.toString() == first } } }
                test.sendStatus(1, Bundle().apply { putString("stream", "STREAM_FIRST_MS ${SystemClock.elapsedRealtime() - firstStart}\n") })
                lateinit var firstText: TextView
                test.runOnMainSync { firstText = views(list).filterIsInstance<TextView>().first { it.text.toString() == first } }
                stable()
                lateinit var activeSid: String
                test.runOnMainSync { activeSid = field("sid") as String }
                test.runOnMainSync { pane.onHide() }
                release(1)
                await("second chunk persisted while disconnected") {
                    JSONObject(AgentRepository.snapshot(c, activeSid).second).getJSONArray("events").toString().contains("STREAM_SECOND")
                }
                test.runOnMainSync { pane.onShow() }
                await("reconnect replays missing chunk") { ui { firstText.isAttachedToWindow && firstText.text.toString() == first + " STREAM_SECOND" } }
                stable()
                release(2)
                await("terminal state") { ui { field("taskState") == "succeeded" && send.contentDescription == "发送消息" && status.visibility == View.GONE } }
                check(ui { firstText.text.toString() == first + " STREAM_SECOND STREAM_DONE" && views(list).filterIsInstance<TextView>().count { it.text.toString() == text } == 1 })
                stable()
            }
        } finally { test.runOnMainSync { a.finish() } }
    }
}
