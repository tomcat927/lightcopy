package com.tomcat927.lightcopy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.Proxy
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 热更新：检查/下载/校验/安装（照 imgink-uploader 的 Updater 移植）。
 *
 * 版本发布链路：CI 每次 push main 构建正式签名 APK 并发布 Release，
 * 同时上传 latest.json 清单（version_code 为构建时间戳，单调递增）。
 * 「镜像加速」开关（照 ncm-cloud-player）：开启时清单/下载/校验优先走 gh-proxy.com（国内可直连），
 * 关闭时优先 GitHub 直连，镜像仅作兜底；每条 URL 按归属自动选 HTTP 客户端
 * （gh-proxy 绕过系统代理，GitHub 直连走系统代理）。
 */
object Updater {

    private const val TAG = "LightCopy"
    private const val OWNER = "tomcat927"
    private const val REPO = "lightcopy"
    private const val PROXY_PREFIX = "https://gh-proxy.com/"
    private const val MANIFEST_URL =
        "https://github.com/$OWNER/$REPO/releases/latest/download/latest.json"
    private const val MANIFEST_URL_PROXIED = PROXY_PREFIX + MANIFEST_URL
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    private const val PREFS = "lightcopy_prefs"
    private const val KEY_PREFER_MIRROR = "update_prefer_mirror"

    data class UpdateInfo(
        val tagName: String,
        val versionCode: Long,
        val versionDisplay: String,
        val downloadUrl: String,
        val fallbackDownloadUrl: String,
        val checksumUrl: String,
        val fallbackChecksumUrl: String,
        val releaseUrl: String,
        val notes: String?
    )

    // gh-proxy 国内可直连（绕过系统代理）；GitHub 直连走系统代理（需要代理的用户自行开启）
    private val directClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .proxy(Proxy.NO_PROXY)
            .build()
    }
    private val systemClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private fun clientFor(url: String): OkHttpClient =
        if (url.startsWith(PROXY_PREFIX)) directClient else systemClient

    // ---------- 镜像加速开关 ----------

    fun isPreferMirror(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PREFER_MIRROR, true)

    fun setPreferMirror(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PREFER_MIRROR, value).apply()
        RemoteLog.i(TAG, "镜像加速更新下载 = $value")
    }

    fun currentVersionCode(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } catch (_: Exception) {
        0L
    }

    fun currentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** 检查更新，返回 null 表示已是最新或检查失败 */
    suspend fun checkForUpdate(context: Context, preferMirror: Boolean): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val current = currentVersionCode(context)
            val info = checkManifest(preferMirror)
                ?: checkManifest(!preferMirror)
                ?: checkGithubApi(preferMirror)
                ?: checkGithubApi(!preferMirror)
            if (info != null && info.versionCode > current) info else null
        }

    private fun checkManifest(preferMirror: Boolean): UpdateInfo? {
        return try {
            val url = if (preferMirror) MANIFEST_URL_PROXIED else MANIFEST_URL
            val body = clientFor(url).newCall(Request.Builder().url(url).build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null }
                ?: return null
            parseManifest(body, preferMirror)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseManifest(json: String, preferMirror: Boolean): UpdateInfo? {
        return runCatching {
            val d = JSONObject(json)
            val code = d.getLong("version_code")
            val apk = d.getString("apk")
            val ghApk = d.getString("github_apk")
            val sha = d.getString("apk_sha256")
            val ghSha = d.getString("github_apk_sha256")
            if (apk.isBlank() || ghApk.isBlank() || sha.isBlank() || ghSha.isBlank()) {
                return null
            }
            // 按开关决定主备顺序：镜像关闭时 GitHub 直连为主、镜像兜底
            val (primary, secondary) = if (preferMirror) apk to ghApk else ghApk to apk
            val (primarySha, secondarySha) = if (preferMirror) sha to ghSha else ghSha to sha
            UpdateInfo(
                tagName = d.optString("tag_name", ""),
                versionCode = code,
                versionDisplay = d.optString("version_display", d.optString("tag_name", "")),
                downloadUrl = primary,
                fallbackDownloadUrl = secondary,
                checksumUrl = primarySha,
                fallbackChecksumUrl = secondarySha,
                releaseUrl = d.optString("release_url", ""),
                notes = d.optString("release_notes", null)
            )
        }.getOrNull()
    }

    private fun checkGithubApi(preferMirror: Boolean): UpdateInfo? {
        return try {
            val req = Request.Builder().url(API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", REPO)
                .build()
            val body = clientFor(API_URL).newCall(req).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
                ?: return null
            val d = JSONObject(body)
            val tag = d.optString("tag_name", "")
            val code = versionCodeFromTag(tag) ?: return null
            val assets = d.optJSONArray("assets") ?: return null
            var apk = ""; var sha = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                val url = a.optString("browser_download_url", "")
                when {
                    name.endsWith(".apk") -> apk = url
                    name.endsWith(".apk.sha256") -> sha = url
                }
            }
            if (apk.isBlank() || sha.isBlank()) return null
            val (primary, secondary) = if (preferMirror) PROXY_PREFIX + apk to apk else apk to PROXY_PREFIX + apk
            val (primarySha, secondarySha) = if (preferMirror) PROXY_PREFIX + sha to sha else sha to PROXY_PREFIX + sha
            UpdateInfo(
                tagName = tag,
                versionCode = code,
                versionDisplay = tag.removePrefix("v"),
                downloadUrl = primary,
                fallbackDownloadUrl = secondary,
                checksumUrl = primarySha,
                fallbackChecksumUrl = secondarySha,
                releaseUrl = d.optString("html_url", ""),
                notes = d.optString("body", null)
            )
        } catch (_: Exception) {
            null
        }
    }

    /** tag 形如 v0.1.0-20261007033358（时间为北京时间），解析为 epoch 秒 */
    fun versionCodeFromTag(tag: String): Long? {
        val m = Regex("-([0-9]{14})$").find(tag) ?: return null
        val t = m.groupValues[1]
        return runCatching {
            LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant().epochSecond
        }.getOrNull()
    }

    /**
     * 下载 APK 并 SHA-256 校验，成功返回本地文件。
     * 支持断点续传：已存在的部分文件通过 Range 头续传；校验失败自动删除重下。
     * 全局互斥：前台「立即更新」与后台预下载并发时串行执行，后到者直接命中缓存秒完成。
     */
    private val downloadMutex = Mutex()

    suspend fun downloadAndVerify(
        context: Context,
        info: UpdateInfo,
        onProgress: (received: Long, total: Long) -> Unit
    ): File = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "apk_updates").apply { mkdirs() }
            val file = File(dir, "lightcopy-update.apk")
            val sidecar = File(file.absolutePath + ".sha256")

            // 已有完整下载且校验通过 → 跳过下载
            if (file.exists() && file.length() > 0) {
                val expected = readChecksum(listOf(info.checksumUrl, info.fallbackChecksumUrl))
                    ?: sidecar.takeIf { it.exists() }?.readText()?.trim()
                if (expected != null && sha256(file) == expected.lowercase()) {
                    onProgress(file.length(), file.length())
                    return@withContext file
                }
                file.delete()
                sidecar.delete()
            }

            var lastError: Exception? = null
            for (url in listOf(info.downloadUrl, info.fallbackDownloadUrl)) {
                try {
                    RemoteLog.d(TAG, "download try: ${if (url.startsWith(PROXY_PREFIX)) "mirror" else "github-direct"}")
                    download(url, file, onProgress)
                    val expected = readChecksum(listOf(info.checksumUrl, info.fallbackChecksumUrl))
                        ?: error("无法获取 SHA-256 校验值")
                    val actual = sha256(file)
                    if (actual != expected.lowercase()) {
                        file.delete()
                        sidecar.delete()
                        error("SHA-256 校验失败")
                    }
                    return@withContext file
                } catch (e: Exception) {
                    RemoteLog.w(TAG, "download failed from $url: ${e.message}")
                    lastError = e
                    file.delete()
                    sidecar.delete()
                }
            }
            throw lastError ?: IllegalStateException("下载失败")
        }
    }

    private fun download(
        url: String,
        file: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val existing = if (file.exists()) file.length() else 0L
        val req = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()
        clientFor(url).newCall(req).execute().use { resp ->
            when (resp.code) {
                200, 206 -> {}
                416 -> error("断点信息失效")
                else -> error("HTTP ${resp.code}")
            }
            val resume = resp.code == 206 && existing > 0
            val body = resp.body ?: error("空响应")
            val total = if (resume) existing + body.contentLength() else body.contentLength()
            var received = if (resume) existing else 0L
            val digest = MessageDigest.getInstance("SHA-256")

            FileOutputStream(file, resume).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        received += n
                        onProgress(received, total)
                    }
                }
            }
            if (total > 0 && received != total) error("下载不完整：$received/$total")
            // 下载中流式算好的哈希存 sidecar，离线重装场景可代替在线校验值
            File(file.absolutePath + ".sha256").writeText(hex(digest))
        }
    }

    private fun hex(digest: MessageDigest): String =
        digest.digest().joinToString("") { "%02x".format(it) }

    private fun readChecksum(urls: List<String>): String? {
        for (url in urls) {
            try {
                val body = clientFor(url).newCall(Request.Builder().url(url).build())
                    .execute().use { if (it.isSuccessful) it.body?.string() else null } ?: continue
                val value = body.trim().split(Regex("\\s+")).firstOrNull() ?: continue
                if (value.length == 64 && Regex("^[0-9a-fA-F]{64}$").matches(value)) return value
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return hex(digest)
    }

    // ---------- 安装 ----------

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
