package io.leoagent.app

import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONArray
import org.json.JSONObject

/** Sends actual touchscreen events: performClick() bypasses the focus-consuming regression. */
class ToolbarTouchChecks(private val test: Instrumentation) {
    private val c = test.targetContext
    private fun views(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun ui(check: () -> Boolean): Boolean {
        var result = false
        test.runOnMainSync { result = check() }
        return result
    }
    private fun await(label: String, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 2500
        while (SystemClock.uptimeMillis() < end) {
            if (check()) return
            Thread.sleep(30)
        }
        error("Single tap did not respond: $label")
    }
    private fun tap(v: View) {
        test.waitForIdleSync()
        val xy = IntArray(2)
        var x = 0f; var y = 0f
        test.runOnMainSync {
            check(v.isShown && v.width > 0 && v.height > 0)
            v.getLocationOnScreen(xy)
            x = xy[0] + v.width / 2f; y = xy[1] + v.height / 2f
        }
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            test.sendPointerSync(e); e.recycle()
        }
        test.waitForIdleSync()
    }
    private fun hasText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.toString()?.contains(text) == true) return true
        return (0 until node.childCount).any { hasText(node.getChild(it), text) }
    }
    fun run(reproduceOldFocus: Boolean) {
        check(c.packageName == "io.leoagent.app.verification")
        val a = test.startActivitySync(Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            test.runOnMainSync {
                views(a.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.n_agent
            }
            test.waitForIdleSync()
            lateinit var pane: CodexPane
            lateinit var input: EditText
            lateinit var more: View
            test.runOnMainSync {
                pane = MainActivity::class.java.getDeclaredField("pane").apply { isAccessible = true }.get(a) as CodexPane
                input = CodexPane::class.java.getDeclaredField("input").apply { isAccessible = true }.get(pane) as EditText
                more = views(a.window.decorView).first { it.contentDescription == "更多操作" }
                // Reproduce the 0.1.3 behavior with the same real view, not a mocked listener.
                if (reproduceOldFocus) more.isFocusableInTouchMode = true
                input.requestFocus()
            }
            tap(more)
            if (reproduceOldFocus) {
                check(!hasText(test.uiAutomation.rootInActiveWindow, "显示思考过程"))
                check(ui { more.hasFocus() }) { "Old first tap did not focus the button" }
                tap(more)
                await("old behavior requires second tap") { hasText(test.uiAutomation.rootInActiveWindow, "显示思考过程") }
                test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                return
            }
            await("more menu") { hasText(test.uiAutomation.rootInActiveWindow, "显示思考过程") }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            // Repeat after moving focus back to an editable field.
            test.runOnMainSync { input.requestFocus() }
            tap(more)
            await("more menu after returning to input") { hasText(test.uiAutomation.rootInActiveWindow, "显示思考过程") }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

            lateinit var pipButton: View
            lateinit var drawerButton: View
            lateinit var diffButton: View
            lateinit var pip: ScreenPip
            lateinit var drawer: DrawerLayout
            lateinit var title: View
            test.runOnMainSync {
                val all = views(a.window.decorView)
                pipButton = all.first { it.contentDescription == "桌面画中画" }
                drawerButton = all.first { it.contentDescription == "打开对话列表" }
                diffButton = all.first { it.contentDescription == "查看文件差异" }
                pip = CodexPane::class.java.getDeclaredField("pip").apply { isAccessible = true }.get(pane) as ScreenPip
                drawer = all.filterIsInstance<DrawerLayout>().single()
                title = (CodexPane::class.java.getDeclaredField("titleTv").apply { isAccessible = true }.get(pane) as View).parent as View
                CodexPane::class.java.getDeclaredField("models").apply { isAccessible = true }
                    .set(pane, JSONArray().put(JSONObject().put("id", "fixture").put("name", "Touch fixture model")))
            }
            tap(title)
            await("model menu") { hasText(test.uiAutomation.rootInActiveWindow, "Touch fixture model") }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            tap(pipButton)
            await("desktop preview opens") { ui { pip.shown } }
            tap(pipButton)
            await("desktop preview closes") { ui { !pip.shown } }
            tap(drawerButton)
            await("conversation drawer") { ui { drawer.isDrawerOpen(GravityCompat.START) } }
            test.runOnMainSync { drawer.closeDrawers() }
            await("drawer closes") { ui { !drawer.isDrawerVisible(GravityCompat.START) } }
            test.runOnMainSync {
                CodexPane::class.java.getDeclaredField("sid").apply { isAccessible = true }.set(pane, "touch-fixture")
            }
            tap(diffButton)
            await("diff opens before network completes") { hasText(test.uiAutomation.rootInActiveWindow, "改动 (git diff)") }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

            // Shared controls retain keyboard support without consuming the first touch.
            var clicks = 0
            lateinit var dialog: Dialog
            val buttons = arrayListOf<View>()
            test.runOnMainSync {
                val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                box.addView(EditText(a).apply { hint = "Touch focus source" })
                buttons.add(UI.filledButton(a, "Test button") { clicks++ })
                box.addView(buttons.last())
                buttons.add(UI.row(a, box, "Test row") { clicks++ })
                buttons.add(UI.action(a, box, "Test action") { clicks++ })
                dialog = Dialog(a)
                dialog.setContentView(box); dialog.show()
            }
            try {
                for ((i, b) in buttons.withIndex()) {
                    tap(b)
                    check(ui { clicks == i + 1 }) { "Shared control consumed first tap: $i" }
                }
                test.setInTouchMode(false)
                test.runOnMainSync { check(buttons.first().requestFocus()) }
                test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                check(ui { clicks == 4 }) { "Keyboard activation no longer works" }
            } finally {
                test.setInTouchMode(true)
                test.runOnMainSync { dialog.dismiss() }
            }
        } finally {
            test.runOnMainSync { a.finish() }
        }
    }
}
