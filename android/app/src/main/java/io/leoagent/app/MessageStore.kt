package io.leoagent.app

import android.content.Context
import org.json.JSONObject

/** Device-local read/delivery cursors, pinned to a computer and its restored-data epoch. */
class MessageStore(c: Context, val connection: Cx.Connection) {
    private val prefs = c.getSharedPreferences("messages_" + SecureStore.digest(connection.base), Context.MODE_PRIVATE)
    val epoch: String get() = prefs.getString("epoch", "") ?: ""
    val cursor: Long get() = prefs.getLong("cursor", -1)
    val seen: Long get() = prefs.getLong("seen", 0)
    @Synchronized fun sync(o: JSONObject, highWater: Boolean = false): Boolean {
        val next = o.optString("data_epoch")
        val changed = o.optBoolean("reset") || next.isNotEmpty() && epoch.isNotEmpty() && epoch != next
        val rewound = highWater && o.has("last") && o.optLong("last") < seen
        val edit = prefs.edit()
        if (changed || rewound) edit.putLong("seen", 0).putLong("cursor", -1)
        if (next.isNotEmpty()) edit.putString("epoch", next)
        check(edit.commit())
        return changed || rewound
    }
    fun delivered(seq: Long) { check(prefs.edit().putLong("cursor", seq).commit()) }
    fun markAll(seq: Long) { check(prefs.edit().putLong("seen", maxOf(seen, seq)).commit()) }
    companion object {
        /** Called in the foreground before any task is submitted. No historical alerts on upgrade. */
        @Synchronized fun prepare(c: Context, conn: Cx.Connection = Cx.connection(c)) {
            val store = MessageStore(c, conn)
            if (store.cursor >= 0) return
            val r = Cx.req(c, "GET", "/notify?bootstrap=1", readTimeout = 4000, connection = conn)
            if (r.first == 200 && conn.isCurrent(c)) {
                val o = JSONObject(r.second)
                store.sync(o)
                if (store.cursor < 0) store.delivered(o.getLong("last"))
            }
        }
    }
}
