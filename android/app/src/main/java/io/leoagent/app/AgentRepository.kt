package io.leoagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stable operation IDs survive network loss and process recreation. Never persist a bearer token here. */
object AgentRepository {
    data class Result(val code: Int, val body: JSONObject, val unknown: Boolean = false) {
        val accepted: Boolean get() = code in 200..299 && !unknown
        val message: String get() = body.optString("error", body.optString("message", "HTTP $code"))
    }
    private data class Connection(val base: String, val token: String) {
        val credential = SecureStore.digest(token)
        val store = "operations_" + SecureStore.digest(base)
    }
    private fun connection(c: Context) = Connection(Prefs.url(c), Prefs.token(c))
    private fun json(text: String) = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
    private fun failure(message: String, unknown: Boolean = false) =
        Result(0, JSONObject().put("error", message), unknown)

    private fun request(conn: Connection, method: String, path: String, body: String? = null,
                        operation: String? = null, epoch: String? = null): Pair<Int, String> = try {
        val headers = mutableMapOf("X-Token" to conn.token, "User-Agent" to "leoagent-android")
        operation?.let { headers["Idempotency-Key"] = it }
        epoch?.let { headers["X-Data-Epoch"] = it }
        Http.call(conn.base + "/api/v1" + path, method, headers, body?.toByteArray(Charsets.UTF_8),
            readTimeoutMs = if (method == "POST") 60000 else 20000)
    } catch (_: Exception) { Pair(0, "") }

    private fun health(conn: Connection): JSONObject? {
        val (code, text) = request(conn, "GET", "/health")
        if (code != 200) return null
        val info = json(text)
        if (info.optInt("api_version") != 1 || info.optJSONObject("features")?.optBoolean("idempotent_prompts") != true) return null
        return info.takeIf { it.optString("data_epoch").isNotEmpty() && it.optString("server_id").isNotEmpty() }
    }

    @Synchronized private fun commands(c: Context, conn: Connection): JSONObject =
        SecureStore.read(c, conn.store) ?: JSONObject()

    @Synchronized private fun remove(c: Context, conn: Connection, id: String) {
        val all = commands(c, conn)
        all.remove(id)
        SecureStore.write(c, conn.store, all)
    }

    @Synchronized private fun prepare(c: Context, conn: Connection, sid: String, kind: String,
                                      path: String, body: JSONObject, info: JSONObject): JSONObject {
        val all = commands(c, conn)
        for (id in all.keys()) {
            val prior = all.getJSONObject(id)
            if (prior.optString("session_id") == sid &&
                (kind == "prompt" && prior.optString("kind") == "prompt" || prior.optString("path") == path)) {
                check(prior.optString("credential") == conn.credential) { "连接凭据已改变，请先在电脑上核对之前未确认的操作" }
                check(prior.optString("body") == body.toString()) { "存在参数不同的未确认操作，请先核对，不能作为新任务重发" }
                return prior
            }
        }
        val entry = JSONObject().put("operation_id", UUID.randomUUID().toString())
            .put("session_id", sid).put("kind", kind).put("path", path).put("body", body.toString())
            .put("server_id", info.getString("server_id")).put("data_epoch", info.getString("data_epoch"))
            .put("credential", conn.credential).put("created_at", System.currentTimeMillis())
        all.put(entry.getString("operation_id"), entry)
        // A failed durable write aborts before any command is sent.
        SecureStore.write(c, conn.store, all)
        return entry
    }

    private fun receiptResult(c: Context, conn: Connection, entry: JSONObject, code: Int, body: JSONObject): Result {
        if (body.optString("status") == "unknown") return Result(code, body, true)
        remove(c, conn, entry.getString("operation_id"))
        return Result(code, body)
    }

    private fun lookup(c: Context, conn: Connection, entry: JSONObject): Result? {
        val id = entry.getString("operation_id")
        val (code, text) = request(conn, "GET", "/operations/$id")
        val body = json(text)
        if (code == 200 && body.optString("operation_id") == id) {
            return receiptResult(c, conn, entry, body.optInt("http_status", 202), body)
        }
        return null
    }

    private fun transmit(c: Context, conn: Connection, entry: JSONObject): Result {
        val id = entry.getString("operation_id")
        val (code, text) = request(conn, "POST", entry.getString("path"), entry.getString("body"), id, entry.getString("data_epoch"))
        val body = json(text)
        if (code in 200..299 && body.optString("operation_id") == id) {
            return receiptResult(c, conn, entry, code, body)
        }
        lookup(c, conn, entry)?.let { return it }
        // Only a durable receipt proves this original operation was rejected.
        // A later 401/404 cannot prove an earlier ambiguous send was not accepted.
        if (code in 400..499 && body.optString("operation_id") == id &&
            body.optString("status") == "rejected") {
            remove(c, conn, id)
            return Result(code, body)
        }
        return failure("请求结果尚未确认，操作记录已保留。重新连接后会查询原回执，请勿另发相同任务。", true)
    }

    fun command(c: Context, sid: String, kind: String, path: String, body: JSONObject, expectedConnection: Cx.Connection? = null): Result {
        return try {
            val conn = connection(c)
            if (expectedConnection != null && (conn.base != expectedConnection.base || conn.token != expectedConnection.token)) return failure("连接已切换，本次操作未发送")
            val info = health(conn) ?: return failure("无法确认电脑连接或协议版本，请检查连接并升级电脑端")
            if (conn == connection(c)) CxPrefs.setServerIdentity(c, info.getString("data_epoch"), info.optString("server_generation"))
            val entry = prepare(c, conn, sid, kind, path, body, info)
            if (entry.getString("data_epoch") != info.getString("data_epoch") || entry.getString("server_id") != info.getString("server_id")) {
                failure("电脑数据或身份已改变，旧操作结果需要人工核对，未重新发送。", true)
            } else transmit(c, conn, entry)
        } catch (e: Exception) {
            failure(e.message ?: "无法安全保存操作记录，请先核对电脑任务", true)
        }
    }

    /** Called on foreground/reconnect. A retry always uses the original ID, body and data epoch. */
    fun recover(c: Context, sid: String): List<Result> = try {
        val conn = connection(c)
        val pending = commands(c, conn)
        val entries = pending.keys().asSequence().map { pending.getJSONObject(it) }
            .filter { it.optString("session_id") == sid }.toList()
        if (entries.isEmpty()) emptyList() else {
            val info = health(conn)
            entries.map { entry ->
                when {
                    entry.optString("credential") != conn.credential -> failure("凭据已改变，旧操作需要人工核对", true)
                    info == null -> failure("尚未连上电脑，未确认操作仍保留", true)
                    entry.optString("data_epoch") != info.optString("data_epoch") ||
                        entry.optString("server_id") != info.optString("server_id") -> failure("电脑数据已恢复或重置，旧操作未重新发送", true)
                    else -> lookup(c, conn, entry) ?: transmit(c, conn, entry)
                }
            }
        }
    } catch (_: Exception) { listOf(failure("未确认操作记录暂不可读取，请先核对电脑任务", true)) }

    /** Only called after an explicit user review; this never retries or alters the computer task. */
    @Synchronized fun clearReviewedPending(c: Context, sid: String) {
        val conn = connection(c)
        val all = commands(c, conn)
        val keys = all.keys().asSequence().filter { all.getJSONObject(it).optString("session_id") == sid }.toList()
        keys.forEach { all.remove(it) }
        SecureStore.write(c, conn.store, all)
    }

    fun snapshot(c: Context, sid: String): Pair<Int, String> {
        val conn = connection(c)
        val (code, text) = request(conn, "GET", "/sessions/$sid/snapshot")
        if (code != 200) return Pair(code, text)
        val snapshot = json(text)
        if (conn != connection(c)) return Pair(0, "连接已切换，请重新加载对话")
        CxPrefs.setServerIdentity(c, snapshot.optString("data_epoch"), snapshot.optString("server_generation"))
        snapshot.put("last", snapshot.optLong("cursor"))
        val state = snapshot.optJSONObject("task")?.optString("state") ?: "idle"
        snapshot.put("task_state", state)
        snapshot.put("running", state in setOf("queued", "running", "waiting_approval", "waiting_input", "cancelling", "recovering"))
        val pending = JSONArray()
        snapshot.optJSONArray("interactions")?.let { interactions ->
            for (i in 0 until interactions.length()) {
                val interaction = interactions.getJSONObject(i)
                if (interaction.optString("kind") == "perm") pending.put(interaction.getString("id"))
            }
        }
        snapshot.put("pending", pending)
        return Pair(200, snapshot.toString())
    }
}
