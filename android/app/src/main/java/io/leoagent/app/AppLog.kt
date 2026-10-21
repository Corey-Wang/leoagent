package io.leoagent.app

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 持久日志: filesDir/logs/app.log 环形 (2×1MB) + 崩溃标记; 打包上传到 Mac */
object AppLog {
    private const val MAX = 1_000_000L
    private var dir: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var uploading = false

    fun init(c: Context) {
        if (dir != null) return
        dir = File(c.filesDir, "logs").apply { mkdirs() }
        i("app", "start ${device(c)}")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                e("crash", "UNCAUGHT in ${t.name}: ${android.util.Log.getStackTraceString(e)}")
                File(dir, "crash.flag").writeText(fmt.format(Date()))
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
        // Crash evidence stays local. Upload only after the explicit action in Settings.
    }

    fun device(c: Context): String {
        val v = try { c.packageManager.getPackageInfo(c.packageName, 0).let { "${it.versionName}(${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()})" } } catch (_: Exception) { "?" }
        return "app=$v android=${Build.VERSION.RELEASE}/sdk${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL} rom=${prop("ro.mi.os.version.name").ifEmpty { prop("ro.miui.ui.version.name") }} ${Build.DISPLAY}"
    }
    private fun prop(k: String) = try { Runtime.getRuntime().exec(arrayOf("getprop", k)).inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }

    fun i(tag: String, msg: String) = w("I", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = w("E", tag, if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}\n${android.util.Log.getStackTraceString(t).take(3000)}")

    internal fun redact(text: String): String = text
        .replace(Regex("\\b(?:la_|ld_)[a-zA-Z0-9_-]+"), "[credential]")
        .replace(Regex("(?i)((?:x-token|token|poll_secret|code)\\s*[=:]\\s*[\"']?)[^\\s\"'&,}]+"), "$1[redacted]")
        .replace(Regex("(?:https?://|leoagent://)[^\\s\"<>]+"), "[url]")
        .replace(Regex("(?:/Users/|/home/)[^\\s\"<>]+"), "[local-path]")

    @Synchronized private fun w(lv: String, tag: String, msg: String) {
        val safe = redact(msg)
        android.util.Log.println(if (lv == "E") android.util.Log.ERROR else android.util.Log.INFO, "leo/$tag", safe)
        val d = dir ?: return
        try {
            val f = File(d, "app.log")
            if (f.length() > MAX) { File(d, "app.log.1").delete(); f.renameTo(File(d, "app.log.1")) }
            File(d, "app.log").appendText("${fmt.format(Date())} $lv/$tag: $safe\n")
        } catch (_: Exception) {}
    }

    private fun zip(c: Context, note: String): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("info.txt", "${device(c)}\nnote=${redact(note)}\ntime=${Date()}\n".toByteArray())
            for (n in listOf("app.log.1", "app.log")) File(dir, n).takeIf { it.exists() }?.let { put(n, redact(it.readText()).toByteArray()) }
        }
        return bo.toByteArray()
    }

    /** 阻塞调用; 返回 null=成功 或 错误文字 */
    fun upload(c: Context, note: String, kind: String = "manual"): String? {
        if (dir == null) init(c)
        if (uploading) return "正在上传中"
        uploading = true
        return try {
            i("log", "upload kind=$kind")
            val b = zip(c, note)
            val (code, _) = Http.call("${Prefs.url(c)}/api/app/logs", "POST",
                mapOf("X-Token" to Prefs.token(c), "X-Note" to URLEncoder.encode(redact(note), "UTF-8"),
                    "X-Kind" to kind, "X-App-Ver" to device(c).substringAfter("app=").substringBefore(" ")),
                b, contentType = "application/zip", readTimeoutMs = 60000)
            if (code == 200) { File(dir, "crash.flag").delete(); i("log", "upload ok ${b.size}B"); null }
            else { e("log", "upload HTTP $code"); "HTTP $code" }
        } catch (x: Exception) { e("log", "upload failed", x); "${x.javaClass.simpleName}: ${x.message}" }
        finally { uploading = false }
    }
}

class LeoApp : Application() {
    override fun onCreate() { super.onCreate(); AppLog.init(this) }
}
