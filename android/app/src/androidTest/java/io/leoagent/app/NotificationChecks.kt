package io.leoagent.app

import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
        val notificationTag = "${SecureStore.digest(conn.base)}:$sid"
        val staleConnection = AgentRepository.command(c, sid, "prompt", "/sessions/$sid/tasks", JSONObject().put("text", "must never dispatch"),
            expectedConnection = Cx.Connection("http://127.0.0.1:1", "wrong-connection"))
        check(!staleConnection.accepted && !staleConnection.unknown) { "Stale connection was allowed to dispatch" }
        if (denied) check(store.cursor > 0) { "Delivery cursor was lost after process recreation" }
        MessageStore.prepare(c)
        val baseline = store.cursor
        check(baseline > 0)
        val a = test.startActivitySync(Intent(c, MainActivity::class.java).putExtra("tab", "agent").putExtra("sid", sid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        test.waitForIdleSync()
        await("visible conversation") { CodexPane.visibleSession == sid }
        if (denied) test.runOnMainSync { CodexService.start(a); a.showSettings() }
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
            check(texts(a).contains("任务通知") && texts(a).contains("未开启"))
            screenshot(a, "notifications-denied")
            test.runOnMainSync { a.finish() }
            return
        }
        await("system notification actually posted") { manager.activeNotifications.any { it.id == 5 && it.tag == notificationTag } }
        val alert = manager.activeNotifications.single { it.id == 5 && it.tag == notificationTag }
        check(alert.notification.visibility == android.app.Notification.VISIBILITY_PRIVATE)
        test.runOnMainSync { a.showSettings() }
        await("settings hide conversation before notification tap") { CodexPane.visibleSession == null }
        alert.notification.contentIntent.send()
        await("notification tap opens the matching conversation") { CodexPane.visibleSession == sid }
        manager.cancel(alert.tag, alert.id)
        val beforeForeground = store.cursor
        val foregroundTask = submit("slow Android notification foreground")
        await("foreground task completes") { finished(foregroundTask) }
        await("visible completion still acknowledges delivery") { store.cursor > beforeForeground }
        // Other conversations can still complete while this conversation is visible.
        check(manager.activeNotifications.none { it.id == 5 && it.tag == notificationTag }) {
            "Visible conversation incorrectly alerted: " + manager.activeNotifications.map { it.tag to it.notification.extras.getCharSequence("android.text") }
        }
        screenshot(a, "notifications-conversation")
        // Delivery state remains scoped to its computer and is reset after a data restore.
        val other = MessageStore(c, Cx.Connection("http://127.0.0.1:1", "fixture-other"))
        check(other.cursor == -1L)
        other.sync(JSONObject().put("data_epoch", "before")); other.delivered(99)
        check(other.sync(JSONObject().put("data_epoch", "after")))
        check(other.cursor == -1L)
        test.runOnMainSync { a.finish() }
    }
}
