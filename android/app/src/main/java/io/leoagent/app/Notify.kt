package io.leoagent.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

const val CH_FG = "leo_fg"

fun ensureChannels(c: Context) {
    c.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(CH_FG, "后台运行(常驻)", NotificationManager.IMPORTANCE_MIN))
}
