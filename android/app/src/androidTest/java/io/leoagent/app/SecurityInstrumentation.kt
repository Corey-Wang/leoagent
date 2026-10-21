package io.leoagent.app

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import org.json.JSONObject
import java.io.File

/** Runs only against the explicitly isolated verification app; no access to the normal app's data. */
class SecurityInstrumentation : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    override fun onStart() {
        val output = Bundle()
        try {
            val c = targetContext
            check(c.packageName == "io.leoagent.app.verification") { "Use -Pleoagent.verification, never the regular app" }
            if (args.getString("scenario") in setOf("toolbar-touch", "toolbar-touch-reproduce")) {
                ToolbarTouchChecks(this).run(args.getString("scenario") == "toolbar-touch-reproduce")
                finish(-1, Bundle().apply { putString("stream", "PASS: ${args.getString("scenario")} touchscreen first tap and keyboard behavior\n") })
                return
            }
            if (args.getString("scenario") in setOf("notifications", "notifications-denied")) {
                NotificationChecks(this).run(args.getString("scenario") == "notifications-denied")
                finish(-1, Bundle().apply { putString("stream", "PASS: ${args.getString("scenario")} native inbox, background service, delivery/read cursors and notification behavior\n") })
                return
            }
            if (args.getString("scenario") == "markdown-theme") {
                var renderingFailure: Throwable? = null
                runOnMainSync { try { MarkdownRenderingChecks.run(c) } catch (e: Throwable) { renderingFailure = e } }
                renderingFailure?.let { throw it }
                finish(-1, Bundle().apply { putString("stream", "PASS: readable Markdown tables and code in light/dark themes; rendered PNGs saved in cache\n") })
                return
            }
            if (args.getString("scenario") == "legacy-link") {
                val base = "http://127.0.0.1:1"
                check(c.getSharedPreferences("leoagent", 0).edit().putString("url", base).putString("token", "legacy-fixture").commit())
                check(Prefs.token(c) == "legacy-fixture")
                verifyLegacyLink(c, base)
                finish(-1, Bundle().apply { putString("stream", "PASS: legacy upgrade compatibility\n") })
                return
            }
            if (args.getString("scenario") in setOf("lose-send", "recover-send")) {
                verifyLostSend(c)
                finish(-1, Bundle().apply { putString("stream", "PASS: ${args.getString("scenario")} with original durable operation\n") })
                return
            }
            if (args.getString("scenario") == "stale-approval") {
                verifyStaleApproval(c)
                finish(-1, Bundle().apply { putString("stream", "PASS: second device answered, Android rejected receipt clears pending operation\n") })
                return
            }
            val value = JSONObject().put("token", "fixture-secret-never-plaintext")
            SecureStore.write(c, "roundtrip", value)
            check(SecureStore.read(c, "roundtrip")!!.getString("token") == value.getString("token"))
            val encrypted = File(c.noBackupFilesDir, "roundtrip.enc")
            check(!encrypted.readText().contains(value.getString("token")))
            val envelope = JSONObject(encrypted.readText())
            envelope.put("data", "AAAAAAAAAAAAAAAAAAAAAAAA")
            encrypted.writeText(envelope.toString())
            check(runCatching { SecureStore.read(c, "roundtrip") }.isFailure) { "Tampering must fail closed" }
            val base = args.getString("base_url") ?: "http://127.0.0.1:1"
            if (SecureStore.read(c, "restart_verification") == null) {
                // Simulate upgrading the old app's plaintext preferences in place.
                check(c.getSharedPreferences("leoagent", 0).edit().putString("url", base).putString("token", "legacy-fixture").commit())
                check(Prefs.token(c) == "legacy-fixture")
            }
            check(!c.getSharedPreferences("leoagent", 0).contains("token"))
            check(Prefs.parsePair("leoagent://pair?url=https%3A%2F%2Fexample.com&token=unsafe") == null)
            check(Prefs.parseLegacyPair("leoagent://pair?url=https%3A%2F%2Fexample.com&token=legacy-fixture") == ("https://example.com" to "legacy-fixture"))
            check(Prefs.parseLegacyPair("leoagent://pair?url=https%3A%2F%2Fexample.com&token=x&code=invalid") == null)
            check(Prefs.parseLegacyPair("leoagent://other?url=https%3A%2F%2Fexample.com&token=x") == null)
            check(Prefs.parseLegacyPair("leoagent://pair?url=https%3A%2F%2Fuser%40example.com&token=x") == null)
            check(Prefs.parsePair("leoagent://pair?url=https%3A%2F%2Fexample.com&code=" + "a".repeat(64)) != null)
            args.getString("pair_code")?.let { invitation ->
                fun post(path: String, body: JSONObject): JSONObject {
                    val r = Http.call("$base/api/v1/pairing/$path", "POST", body = body.toString().toByteArray())
                    check(r.first == 200) { "$path HTTP ${r.first}" }
                    return JSONObject(r.second)
                }
                val claim = post("claim", JSONObject().put("code", invitation).put("device_name", "isolated Android verification"))
                sendStatus(1, Bundle().apply { putString("stream", "PAIRING_PENDING ${claim.getString("verification")}\n") })
                val poll = JSONObject().put("pairing_id", claim.getString("pairing_id")).put("poll_secret", claim.getString("poll_secret"))
                val end = android.os.SystemClock.elapsedRealtime() + 30000
                var result: JSONObject? = null
                while (android.os.SystemClock.elapsedRealtime() < end) {
                    val candidate = post("poll", poll)
                    if (candidate.optString("state") == "paired") { result = candidate; break }
                    Thread.sleep(200)
                }
                check(result != null) { "Local test driver did not confirm pairing" }
                check(Prefs.save(c, base, result!!.getString("token")))
                val health = Cx.syncIdentity(c)
                check(health.first == 200)
                val project = JSONObject(Cx.req(c, "POST", "/projects", JSONObject().put("path", args.getString("project_path"))).second)
                val session = JSONObject(Cx.req(c, "POST", "/sessions", JSONObject().put("project", project.getInt("id"))).second)
                val sid = session.getString("id")
                val sent = AgentRepository.command(c, sid, "prompt", "/sessions/$sid/tasks", JSONObject().put("text", "Android fixture"))
                check(sent.accepted && !sent.unknown) { sent.message }
                val snap = AgentRepository.snapshot(c, sid)
                check(snap.first == 200)
                check(JSONObject(snap.second).getJSONObject("task").getString("id") == sent.body.getString("task_id"))
                check(AgentRepository.recover(c, sid).isEmpty())
                val deadline = android.os.SystemClock.elapsedRealtime() + 10000
                var finished = JSONObject(snap.second).getJSONObject("task")
                while (finished.optString("state") !in setOf("succeeded", "failed", "unknown", "cancelled") &&
                    android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(100)
                    val next = AgentRepository.snapshot(c, sid)
                    check(next.first == 200)
                    finished = JSONObject(next.second).getJSONObject("task")
                }
                check(finished.optString("state") == "succeeded") { "Fixture task did not finish: $finished" }
                SecureStore.write(c, "restart_verification", JSONObject().put("sid", sid).put("token", result!!.getString("token")))
                output.putString("stream", "PASS: real Android Keystore, encrypted credential storage, local pairing and HTTP task submission\n")
            } ?: run {
                val saved = SecureStore.read(c, "restart_verification")
                if (saved != null) {
                    check(Prefs.url(c) == (args.getString("base_url") ?: "")) { "saved URL was not restored" }
                    check(SecureStore.digest(Prefs.token(c)) == SecureStore.digest(saved.getString("token"))) { "saved credential was not restored" }
                    check(Cx.syncIdentity(c).first == 200)
                    check(AgentRepository.snapshot(c, saved.getString("sid")).first == 200)
                }
                output.putString("stream", "PASS: Android Keystore round-trip, tamper rejection and saved credentials after process recreation\n")
            }
            finish(-1, output)
        } catch (e: Throwable) {
            output.putString("stream", "FAIL: ${android.util.Log.getStackTraceString(e)}")
            finish(0, output)
        }
    }

    private fun verifyLegacyLink(c: Context, base: String) {
        val link = android.net.Uri.Builder().scheme("leoagent").authority("pair")
            .appendQueryParameter("url", "$base/").appendQueryParameter("token", "legacy-qr-fixture").build()
        val activity = startActivitySync(android.content.Intent(c, SettingsActivity::class.java)
            .setData(link).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForIdleSync()
        runOnMainSync {
            fun descendants(v: android.view.View): List<android.view.View> = listOf(v) +
                if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
            val views = descendants(activity.window.decorView)
            val fields = views.filterIsInstance<android.widget.EditText>()
            check(fields.map { it.text.toString() } == listOf(base, "legacy-qr-fixture")) { "Old QR did not fill connection fields" }
            check(views.filterIsInstance<android.widget.TextView>().single { it.text.toString() == "保存" }.performClick())
        }
        waitForIdleSync()
        check(Prefs.url(c) == base && Prefs.token(c) == "legacy-qr-fixture")
        check(!c.getSharedPreferences("leoagent", 0).contains("token"))
        check(!File(c.noBackupFilesDir, "credential_${SecureStore.digest(base)}.enc").readText().contains("legacy-qr-fixture"))
        sendStatus(1, Bundle().apply { putString("stream", "PASS: old credential migration and legacy QR settings/save retain encrypted credentials\n") })
    }

    private fun pending(c: Context) =
        SecureStore.read(c, "operations_" + SecureStore.digest(Prefs.url(c))) ?: JSONObject()

    private fun awaitSucceeded(c: Context, sid: String, task: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val result = AgentRepository.snapshot(c, sid)
            check(result.first == 200)
            val snapshot = JSONObject(result.second)
            val current = snapshot.getJSONObject("task")
            check(current.getString("id") == task)
            if (current.optString("state") == "succeeded") {
                check(snapshot.getJSONArray("events").toString().contains("fixture: slow Android fault-"))
                return
            }
            check(current.optString("state") !in setOf("failed", "unknown", "cancelled", "interrupted")) { "$current" }
            Thread.sleep(100)
        }
        error("Recovered task did not succeed")
    }

    private fun verifyLostSend(c: Context) {
        check(Prefs.url(c) == args.getString("base_url"))
        check(Cx.syncIdentity(c).first == 200)
        if (args.getString("scenario") == "lose-send") {
            check(pending(c).length() == 0)
            val sid = SecureStore.read(c, "restart_verification")!!.getString("sid")
            val text = "slow Android fault-" + args.getString("fault")
            val sent = AgentRepository.command(c, sid, "prompt", "/sessions/$sid/tasks", JSONObject().put("text", text))
            check(sent.unknown && !sent.accepted) { "Lost response was incorrectly reported as accepted: ${sent.body}" }
            val stored = pending(c)
            check(stored.length() == 1) { "Ambiguous send did not retain exactly one durable operation" }
            val id = stored.keys().next()
            val original = stored.getJSONObject(id)
            check(JSONObject(original.getString("body")).getString("text") == text)
            // Even a subsequent unavailable/404 receipt must retain the original operation.
            val recovered = AgentRepository.recover(c, sid)
            check(recovered.size == 1 && recovered[0].unknown)
            check(pending(c).getJSONObject(id).toString() == original.toString())
            SecureStore.write(c, "fault_verification", JSONObject().put("sid", sid).put("id", id)
                .put("original", original).put("pid", android.os.Process.myPid()))
        } else {
            val saved = SecureStore.read(c, "fault_verification")!!
            check(saved.getInt("pid") != android.os.Process.myPid()) { "App process was not recreated" }
            val id = saved.getString("id")
            val sid = saved.getString("sid")
            check(pending(c).getJSONObject(id).toString() == saved.getJSONObject("original").toString())
            val recovered = AgentRepository.recover(c, sid)
            check(recovered.size == 1 && recovered[0].accepted && !recovered[0].unknown) { "$recovered" }
            check(recovered[0].body.getString("operation_id") == id)
            check(pending(c).length() == 0) { "Confirmed receipt was not removed" }
            awaitSucceeded(c, sid, recovered[0].body.getString("task_id"))
            check(AgentRepository.recover(c, sid).isEmpty())
        }
    }

    private fun verifyStaleApproval(c: Context) {
        check(pending(c).length() == 0)
        val sid = SecureStore.read(c, "restart_verification")!!.getString("sid")
        val sent = AgentRepository.command(c, sid, "prompt", "/sessions/$sid/tasks",
            JSONObject().put("text", "approval Android stale"))
        check(sent.accepted)
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        var request: String? = null
        var handled = false
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val response = AgentRepository.snapshot(c, sid)
            check(response.first == 200)
            val snapshot = JSONObject(response.second)
            val interactions = snapshot.getJSONArray("interactions")
            if (request == null && interactions.length() > 0) {
                request = interactions.getJSONObject(0).getString("id")
                sendStatus(1, Bundle().apply { putString("stream", "WAITING_SECOND_DEVICE\n") })
            }
            if (request != null && interactions.length() == 0) {
                handled = true
                break
            }
            Thread.sleep(100)
        }
        check(handled && request != null) { "Other device did not settle the approval" }
        val response = AgentRepository.command(c, sid, "interaction", "/interactions/$request/respond",
            JSONObject().put("option", "allow"))
        check(response.code == 409 && !response.unknown && !response.accepted)
        check(response.body.getString("status") == "rejected")
        check(response.body.getString("code") == "interaction_already_handled")
        check(pending(c).length() == 0) { "Definitively rejected stale response remained pending" }
        check(AgentRepository.recover(c, sid).isEmpty())
        val taskEnd = android.os.SystemClock.elapsedRealtime() + 5000
        while (android.os.SystemClock.elapsedRealtime() < taskEnd) {
            val snapshot = JSONObject(AgentRepository.snapshot(c, sid).second)
            check(snapshot.getJSONArray("interactions").length() == 0)
            if (snapshot.getJSONObject("task").optString("state") == "succeeded") return
            Thread.sleep(100)
        }
        error("Other device's approval did not finish the fixture task")
    }
}
