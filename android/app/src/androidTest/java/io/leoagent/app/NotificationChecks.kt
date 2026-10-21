package io.leoagent.app

import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONObject
import java.io.File

/** Real app/service/NotificationManager integration, confined to the verification package. */
class NotificationChecks(private val test: Instrumentation) {
    private val c = test.targetContext
    private val manager = c.getSystemService(NotificationManager::class.java)
    private fun await(label: String, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) { if (check()) return; Thread.sleep(80) }
        error("Timed out: $label")
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun click(a: MainActivity, label: String) = test.runOnMainSync {
        var v: View = views(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == label }
        while (!v.isClickable) v = v.parent as View
        check(v.performClick())
    }
    private fun texts(a: MainActivity): List<String> {
        var result = emptyList<String>()
        test.runOnMainSync { result = views(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() } }
        return result
    }
    private fun screenshot(a: MainActivity, name: String) {
        test.waitForIdleSync()
        val frame = java.util.concurrent.CountDownLatch(1)
        test.runOnMainSync { a.window.decorView.postDelayed({ frame.countDown() }, 350) }
        check(frame.await(3, java.util.concurrent.TimeUnit.SECONDS))
        // Capture the composed emulator display, rather than drawing views before their next layout.
        val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
        File(c.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    fun run(denied: Boolean) {
        check(c.packageName == "io.leoagent.app.verification")
        check(Prefs.configured(c))
        check(taskNotificationsEnabled(c) != denied) { "Driver must explicitly grant/revoke notification permission" }
        val conn = Cx.connection(c)
        val store = MessageStore(c, conn)
        val saved = SecureStore.read(c, "restart_verification")!!
        val metadata = JSONObject(Cx.req(c, "GET", "/sessions/${saved.getString("sid")}").second)
        val session = Cx.req(c, "POST", "/sessions", JSONObject().put("project", metadata.getInt("project_id")))
        check(session.first == 200)
        val sid = JSONObject(session.second).getString("id")
        val staleConnection = AgentRepository.command(c, sid, "prompt", "/sessions/$sid/tasks", JSONObject().put("text", "must never dispatch"),
            expectedConnection = Cx.Connection("http://127.0.0.1:1", "wrong-connection"))
        check(!staleConnection.accepted && !staleConnection.unknown) { "Stale connection was allowed to dispatch" }
        if (denied) check(store.cursor > 0 && store.seen > 0) { "Delivery/read state was lost after process recreation" }
        MessageStore.prepare(c)
        val baseline = store.cursor
        check(baseline > 0)
        val a = test.startActivitySync(Intent(c, MainActivity::class.java).putExtra("tab", "agent").putExtra("sid", sid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        test.waitForIdleSync()
        await("visible conversation") { CodexPane.visibleSession == sid }
        // Navigation uses a programmatic BottomNavigationView, so locate the real instance.
        var bottom: BottomNavigationView? = null
        test.runOnMainSync {
            bottom = views(a.window.decorView).filterIsInstance<BottomNavigationView>().single()
            if (denied) { CodexService.start(a); bottom!!.selectedItemId = R.id.n_set }
        }
        if (denied) await("hidden conversation clears foreground suppression") { CodexPane.visibleSession == null }
        fun submit(text: String): String {
            val result = AgentRepository.command(c, sid, "prompt", "/sessions/$sid/tasks", JSONObject().put("text", text))
            check(result.accepted) { result.message }
            return result.body.getString("task_id")
        }
        fun finished(id: String) = JSONObject(Cx.req(c, "GET", "/tasks/$id", v1 = true).second).optString("state") == "succeeded"
        val task = submit("slow Android notification background ${if (denied) "denied" else "allowed"}")
        if (!denied) {
            await("conversation observes its running task") { texts(a).contains("Agent 正在执行") }
            val otherSession = JSONObject(Cx.req(c, "POST", "/sessions", JSONObject().put("project", metadata.getInt("project_id"))).second).getString("id")
            test.runOnMainSync { a.startActivity(Intent(c, MainActivity::class.java).putExtra("tab", "agent").putExtra("sid", otherSession).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            await("switching conversations retains background monitoring") { CodexPane.visibleSession == otherSession }
        }
        await("background task completes") { finished(task) }
        await("cursor advanced after background completion") { store.cursor > baseline }
        if (denied) {
            check(manager.activeNotifications.none { it.id == 5 }) { "Denied permission still posted a task notification" }
            test.runOnMainSync { bottom!!.selectedItemId = R.id.n_msg }
            await("denied notification remains in inbox") { texts(a).any { it.contains("notification background denied") } }
            check(texts(a).contains("开启任务通知"))
            screenshot(a, "notifications-denied")
            test.runOnMainSync { a.finish() }
            return
        }
        await("system notification actually posted") { manager.activeNotifications.any { it.id == 5 } }
        val alert = manager.activeNotifications.single { it.id == 5 }
        check(alert.notification.visibility == android.app.Notification.VISIBILITY_PRIVATE)
        alert.notification.contentIntent.send()
        await("notification tap opens the matching conversation") { CodexPane.visibleSession == sid }
        manager.cancel(alert.tag, alert.id)
        val beforeForeground = store.cursor
        val foregroundTask = submit("slow Android notification foreground")
        await("foreground task completes") { finished(foregroundTask) }
        await("visible completion still acknowledges delivery") { store.cursor > beforeForeground }
        check(manager.activeNotifications.none { it.id == 5 }) { "Visible conversation incorrectly alerted" }
        test.runOnMainSync { bottom!!.selectedItemId = R.id.n_msg }
        await("inbox loads") { texts(a).contains("全部标为已读") && texts(a).contains("查看更早消息") }
        check(store.seen == 0L) { "Opening the inbox silently cleared unread messages" }
        screenshot(a, "notifications-inbox")
        click(a, "查看更早消息")
        await("second history page") { texts(a).count { it.startsWith("✓ Burst") } > 50 }
        click(a, "全部标为已读")
        val high = JSONObject(Cx.req(c, "GET", "/notes/count?after=0").second).getLong("last")
        await("explicit mark-all persisted") { store.seen == high }
        // A different computer must never inherit this computer's read/delivery state.
        val other = MessageStore(c, Cx.Connection("http://127.0.0.1:1", "fixture-other"))
        check(other.seen == 0L && other.cursor == -1L)
        // Restore epoch resets read and delivery cursors; use a separate namespace for this assertion.
        other.sync(JSONObject().put("data_epoch", "before")); other.markAll(99); other.delivered(99)
        check(other.sync(JSONObject().put("data_epoch", "after")))
        check(other.seen == 0L && other.cursor == -1L)
        test.runOnMainSync { a.finish() }
    }
}
