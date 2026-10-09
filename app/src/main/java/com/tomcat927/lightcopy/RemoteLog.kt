package com.tomcat927.lightcopy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 远程日志（照 notion-app-android 的 RemoteLogService 移植）：内存环形缓冲 + 本地待传文件，
 * 上传到用户自配的 OpenList——登录(/api/auth/login/hash) → mkdir → PUT /api/fs/put，
 * 每次上传一个独立诊断文件 {远程目录}/install-{安装ID}/log-时间戳.txt，401 自动重登重试。
 *
 * 隐私红线：只记事件与状态（块数、成败、包名、耗时），绝不记复制的文本内容；
 * 上传/复制前统一脱敏（凭据/URL/邮箱/本地路径）。
 * 默认关闭；OpenList 配置存 SharedPreferences（运行期用户输入，不编译进包）。
 */
object RemoteLog {

    private const val TAG = "LightCopy"
    private const val PREFS = "lightcopy_prefs"
    private const val KEY_ENABLED = "remote_log_enabled"
    private const val KEY_BASE_URL = "remote_log_base_url"
    private const val KEY_USERNAME = "remote_log_username"
    private const val KEY_PASSWORD = "remote_log_password"
    private const val KEY_TARGET_PATH = "remote_log_target_path"
    private const val KEY_INSTALL_ID = "remote_log_install_id"
    private const val KEY_LAST_UPLOAD_AT = "remote_log_last_upload_at"
    private const val PENDING_FILE = "remote_pending.log"
    private const val ALIST_SALT = "https://github.com/alist-org/alist"
    private const val DEFAULT_TARGET_PATH = "/lightcopy/logs"
    private const val MAX_SNAPSHOT_BYTES = 2L * 1024 * 1024
    private const val MAX_BUFFER = 500

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedToken: String? = null
    private val buffer = ArrayDeque<String>()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "RemoteLog").apply { isDaemon = true } }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun sessionStart(context: Context) {
        init(context)
        i(TAG, "---- 会话开始 v${versionName(context)} · versionCode=${versionCode(context)} · 构建时间=${buildTimeOf(versionCode(context))} ----")
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
                if (file.exists() && file.length() > 256L * 1024) file.delete()
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

    fun getBaseUrl(context: Context): String = prefs(context).getString(KEY_BASE_URL, "").orEmpty()
    fun getUsername(context: Context): String = prefs(context).getString(KEY_USERNAME, "").orEmpty()
    fun getPassword(context: Context): String = prefs(context).getString(KEY_PASSWORD, "").orEmpty()
    fun getTargetPath(context: Context): String =
        prefs(context).getString(KEY_TARGET_PATH, DEFAULT_TARGET_PATH).orEmpty().ifBlank { DEFAULT_TARGET_PATH }

    fun saveConfig(context: Context, baseUrl: String, username: String, password: String, targetPath: String) {
        prefs(context).edit()
            .putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_TARGET_PATH, normalizeTargetPath(targetPath))
            .apply()
        if (password.isNotBlank()) {
            prefs(context).edit().putString(KEY_PASSWORD, password).apply()
        }
        cachedToken = null
    }

    fun isConfigured(context: Context): Boolean =
        getBaseUrl(context).isNotBlank() && getUsername(context).isNotBlank() && getPassword(context).isNotBlank()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun installId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_INSTALL_ID, null)?.let { return it }
        val random = java.security.SecureRandom()
        val id = (1..6).map { "%02x".format(random.nextInt(256)) }.joinToString("")
        p.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    private fun normalizeTargetPath(value: String): String {
        var path = value.trim().ifBlank { DEFAULT_TARGET_PATH }
        if (!path.startsWith("/")) path = "/$path"
        path = path.replace(Regex("/+"), "/")
        if (path == "/") path = DEFAULT_TARGET_PATH
        return path.trimEnd('/')
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

    /**
     * versionCode 是 CI 用 `date +%s` 生成的构建时间戳，换算成可读时间即可直接对应 Release tag。
     * 排查日志时必须能一眼确认"设备跑的到底是哪次构建"，否则容易把旧包的日志当新包分析。
     */
    fun buildTimeOf(code: Long): String = try {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(Date(code * 1000L))
    } catch (_: Exception) {
        "?"
    }

    // ---------- 上传 ----------

    enum class UploadResult { OK, NOT_CONFIGURED, AUTH_FAILED, FAILED }

    /** 待传内容超阈值时上传（供周期任务顺带触发） */
    fun maybeUpload(reason: String) {
        val ctx = appContext ?: return
        if (!isEnabled(ctx)) return
        val file = File(ctx.filesDir, PENDING_FILE)
        if (file.exists() && file.length() > 64L * 1024) upload(reason, null)
    }

    /**
     * 把待传日志作为独立诊断文件上传到 OpenList，成功后清空本地待传文件。
     * 结果回调统一切主线程（后台线程里弹 Toast 会直接崩）。
     * onResult 回调 (OK, 远程路径) 或 (失败原因, null)。
     */
    fun upload(reason: String, onResult: ((UploadResult, String?) -> Unit)?) {
        val ctx = appContext
        if (ctx == null || !isEnabled(ctx)) {
            onResult?.invoke(UploadResult.FAILED, null)
            return
        }
        executor.execute {
            val result = uploadBlocking(ctx, reason)
            if (result.first != UploadResult.OK) w(TAG, "日志上传失败（$reason）: $result")
            mainHandler.post { onResult?.invoke(result.first, result.second) }
        }
    }

    /**
     * 测试 OpenList 连接：用当前填写的账号做一次全新登录并验证目标目录可创建。
     * 密码留空时回退到已保存的密码（与保存逻辑一致）。
     */
    fun testConnection(
        baseUrl: String,
        username: String,
        password: String,
        targetPath: String,
        onResult: ((UploadResult) -> Unit)?,
    ) {
        if (baseUrl.isBlank() || username.isBlank()) {
            mainHandler.post { onResult?.invoke(UploadResult.NOT_CONFIGURED) }
            return
        }
        val effectivePassword = password.ifBlank {
            appContext?.let { getPassword(it) }.orEmpty()
        }
        if (effectivePassword.isBlank()) {
            mainHandler.post { onResult?.invoke(UploadResult.NOT_CONFIGURED) }
            return
        }
        executor.execute {
            val result = runCatching {
                val base = baseUrl.trim().trimEnd('/')
                val token = login(base, username.trim(), effectivePassword)
                mkdir(base, token, normalizeTargetPath(targetPath))
                UploadResult.OK
            }.getOrElse {
                if (it is AuthException) UploadResult.AUTH_FAILED else UploadResult.FAILED
            }
            mainHandler.post { onResult?.invoke(result) }
        }
    }

    private fun uploadBlocking(ctx: Context, reason: String): Pair<UploadResult, String?> {
        if (!isConfigured(ctx)) return UploadResult.NOT_CONFIGURED to null
        val baseUrl = getBaseUrl(ctx)
        val username = getUsername(ctx)
        val password = getPassword(ctx)
        val targetPath = getTargetPath(ctx)

        // 读前先改名再读，上传期间新增的行落到新文件里，不丢
        val dir = ctx.filesDir
        val pending = File(dir, PENDING_FILE)
        if (!pending.exists() || pending.length() == 0L) return UploadResult.OK to null
        val uploading = File(dir, "remote_uploading.log")
        if (!pending.renameTo(uploading)) return UploadResult.FAILED to null

        try {
            val snapshot = buildSnapshot(ctx, uploading, reason)
            if (snapshot.size > MAX_SNAPSHOT_BYTES) {
                w(TAG, "日志快照超 2MB，放弃本次上传")
                return UploadResult.FAILED to null
            }

            val remoteDirectory = "$targetPath/install-${installId(ctx)}"
            val fileName = "log-${fileFormat.format(Date())}.txt"
            val remotePath = "$remoteDirectory/$fileName"

            var token = login(baseUrl, username, password)
            val uploadOnce = { t: String ->
                mkdir(baseUrl, t, targetPath)
                mkdir(baseUrl, t, remoteDirectory)
                put(baseUrl, t, remotePath, snapshot)
            }
            try {
                uploadOnce(token)
            } catch (e: AuthException) {
                cachedToken = null
                token = login(baseUrl, username, password)
                uploadOnce(token)
            }

            uploading.delete()
            prefs(ctx).edit().putString(KEY_LAST_UPLOAD_AT, format.format(Date())).apply()
            i(TAG, "日志已上传: $remotePath (${snapshot.size} bytes)")
            return UploadResult.OK to remotePath
        } catch (e: AuthException) {
            cachedToken = null
            return UploadResult.AUTH_FAILED to null
        } catch (e: Exception) {
            w(TAG, "上传异常", e)
            return UploadResult.FAILED to null
        }
    }

    private fun buildSnapshot(ctx: Context, uploading: File, reason: String): ByteArray {
        val code = versionCode(ctx)
        // versionCode 是 CI 用 `date +%s` 生成的构建时间戳，换算成可读时间就能跟 Release tag 对上，
        // 排查时不必再手工换算（此前多次需要反推设备到底装的哪次构建）。
        val buildTime = buildTimeOf(code)
        val header = buildString {
            appendLine("轻复制诊断日志")
            appendLine("Generated: ${format.format(Date())} · 触发: $reason")
            appendLine(
                "App: v${versionName(ctx)} · versionCode=$code · 构建时间=$buildTime · " +
                    "sdk=${android.os.Build.VERSION.SDK_INT} · 机型=${android.os.Build.MODEL}"
            )
            appendLine("Privacy: 脱敏快照；凭据与复制内容不在日志中")
            appendLine()
        }
        val body = uploading.readText()
        return redact(header + body).toByteArray(Charsets.UTF_8)
    }

    /** 脱敏：凭据/Bearer/UUID/邮箱/URL/本地路径（照 notion-app-android 的 _redact 移植） */
    private fun redact(input: String): String {
        var value = input
        value = value.replace(
            Regex("(authorization|cookie|set-cookie|password|access[_-]?token|token)\\s*[:=]\\s*[^\\s,;]+", RegexOption.IGNORE_CASE),
            "$1=[REDACTED]",
        )
        value = value.replace(Regex("bearer\\s+[a-z0-9._~+/-]+=*", RegexOption.IGNORE_CASE), "Bearer [REDACTED]")
        value = value.replace(
            Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"),
            "[UUID]",
        )
        value = value.replace(Regex("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", RegexOption.IGNORE_CASE), "[EMAIL]")
        value = value.replace(Regex("https?://[^\\s]+"), "[URL]")
        value = value.replace(Regex("([a-z]:\\\\|/storage/|/data/)[^\\r\\n\\s]+", RegexOption.IGNORE_CASE), "[LOCAL_PATH]")
        return value
    }

    // ---------- OpenList API（照 notion-app-android 移植） ----------

    private class AuthException(message: String) : Exception(message)

    /** sha256(password + alistSalt) 哈希登录，返回 token */
    private fun login(baseUrl: String, username: String, password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$password-$ALIST_SALT".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val body = JSONObject().put("username", username).put("password", digest).put("otp_code", "")
        val json = postJson(
            "$baseUrl/api/auth/login/hash", body.toString(),
            headers = emptyMap(),
        )
        val token = json.optJSONObject("data")?.optString("token").orEmpty()
        if (json.optInt("code") != 200 || token.isEmpty()) {
            throw AuthException(json.optString("message", "OpenList 登录失败"))
        }
        cachedToken = token
        return token
    }

    /** 创建目录；已存在（message 含 exist）视为成功 */
    private fun mkdir(baseUrl: String, token: String, path: String) {
        val body = JSONObject().put("path", path)
        val json = postJson(
            "$baseUrl/api/fs/mkdir", body.toString(),
            headers = mapOf("Authorization" to token),
        )
        val message = json.optString("message").lowercase()
        if (json.optInt("code") != 200 && !message.contains("exist")) {
            throw Exception(json.optString("message", "无法创建远程日志目录"))
        }
    }

    /** PUT /api/fs/put：File-Header 携带完整远程路径 */
    private fun put(baseUrl: String, token: String, remotePath: String, bytes: ByteArray) {
        val encodedPath = java.net.URLEncoder.encode(remotePath, "UTF-8")
        val request = Request.Builder()
            .url("$baseUrl/api/fs/put")
            .header("Authorization", token)
            .header("File-Path", encodedPath)
            .header("Content-Type", "application/octet-stream")
            .header("Content-Length", bytes.size.toString())
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (json.optInt("code") == 401 || resp.code == 401) {
                throw AuthException(json.optString("message", "认证失败"))
            }
            if (json.optInt("code") != 200) {
                throw Exception(json.optString("message", "上传诊断日志失败（HTTP ${resp.code}）"))
            }
        }
    }

    private fun postJson(url: String, body: String, headers: Map<String, String>): JSONObject {
        val builder = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (json == null) throw Exception("OpenList 请求失败（HTTP ${resp.code}）")
            if (json.optInt("code") == 401 || resp.code == 401) {
                throw AuthException(json.optString("message", "认证失败"))
            }
            return json
        }
    }

    // ---------- 本地导出 ----------

    /** 全部日志（内存缓冲 + 本地待传文件）脱敏后复制到剪贴板，便于直接粘贴给开发者 */
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
        val text = redact(sb.toString())
        if (text.isBlank()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("lightcopy-log", text))
        return true
    }
}
