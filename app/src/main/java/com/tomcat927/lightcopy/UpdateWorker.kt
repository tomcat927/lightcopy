package com.tomcat927.lightcopy

import android.Manifest
import android.app.NotificationChannelCompat
import android.app.NotificationManagerCompat
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 后台热更新：检查新版本 → 有则后台下载并校验（与前台「立即更新」共用
 * Updater 的互斥锁和缓存文件，谁先完成都一样）→ 成功后发「点按安装」通知。
 * 由启动静默检查（发现新版本）和 6 小时周期任务两种方式触发。
 */
class UpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LightCopy"
        private const val CHANNEL_ID = "update_ready"
        private const val KEY_LAST_NOTIFIED_TAG = "last_notified_tag"
        const val WORK_ONE_TIME = "update_download_once"
        const val WORK_PERIODIC = "update_check_periodic"
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val info = Updater.checkForUpdate(context, Updater.isPreferMirror(context))
            ?: return Result.success()
        RemoteLog.i(TAG, "后台更新：发现 ${info.tagName}，开始后台下载")
        return try {
            val apk = withContext(Dispatchers.IO) {
                Updater.downloadAndVerify(context, info) { _, _ -> }
            }
            RemoteLog.i(TAG, "后台更新：下载完成 ${apk.length()} bytes，${info.tagName}")
            notifyReady(context, info, apk)
            Result.success()
        } catch (e: Exception) {
            RemoteLog.w(TAG, "后台更新失败 ${info.tagName}: ${e.message}")
            Result.retry()
        }
    }

    /** 就绪通知：点按直接调起系统安装器；同一版本只通知一次 */
    private fun notifyReady(context: Context, info: Updater.UpdateInfo, apk: File) {
        val prefs = context.getSharedPreferences("lightcopy_prefs", Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_NOTIFIED_TAG, "") == info.tagName) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("更新就绪通知")
                .build()
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk),
                "application/vnd.android.package-archive",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            context, info.versionCode.toInt(), installIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("轻复制新版本已就绪")
            .setContentText("${info.versionDisplay} 已后台下载并校验，点按安装")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        // Android 13+ 未授予通知权限时静默跳过（APK 已就绪，下次打开秒装）
        runCatching { NotificationManagerCompat.from(context).notify(info.versionCode.toInt(), notification) }
            .onFailure { RemoteLog.d(TAG, "notify skipped: ${it.message}") }
        prefs.edit().putString(KEY_LAST_NOTIFIED_TAG, info.tagName).apply()
    }
}
