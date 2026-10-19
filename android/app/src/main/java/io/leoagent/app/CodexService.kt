package io.leoagent.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONObject

const val CH_CODEX = "codex_done"
fun ensureCodexChannel(c: Context) {
    c.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CH_CODEX, "Codex 完成/审批", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
}

/** 有 Codex 任务在跑时常驻, 长轮询 /notify, 完成或需审批时弹通知; 空闲后自动停止 */
class CodexService : Service() {
    companion object {
        fun start(c: Context) { try { val i = Intent(c, CodexService::class.java); if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) } catch (_: Exception) {} }
    }
    @Volatile private var running = false
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this); ensureCodexChannel(this); AppLog.i("svc", "CodexService start running=$running")
        val n = Notification.Builder(this, CH_FG).setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("Codex 运行中")
            .setContentText("完成或需要审批时会通知你").setContentIntent(open(null)).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(3, n)
        if (!running) { running = true; Thread { loop(); stopSelf() }.start() }
        return START_NOT_STICKY
    }
    private fun open(sid: String?): PendingIntent = PendingIntent.getActivity(this, (sid ?: "").hashCode(),
        Intent(this, MainActivity::class.java).putExtra("tab", "agent").putExtra("sid", sid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun loop() {
        var last = CxPrefs.noteLast(this); var idle = 0; var errs = 0; val t0 = System.currentTimeMillis()
        if (last <= 0) Cx.req(this, "GET", "/notify?since=0").let { if (it.first == 200) { last = JSONObject(it.second).optInt("last"); CxPrefs.setNoteLast(this, last) } }
        while (running && System.currentTimeMillis() - t0 < 6 * 3600_000L) {
            val r = Cx.req(this, "GET", "/notify?since=$last&wait=25", readTimeout = 40000)
            if (r.first != 200) { if (++errs > 60) break; Thread.sleep(5000); continue }
            errs = 0
            val o = JSONObject(r.second); val items = o.getJSONArray("items")
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i); val sid = it.optString("sid")
                if (CodexPane.visibleSession == sid) continue
                val perm = it.optString("kind") == "perm" || it.optString("kind") == "ask"
                val nb = Notification.Builder(this, CH_CODEX).setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(it.optString("title")).setContentText(it.optString("text")).setStyle(Notification.BigTextStyle().bigText(it.optString("text")))
                    .setAutoCancel(true).setContentIntent(open(sid)).setCategory(if (perm) Notification.CATEGORY_ALARM else Notification.CATEGORY_MESSAGE)
                getSystemService(NotificationManager::class.java).notify(5000 + (sid.hashCode() and 0xffff), nb.build())
            }
            last = o.optInt("last", last); CxPrefs.setNoteLast(this, last)
            if (!o.optBoolean("running") && items.length() == 0) { if (++idle >= 2) break } else idle = 0
        }
    }
    override fun onDestroy() { AppLog.i("svc", "CodexService destroy"); running = false; super.onDestroy() }
}
