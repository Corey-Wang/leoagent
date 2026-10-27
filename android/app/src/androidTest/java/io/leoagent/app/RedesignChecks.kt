package io.leoagent.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import org.json.JSONObject
import java.io.File

/** Real touch -> native composer -> HTTP/SSE fixture -> approval -> persisted receipt. */
class RedesignChecks(private val test: Instrumentation) {
    private val c = test.targetContext
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun ui(block: () -> Boolean): Boolean { var result = false; test.runOnMainSync { result = block() }; return result }
    private fun await(label: String, block: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) { if (block()) return; Thread.sleep(80) }
        error("Timed out: $label")
    }
    private fun tap(v: View) {
        test.waitForIdleSync()
        // IME and drawer animations run outside the app main queue; wait before sampling coordinates.
        test.uiAutomation.waitForIdle(300, 3000)
        val xy = IntArray(2); var x = 0f; var y = 0f
        test.runOnMainSync {
            check(v.isShown && v.width > 0 && v.height >= UI.dp(v.context, 48)) { "Touch target is hidden or too small: ${v.contentDescription}" }
            v.getLocationOnScreen(xy); x = xy[0] + v.width / 2f; y = xy[1] + v.height / 2f
        }
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0).let {
                it.source = InputDevice.SOURCE_TOUCHSCREEN; test.sendPointerSync(it); it.recycle()
            }
        }
        test.waitForIdleSync()
    }
    private fun capture(name: String) {
        // Allow transient system toasts from preceding navigation checks to expire.
        test.waitForIdleSync(); Thread.sleep(2800)
        val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
        File(c.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun tapDialog(label: String) {
        test.uiAutomation.waitForIdle(300, 3000)
        val node = test.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(label).first { it.isClickable }
        val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
        check(bounds.height() >= UI.dp(c, 48))
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0).let {
                it.source = InputDevice.SOURCE_TOUCHSCREEN; test.sendPointerSync(it); it.recycle()
            }
        }
        test.waitForIdleSync()
    }
    fun run(dark: Boolean) {
        check(c.packageName == "io.leoagent.app.verification" && Prefs.configured(c))
        test.runOnMainSync { AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO) }
        val saved = SecureStore.read(c, "restart_verification")!!
        val metadata = JSONObject(Cx.req(c, "GET", "/sessions/${saved.getString("sid")}").second)
        val pid = metadata.getInt("project_id")
        val session = Cx.req(c, "POST", "/sessions", JSONObject().put("project", pid).put("mode", "ask"))
        check(session.first == 200)
        val sid = JSONObject(session.second).getString("id")
        CxPrefs.setProject(c, pid)
        c.getSharedPreferences("ui", 0).edit().putInt("top", 1).commit() // Upgrade from the removed message tab.
        var a = test.startActivitySync(Intent(c, MainActivity::class.java).putExtra("tab", "agent").putExtra("sid", sid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            await("conversation") { CodexPane.visibleSession == sid }
            // The legacy-link scenario deliberately queues save/invalid-host toasts.
            // Android can defer their display until our first foreground activity.
            Thread.sleep(8000)
            lateinit var pane: CodexPane; lateinit var input: EditText; lateinit var host: LinearLayout
            test.runOnMainSync {
                pane = MainActivity::class.java.getDeclaredField("pane").apply { isAccessible = true }.get(a) as CodexPane
                fun field(name: String) = CodexPane::class.java.getDeclaredField(name).apply { isAccessible = true }.get(pane)
                input = field("input") as EditText; host = field("approvalHost") as LinearLayout
                check(views(a.window.decorView).none { it is BottomNavigationView }) { "Bottom navigation still exists" }
                check(ColorUtils.calculateContrast(a.getColor(R.color.text), a.getColor(R.color.bg)) >= 4.5)
                check(ColorUtils.calculateContrast(a.getColor(R.color.muted), a.getColor(R.color.surface2)) >= 4.5)
                check(ColorUtils.calculateContrast(a.getColor(R.color.primary), a.getColor(R.color.bg)) >= 4.5)
                check(ColorUtils.calculateContrast(Color.WHITE, a.getColor(R.color.primaryFill)) >= 4.5)
                // Measure the actual shared row at 320dp with a long model name.
                val parent = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                val row = UI.row(a, parent, "桌面同步（实验）", "开 · 桌面端未运行，暂用 codex-acp") {}
                parent.measure(View.MeasureSpec.makeMeasureSpec(UI.dp(a, 320), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
                check(row.getChildAt(0).width >= UI.dp(a, 120)) { "Long value squeezed the row title" }
            }
            fun button(label: String): View {
                var result: View? = null
                test.runOnMainSync {
                    var target = views(a.window.decorView).firstOrNull {
                        it.isShown && (it.contentDescription == label || it is TextView && it.text.toString() == label)
                    }
                    while (target != null && !target.isClickable && target.parent is View) target = target.parent as View
                    result = target?.takeIf { it.isClickable }
                }
                return checkNotNull(result) { "Missing action: $label" }
            }
            fun submit(text: String) {
                await("composer ready") { ui { views(a.window.decorView).any { it.isShown && it.contentDescription == "发送消息" } } }
                test.runOnMainSync { input.setText(text); input.requestFocus() }
                tap(button("发送消息"))
                await("inline approval") { ui { host.isShown && views(host).filterIsInstance<TextView>().any { it.text == "允许一次" } } }
                test.runOnMainSync {
                    val rect = android.graphics.Rect(); check(host.getGlobalVisibleRect(rect) && rect.height() == host.height) { "Approval actions are clipped" }
                    check(input.text.isEmpty())
                }
            }
            fun action(label: String): View { var v: View? = null; test.runOnMainSync { v = views(host).filterIsInstance<TextView>().first { it.text == label } }; return v!! }
            val suffix = if (dark) "dark" else "light"
            val composerSend = button("发送消息")
            test.runOnMainSync {
                val send = composerSend
                val inputXY = IntArray(2); val sendXY = IntArray(2); input.getLocationOnScreen(inputXY); send.getLocationOnScreen(sendXY)
                check(input.width >= a.window.decorView.width * 0.65f) { "Composer does not give the draft a full reading line" }
                check(inputXY[1] + input.height <= sendXY[1]) { "Composer controls overlap the input line" }
            }
            test.uiAutomation.grantRuntimePermission(c.packageName, android.Manifest.permission.RECORD_AUDIO)
            tap(button("切换语音输入"))
            check(ui { input.visibility == View.GONE && views(a.window.decorView).any { it.isShown && it.contentDescription == "按住说话，松开结束，上滑取消" } })
            tap(button("切换文字输入"))
            check(ui { input.isShown })
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            submit("approval Android redesign $suffix")
            val rejectAction = action("拒绝"); val allowAction = action("允许一次")
            test.runOnMainSync {
                val rejectXY = IntArray(2); val allowXY = IntArray(2)
                rejectAction.getLocationOnScreen(rejectXY); allowAction.getLocationOnScreen(allowXY)
                check(rejectXY[0] < allowXY[0]) { "Approval primary action must stay on the right" }
            }
            capture("redesign-$suffix-approval")
            tap(action("查看完整请求"))
            await("all provider options remain accessible") {
                val root = test.uiAutomation.rootInActiveWindow
                root != null && root.findAccessibilityNodeInfosByText("始终允许").isNotEmpty() && root.findAccessibilityNodeInfosByText("始终拒绝").isNotEmpty()
            }
            capture("redesign-$suffix-details")
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("details dismissed") { ui { a.hasWindowFocus() } }
            test.uiAutomation.waitForIdle(250, 2500)
            tap(action("允许一次"))
            await("approval cleared") { ui { !host.isShown } }
            await("task completed through SSE") { ui { views(a.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("fixture: approval Android redesign $suffix") } } }
            val status = JSONObject(AgentRepository.snapshot(c, sid).second)
            check(status.optJSONArray("pending")?.length() == 0)
            capture("redesign-$suffix-chat")
            tap(button("查看文件差异"))
            await("actual project diff loaded") {
                val root = test.uiAutomation.rootInActiveWindow
                root != null && root.findAccessibilityNodeInfosByText("Review.kt").isNotEmpty()
            }
            capture("redesign-$suffix-files")
            tapDialog("展开或收起文件改动，Review.kt")
            await("file patch collapsed") {
                test.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("isNotBlank").isEmpty()
            }
            tapDialog("展开或收起文件改动，Review.kt")
            await("file patch expanded again") {
                test.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("isNotBlank").isNotEmpty()
            }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("diff returned to conversation") { ui { a.hasWindowFocus() } }
            submit("approval Android redesign rejection $suffix")
            tap(action("拒绝"))
            await("rejection requires confirmation") {
                test.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("确认拒绝").isNotEmpty()
            }
            check(JSONObject(AgentRepository.snapshot(c, sid).second).optJSONArray("pending")?.length() == 1) { "Rejection was sent before confirmation" }
            tapDialog("确认拒绝")
            await("rejection clears card") { ui { !host.isShown } }
            await("rejected interaction persisted") {
                val j = JSONObject(AgentRepository.snapshot(c, sid).second)
                j.optJSONArray("pending")?.length() == 0 && !j.optBoolean("running")
            }
            // A remote answer invalidates the visible card as soon as the SSE status arrives.
            submit("approval Android redesign stale $suffix")
            val pending = JSONObject(AgentRepository.snapshot(c, sid).second).getJSONArray("pending").getString(0)
            check(AgentRepository.command(c, sid, "interaction", "/interactions/$pending/respond", JSONObject().put("option", "allow")).accepted)
            await("stale approval removed") { ui { !host.isShown } }
            tap(button("打开对话列表"))
            await("drawer fully opened") { ui { views(a.window.decorView).filterIsInstance<DrawerLayout>().single().isDrawerOpen(GravityCompat.START) } }
            await("real project sessions, including desktop-store fallback") {
                ui { views(a.window.decorView).any { it.isShown && it.tag == sid && it.contentDescription?.startsWith("打开会话，") == true } }
            }
            capture("redesign-$suffix-projects")
            lateinit var search: EditText
            test.runOnMainSync {
                search = views(a.window.decorView).filterIsInstance<EditText>().first { it.contentDescription == "搜索会话" }
                search.setText("no-match-redesign-isolated")
            }
            await("thread search") { ui { views(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text == "没有匹配的会话" } } }
            test.runOnMainSync { search.setText("") }
            await("thread search cleared") { ui { views(a.window.decorView).any { it.isShown && it.tag == sid && it.contentDescription?.startsWith("打开会话，") == true } } }
            // Real drawer action opens the complete settings, preserves drafts and stops foreground suppression.
            test.runOnMainSync { input.setText("unsent navigation draft") }
            val settingsEntry = button("设置")
            tap(settingsEntry)
            await("settings hide conversation") { CodexPane.visibleSession == null }
            await("connection settings") { ui { views(a.window.decorView).filterIsInstance<TextView>().any { it.text == "服务器与设备凭据" } } }
            capture("redesign-$suffix-settings")
            tap(button("返回对话"))
            await("return to the same conversation") { CodexPane.visibleSession == sid }
            check(ui { input.text.toString() == "unsent navigation draft" && input.isShown }) { "Settings lost the composer draft" }
            tap(button("打开对话列表"))
            await("drawer fully opened") { ui { views(a.window.decorView).filterIsInstance<DrawerLayout>().single().isDrawerOpen(GravityCompat.START) } }
            tap(button("设置"))
            await("settings reopened") { CodexPane.visibleSession == null }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("system back returns to conversation") { CodexPane.visibleSession == sid }
            tap(button("打开对话列表"))
            await("drawer fully opened") { ui { views(a.window.decorView).filterIsInstance<DrawerLayout>().single().isDrawerOpen(GravityCompat.START) } }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("system back closes the drawer") { ui { !views(a.window.decorView).filterIsInstance<DrawerLayout>().single().isDrawerVisible(GravityCompat.START) } }
            check(ui { views(a.window.decorView).filterIsInstance<TextView>().none { it.isShown && (it.text == "消息" || it.text == "全部标为已读") } })
            // Recreate a notification-launched activity while settings are open.
            tap(button("打开对话列表"))
            await("drawer before recreation") { ui { views(a.window.decorView).filterIsInstance<DrawerLayout>().single().isDrawerOpen(GravityCompat.START) } }
            tap(button("设置"))
            await("settings before recreation") { CodexPane.visibleSession == null }
            val monitor = test.addMonitor(MainActivity::class.java.name, null, false)
            try {
                test.runOnMainSync { a.recreate() }
                a = monitor.waitForActivityWithTimeout(10000) as? MainActivity ?: error("Settings recreation did not complete")
                await("settings restored after recreation") { ui { views(a.window.decorView).any { it.isShown && it.contentDescription == "返回对话" } } }
                check(CodexPane.visibleSession == null)
                tap(button("返回对话"))
                await("restored settings return to conversation") { CodexPane.visibleSession == sid }
            } finally { test.removeMonitor(monitor) }
        } finally { test.runOnMainSync { a.finish() } }
    }
}
