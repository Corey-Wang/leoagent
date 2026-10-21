package io.leoagent.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

object Updater {
    private const val T = "update"
    private var pending: String? = null   // 等待"安装未知应用"授权后继续
    private var target = 0

    fun myCode(a: Activity): Long = a.packageManager.getPackageInfo(a.packageName, 0).let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }

    fun check(a: Activity, manual: Boolean) = Thread {
        try {
            AppLog.i(T, "check manual=$manual mine=${myCode(a)}")
            val (c, b) = Net.req("${Prefs.url(a)}/api/app/version", Prefs.token(a))
            if (c != 200) { AppLog.e(T, "check HTTP $c $b"); if (manual) toast(a, "检查失败 HTTP $c"); return@Thread }
            val o = JSONObject(b); val sv = o.optInt("versionCode")
            AppLog.i(T, "server=$sv")
            if (sv <= myCode(a)) { if (manual) toast(a, "已是最新 (v${o.optString("versionName")})"); return@Thread }
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                AlertDialog.Builder(a).setTitle("发现新版本 v${o.optString("versionName")}").setMessage(o.optString("notes"))
                    .setPositiveButton("下载安装") { _, _ -> target = sv; download(a, o.optString("url", "/api/app/apk")) }
                    .setNeutralButton("浏览器下载") { _, _ -> openBrowser(a) }
                    .setNegativeButton("以后", null).show()
            }
        } catch (e: Exception) { AppLog.e(T, "check failed", e); if (manual) toast(a, "检查失败: ${cause(e)}") }
    }.start()

    private fun toast(a: Activity, s: String) = a.runOnUiThread { Toast.makeText(a, s, Toast.LENGTH_LONG).show() }

    fun openBrowser(a: Activity) {
        Thread {
            try {
                val result = Http.call("${Prefs.url(a)}/api/app/download-link", "POST", mapOf("X-Token" to Prefs.token(a)))
                check(result.first == 200) { "无法创建短期下载链接：HTTP ${result.first}" }
                val path = JSONObject(result.second).getString("url")
                check(path.startsWith("/api/app/download/") && !path.contains("..")) { "无效的下载链接" }
                a.runOnUiThread {
                    try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Prefs.url(a) + path)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (_: Exception) { toast(a, "无法打开浏览器") }
                }
            } catch (e: Exception) { toast(a, "无法打开下载：${e.message}") }
        }.start()
    }

    private fun cause(e: Throwable): String = when (e) {
        is UnknownHostException -> "无法解析服务器域名（网络不通或 DNS 问题）"
        is SocketTimeoutException -> "连接超时（网络慢或服务器无响应）"
        is javax.net.ssl.SSLException -> "TLS/证书错误: ${e.message}"
        is IOException -> "网络/IO 错误: ${e.message}"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    private fun fail(a: Activity, path: String, msg: String) = a.runOnUiThread {
        AppLog.e(T, "FAIL: $msg")
        if (a.isFinishing) return@runOnUiThread
        AlertDialog.Builder(a).setTitle("升级失败").setMessage("$msg\n\n可重试，或用浏览器下载后手动安装。")
            .setPositiveButton("重试") { _, _ -> download(a, path) }
            .setNeutralButton("浏览器下载") { _, _ -> openBrowser(a) }
            .setNegativeButton("取消", null).show()
    }

    /** MainActivity.onResume 调用: 授权回来后自动继续 */
    fun resume(a: Activity) {
        val p = pending ?: return
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) { AppLog.i(T, "resume: still no install permission"); return }
        pending = null; AppLog.i(T, "resume: permission granted, continue"); download(a, p)
    }

    fun download(a: Activity, path: String) {
        AppLog.i(T, "download start path=$path sdk=${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            AppLog.i(T, "need REQUEST_INSTALL_PACKAGES")
            pending = path
            AlertDialog.Builder(a).setTitle("需要「安装未知应用」权限")
                .setMessage("请在接下来的设置页打开「允许来自此来源的应用」，返回后会自动继续下载。\n（MIUI/HyperOS 若找不到开关，可用浏览器下载后安装）")
                .setPositiveButton("去设置") { _, _ ->
                    try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))) }
                    catch (e: Exception) { AppLog.e(T, "open unknown-sources settings failed", e)
                        try { a.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) { pending = null; fail(a, path, "无法打开权限设置页: ${e.message}") } }
                }
                .setNeutralButton("浏览器下载") { _, _ -> pending = null; openBrowser(a) }
                .setNegativeButton("取消") { _, _ -> pending = null }.show()
            return
        }
        val pv = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        val tv = TextView(a).apply { text = "连接中…"; setPadding(0, 16, 0, 0) }
        val box = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(60, 40, 60, 10); addView(pv); addView(tv) }
        val dlg = AlertDialog.Builder(a).setTitle("下载新版本").setView(box).setCancelable(false).setNegativeButton("取消", null).show()
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        dlg.setOnDismissListener { cancel.set(true) }
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val dir = File(a.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val part = File(dir, "leoagent.apk.part"); val f = File(dir, "leoagent.apk")
                var url = URL(Prefs.url(a) + path); var hops = 0
                while (true) {   // 手动跟随重定向 (含 http<->https), 只在同源时带 token
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false; connectTimeout = 20000; readTimeout = 60000
                        val origin = URL(Prefs.url(a))
                        if (url.protocol == origin.protocol && url.host == origin.host && url.port == origin.port) setRequestProperty("X-Token", Prefs.token(a))
                        setRequestProperty("Accept-Encoding", "identity")
                    }
                    val rc = conn.responseCode
                    AppLog.i(T, "GET ${url.host}${url.path} -> $rc len=${conn.getHeaderField("Content-Length")} type=${conn.contentType}")
                    if (rc in 300..399 && hops < 5) {
                        val next = URL(url, conn.getHeaderField("Location"))
                        if (next.protocol !in listOf("https", "http") || url.protocol == "https" && next.protocol != "https") throw IOException("拒绝不安全的更新重定向")
                        url = next; conn.disconnect(); hops++; continue
                    }
                    if (rc == 401) throw IOException("服务器拒绝访问 (401)，App 里的 token 可能已失效")
                    if (rc != 200) throw IOException("服务器返回 HTTP $rc")
                    break
                }
                val c = conn!!
                val total = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                var got = 0L; var lastUi = 0L
                val md = MessageDigest.getInstance("SHA-256")
                c.inputStream.use { i -> part.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancel.get()) throw InterruptedException("cancel")
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); got += n
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 200) { lastUi = now; a.runOnUiThread {
                            if (total > 0) { pv.isIndeterminate = false; pv.progress = (got * 100 / total).toInt() }
                            tv.text = "%.1f / %s MB".format(got / 1048576.0, if (total > 0) "%.1f".format(total / 1048576.0) else "?") } }
                    }
                } }
                AppLog.i(T, "downloaded $got B (expected $total) sha256=${md.digest().joinToString("") { "%02x".format(it) }.take(16)}")
                if (total > 0 && got != total) throw IOException("下载不完整 ($got/$total 字节)，网络中断")
                if (got < 100_000) throw IOException("下载内容太小 ($got 字节)，不是有效安装包")
                if (!part.renameTo(f)) throw IOException("无法保存安装包到缓存目录")
                // 校验 APK: 包名 / 版本 / 签名
                val pm = a.packageManager
                val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
                val ai = pm.getPackageArchiveInfo(f.path, flag) ?: throw IOException("安装包解析失败（文件损坏）")
                val av = if (Build.VERSION.SDK_INT >= 28) ai.longVersionCode else ai.versionCode.toLong()
                AppLog.i(T, "apk pkg=${ai.packageName} vc=$av mine=${myCode(a)}")
                if (ai.packageName != a.packageName) throw IOException("安装包包名不符: ${ai.packageName}")
                if (av <= myCode(a)) throw IOException("下载到的版本 ($av) 不比当前版本 (${myCode(a)}) 新")
                if (!sameSig(a, ai)) throw IOException("新安装包签名与当前 App 不一致，无法覆盖安装。需先卸载旧版再用浏览器下载安装（会清除本地设置）")
                val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", f)
                AppLog.i(T, "launch installer uri=$uri")
                a.runOnUiThread {
                    try { dlg.setOnDismissListener(null); dlg.dismiss() } catch (_: Exception) {}
                    try {
                        a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) { AppLog.e(T, "installer launch failed", e); fail(a, path, "无法启动系统安装器: ${e.message}") }
                }
            } catch (e: InterruptedException) { AppLog.i(T, "cancelled by user")
            } catch (e: Throwable) {
                AppLog.e(T, "download failed", e)
                a.runOnUiThread { try { dlg.setOnDismissListener(null); dlg.dismiss() } catch (_: Exception) {} }
                fail(a, path, if (e is IOException && e.message?.any { it.code > 0x2e80 } == true) e.message!! else cause(e))
            } finally { conn?.disconnect() }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun sameSig(a: Activity, ai: android.content.pm.PackageInfo): Boolean = try {
        val pm = a.packageManager
        val mine = if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(a.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.map { it.toCharsString() }
                   else pm.getPackageInfo(a.packageName, PackageManager.GET_SIGNATURES).signatures?.map { it.toCharsString() }
        val theirs = if (Build.VERSION.SDK_INT >= 28) ai.signingInfo?.apkContentsSigners?.map { it.toCharsString() } else ai.signatures?.map { it.toCharsString() }
        AppLog.i("update", "sig mine=${mine?.firstOrNull()?.hashCode()} apk=${theirs?.firstOrNull()?.hashCode()}")
        !theirs.isNullOrEmpty() && !mine.isNullOrEmpty() && mine.toSet() == theirs.toSet()
    } catch (e: Exception) { AppLog.e("update", "sig check error", e); false }
}
