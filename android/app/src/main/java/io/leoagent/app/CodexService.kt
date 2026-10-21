package io.leoagent.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject

const val CH_CODEX = "codex_done"
fun ensureCodexChannel(c: Context) {
    c.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CH_CODEX, "任务与交互提醒", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
}
fun taskNotificationsEnabled(c: Context): Boolean {
    ensureCodexChannel(c)
    val manager = c.getSystemService(NotificationManager::class.java)
    return manager.areNotificationsEnabled() && manager.getNotificationChannel(CH_CODEX)?.importance != NotificationManager.IMPORTANCE_NONE
}

/** Best-effort foreground polling while tasks are active; the inbox remains the durable source. */
class CodexService : Service() {
    companion object {
        fun start(c: Context) { try { c.startForegroundService(Intent(c, CodexService::class.java)) }
            catch (e: Exception) { AppLog.e("svc", "后台提醒启动失败: ${e.javaClass.simpleName}") } }
    }
    @Volatile private var alive = true
    private var worker: Thread? = null
    private var latestStart = 0
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        ensureChannels(this); ensureCodexChannel(this)
        val n = progress("任务正在后台运行", "完成、审批或提问会提醒；消息页可补查")
        if (Build.VERSION.SDK_INT >= 29) startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(3, n)
        if (worker == null) launch(startId)
        return START_NOT_STICKY
    }
    private fun launch(startId: Int) {
        worker = Thread {
            try { loop() } catch (_: InterruptedException) { /* service stopped */ }
            catch (e: Exception) { AppLog.e("svc", "提醒暂停: ${e.javaClass.simpleName}") }
            finally { Handler(Looper.getMainLooper()).post {
                worker = null
                if (alive) { if (latestStart != startId) launch(latestStart) else stopSelf(startId) }
            } }
        }.apply { name = "leoagent-notifications"; start() }
    }
    private fun open(sid: String?, conn: Cx.Connection): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).setData(Uri.parse("leoagent://notification/" + SecureStore.digest(conn.base) + "/" + Uri.encode(sid ?: "")))
            .putExtra("tab", "agent").putExtra("sid", sid).putExtra("server", conn.base)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun progress(title: String, text: String) = Notification.Builder(this, CH_FG)
        .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle(title).setContentText(text)
        .setContentIntent(open(null, Cx.connection(this))).setOngoing(true).setOnlyAlertOnce(true).build()
    private fun loop() {
        val conn = Cx.connection(this)
        val store = MessageStore(this, conn)
        var last = maxOf(store.cursor, 0); var idle = 0; var errors = 0
        val start = SystemClock.elapsedRealtime()
        while (alive && conn.isCurrent(this) && SystemClock.elapsedRealtime() - start < 6 * 3600_000L) {
            try {
                val r = Cx.req(this, "GET", "/notify?cursor=1&since=$last&wait=25&epoch=${Uri.encode(store.epoch)}", readTimeout = 40000, connection = conn)
                if (!alive || !conn.isCurrent(this)) break
                if (r.first in setOf(401, 403)) { AppLog.e("svc", "提醒连接授权失效，请重新配对"); break }
                check(r.first == 200) { "HTTP ${r.first}" }
                val o = JSONObject(r.second)
                // Old computers still use since=0 as bootstrap. Recover their first page via
                // the durable inbox instead of silently accepting a skipped first alert.
                if (!o.has("data_epoch") && last == 0L && o.optLong("last") > 0L) {
                    var before = o.getLong("last") + 1
                    var earliest = org.json.JSONArray()
                    while (alive && conn.isCurrent(this)) {
                        val page = Cx.req(this, "GET", "/notes?since=0&before=$before&limit=50", connection = conn)
                        check(page.first == 200) { "HTTP ${page.first}" }
                        val rows = org.json.JSONArray(page.second)
                        if (rows.length() == 0) break
                        earliest = rows
                        before = (0 until rows.length()).minOf { rows.getJSONObject(it).getLong("seq") }
                        if (rows.length() < 50) break
                    }
                    val ordered = (0 until earliest.length()).map { earliest.getJSONObject(it) }.sortedBy { it.getLong("seq") }
                    o.put("items", org.json.JSONArray(ordered)).put("last", ordered.lastOrNull()?.getLong("seq") ?: last)
                }
                val items = o.getJSONArray("items")
                store.sync(o)
                for (i in 0 until items.length()) {
                    if (!alive || !conn.isCurrent(this)) return
                    val note = items.getJSONObject(i); val sid = note.optString("sid")
                    if (CodexPane.visibleSession == sid || !taskNotificationsEnabled(this)) continue
                    val interaction = note.optString("kind") in setOf("perm", "ask")
                    val n = Notification.Builder(this, CH_CODEX).setSmallIcon(android.R.drawable.stat_notify_chat)
                        .setContentTitle(note.optString("title")).setContentText(note.optString("text"))
                        .setStyle(Notification.BigTextStyle().bigText(note.optString("text")))
                        .setVisibility(Notification.VISIBILITY_PRIVATE)
                        .setAutoCancel(true).setContentIntent(open(sid, conn))
                        .setCategory(if (interaction) Notification.CATEGORY_REMINDER else Notification.CATEGORY_MESSAGE).build()
                    // A string tag avoids truncated session hashes and scopes alerts to a computer.
                    try { getSystemService(NotificationManager::class.java).notify("${SecureStore.digest(conn.base)}:$sid", 5, n) }
                    catch (_: SecurityException) { /* permission changed during delivery; inbox keeps the message */ }
                }
                last = o.getLong("last"); store.delivered(last)
                if (errors > 0) getSystemService(NotificationManager::class.java).notify(3, progress("后台提醒已连接", "完成、审批或提问会提醒"))
                errors = 0
                if (!o.optBoolean("running") && items.length() == 0) { if (++idle >= 2) break } else idle = 0
            } catch (e: InterruptedException) { throw e }
            catch (e: Exception) {
                AppLog.e("svc", "提醒重连: ${e.javaClass.simpleName}")
                if (++errors > 60) break
                if (alive && conn.isCurrent(this)) getSystemService(NotificationManager::class.java)
                    .notify(3, progress("后台提醒正在重连", "网络恢复后自动补取，任务状态以电脑为准"))
                Thread.sleep(minOf(30_000L, 1000L * (1L shl minOf(errors, 5))))
            }
        }
    }
    override fun onDestroy() { alive = false; worker?.interrupt(); super.onDestroy() }
}
