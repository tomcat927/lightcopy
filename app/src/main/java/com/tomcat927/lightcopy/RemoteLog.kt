package com.tomcat927.lightcopy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 远程日志：内存环形缓冲 + 本地待传文件 + POST 到用户自配 URL。
 *
 * 隐私红线：只记事件与状态（块数、成败、包名、耗时），绝不记复制的文本内容。
 * 默认关闭；URL 存 SharedPreferences，不编译进包。上传失败文件保留，下次继续传。
 */
object RemoteLog {

    private const val TAG = "LightCopy"
    private const val PREFS = "lightcopy_prefs"
    private const val KEY_ENABLED = "remote_log_enabled"
    private const val KEY_URL = "remote_log_url"
    private const val PENDING_FILE = "remote_pending.log"
    private const val MAX_BUFFER = 500
    private const val MAX_PENDING_BYTES = 256L * 1024

    @Volatile
    private var appContext: Context? = null
    private val buffer = ArrayDeque<String>()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "RemoteLog").apply { isDaemon = true } }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun sessionStart(context: Context) {
        init(context)
        i(TAG, "---- 会话开始 v${versionName(context)} (versionCode ${versionCode(context)}) ----")
    }

    fun d(tag: String, msg: String) = log("D", tag, msg)
    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log("W", tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log("E", tag, msg, tr)

    private fun log(level: String, tag: String, msg: String, tr: Throwable? = null) {
        val full = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        when (level) {
            "D" -> Log.d(tag, msg, tr)
            "W" -> Log.w(tag, msg, tr)
            else -> Log.e(tag, msg, tr)
        }
        val line = "${format.format(Date())} $level/$tag: $full"
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_BUFFER) buffer.removeFirst()
        }
        executor.execute {
            runCatching {
                val ctx = appContext ?: return@execute
                val file = File(ctx.filesDir, PENDING_FILE)
                if (file.exists() && file.length() > MAX_PENDING_BYTES) file.delete()
                file.appendText(line + "\n")
            }
        }
    }

    // ---------- 配置 ----------

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) i(TAG, "远程日志已开启")
    }

    fun getUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, "").orEmpty()

    fun setUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, url.trim()).apply()
    }

    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    fun versionCode(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } catch (_: Exception) {
        0L
    }

    // ---------- 上传 ----------

    /** 待传内容超阈值时上传（供周期任务顺带触发） */
    fun maybeUpload(reason: String) {
        val ctx = appContext ?: return
        if (!isEnabled(ctx)) return
        val file = File(ctx.filesDir, PENDING_FILE)
        if (file.exists() && file.length() > 64L * 1024) upload(reason, null)
    }

    /**
     * 上传全部待传日志（POST text/plain 全量文本）。
     * 读前先改名再读，上传期间新增的行落到新文件里，不丢。
     */
    fun upload(reason: String, onResult: ((Boolean) -> Unit)?) {
        val ctx = appContext
        if (ctx == null || !isEnabled(ctx)) {
            onResult?.invoke(false)
            return
        }
        val url = getUrl(ctx)
        executor.execute {
            val result = runCatching {
                val dir = ctx.filesDir
                val pending = File(dir, PENDING_FILE)
                if (!pending.exists() || pending.length() == 0L) {
                    onResult?.invoke(true)   // 无待传内容视为成功
                    return@runCatching
                }
                val uploading = File(dir, "remote_uploading.log")
                if (!pending.renameTo(uploading)) error("rename failed")
                val header = "==== 轻复制日志上传（$reason） ${format.format(Date())} v${versionName(ctx)} ====\n"
                val body = (header + uploading.readText()).toRequestBody("text/plain".toMediaType())
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                    .newCall(Request.Builder().url(url).post(body).build())
                    .execute()
                    .use { resp ->
                        uploading.delete()
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    }
                true
            }
            val ok = result.getOrDefault(false)
            if (!ok) w(TAG, "日志上传失败（$reason）")
            onResult?.invoke(ok)
        }
    }

    /** 全部日志（内存缓冲 + 本地待传文件）复制到剪贴板，便于用户直接粘贴给开发者 */
    fun copyAllToClipboard(context: Context): Boolean {
        val sb = StringBuilder()
        synchronized(buffer) {
            sb.appendLine("---- 内存缓冲（最近 ${buffer.size} 条）----")
            buffer.forEach { sb.appendLine(it) }
        }
        val pending = File(context.filesDir, PENDING_FILE)
        if (pending.exists() && pending.length() > 0L) {
            sb.appendLine("---- 本地待传日志 ----")
            sb.append(pending.readText())
        }
        val text = sb.toString()
        if (text.isBlank()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("lightcopy-log", text))
        return true
    }
}
