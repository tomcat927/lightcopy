package com.tomcat927.lightcopy

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Root 保活：无障碍服务本就由系统 bind 常驻，真正会丢的是「开关状态」，
 * 所以保活 = 检测到开关被关 → 把两个 secure 设置写回来（合并追加，绝不覆盖踢掉其他服务）。
 *
 * 恢复双后端：adb 一次性授予的 WRITE_SECURE_SETTINGS 优先（免弹 su），否则走 su shell。
 * 覆盖面：应用内存活时的各种关闭（ROM 主动关/服务解绑）。
 * 强力停止场景 app 内全灭，属 V2 开机脚本范畴，V1 明确不做。
 */
object RootKeeper {

    private const val TAG = "LightCopy"
    private const val PREFS = "lightcopy_prefs"
    private const val KEY_KEEPALIVE_ON = "root_keepalive_on"
    private const val KEY_LAST_RECOVERY = "last_recovery_ms"
    private const val WORK_PERIODIC = "root_keepalive_periodic"
    private const val WORK_ONE_SHOT = "root_keepalive_once"

    /** 重绑风暴保护：两次恢复至少间隔 30 秒 */
    private const val MIN_RECOVERY_INTERVAL_MS = 30_000L

    fun isKeepAliveOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEPALIVE_ON, false)

    /** 开关联动持久化与 WorkManager 调度 */
    fun setKeepAliveOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_KEEPALIVE_ON, on).apply()
        if (on) schedule(context) else cancel(context)
    }

    /**
     * 请求 root（首次会弹 Magisk/KernelSU 授权框）。
     * 超时给足 15 秒：用户在管理器里点「允许」需要时间。
     */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        runSu("id", timeoutMs = 15_000)
    }

    /**
     * 恢复无障碍服务（已开启则原样返回 true）。
     * READ/WRITE 走 secure 设置；有 WRITE_SECURE_SETTINGS 用 Java 层写，
     * 没有就用一次 su 调用带两条 settings 命令（减少弹窗与管理器日志噪音）。
     * suTimeoutMs：首次授权要等用户在管理器里点「允许」，调用方可放宽（如瓦片路径 15 秒）。
     */
    fun ensureServiceEnabled(context: Context, suTimeoutMs: Long = 5_000L): Boolean {
        if (CopyAccessibilityService.isSelfEnabled(context)) return true
        val cr = context.contentResolver
        val cn = ComponentName(context, CopyAccessibilityService::class.java)
        val flat = cn.flattenToShortString()
        val current = Settings.Secure.getString(
            cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        // 合并而非覆盖：用户可能同时开着 MT 管理器等其他无障碍服务
        val merged = current.split(':')
            .filter { it.isNotBlank() }
            .toMutableList()
            .apply {
                if (none { it.equals(flat, true) || it.equals(cn.flattenToString(), true) }) {
                    add(flat)
                }
            }
            .joinToString(":")
            .ifBlank { flat }

        return try {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_SECURE_SETTINGS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged)
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                RemoteLog.d(TAG, "keepalive: recovered via WRITE_SECURE_SETTINGS")
                true
            } else {
                val script = "settings put secure enabled_accessibility_services '$merged'" +
                    " && settings put secure accessibility_enabled 1"
                runSu(script, timeoutMs = suTimeoutMs).also {
                    if (it) RemoteLog.d(TAG, "keepalive: recovered via su")
                }
            }
        } catch (e: Exception) {
            RemoteLog.w(TAG, "keepalive: recover failed: ${e.message}")
            false
        }
    }

    /**
     * 强制重绑：设置里服务已启用但实际没被绑定（热更新/进程死亡后的僵死状态，
     * 设置值不变系统不会触发重绑）→ 把本服务从启用列表摘掉再挂回，
     * 两次设置变更必然触发 AccessibilityManagerService 先解绑再绑定。
     * 只动本服务，其他无障碍服务不受影响。
     */
    fun forceRebind(context: Context): Boolean {
        val cr = context.contentResolver
        val cn = ComponentName(context, CopyAccessibilityService::class.java)
        val short = cn.flattenToShortString()
        val full = cn.flattenToString()
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        if (current.split(':').none { it.equals(short, true) || it.equals(full, true) }) return false
        val without = current.split(':')
            .filter { it.isNotBlank() && !it.equals(short, true) && !it.equals(full, true) }
            .joinToString(":")
        return try {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_SECURE_SETTINGS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without)
                Thread.sleep(400)
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, current)
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.d(TAG, "keepalive: force rebind via WRITE_SECURE_SETTINGS")
                true
            } else {
                // 一条 su 命令内完成摘除→等待→挂回→开总开关，避免多次弹 su
                val script = "settings put secure enabled_accessibility_services '$without' && sleep 0.5 && " +
                    "settings put secure enabled_accessibility_services '$current' && " +
                    "settings put secure accessibility_enabled 1"
                runSu(script, timeoutMs = 10_000).also {
                    if (it) Log.d(TAG, "keepalive: force rebind via su")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "force rebind failed: ${e.message}")
            false
        }
    }

    // ---------- 调度 ----------

    /** 15 分钟周期巡检（WorkManager 随重启自恢复） */
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_PERIODIC) }
    }

    /** 服务被解绑后 20 秒一次性恢复（进程若随后被杀则由周期巡检兜底） */
    fun onServiceUnbound(context: Context) {
        if (!isKeepAliveOn(context)) return
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_ONE_SHOT,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<KeepAliveWorker>()
                    .setInitialDelay(20, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }

    // ---------- root shell ----------

    private fun runSu(command: String, timeoutMs: Long = 5_000): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            // 排空输出防止管道写满阻塞
            val outThread = Thread { process.inputStream.use { it.readBytes() } }
            val errThread = Thread { process.errorStream.use { it.readBytes() } }
            outThread.isDaemon = true
            errThread.isDaemon = true
            outThread.start()
            errThread.start()
            val ok = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) && process.exitValue() == 0
            outThread.join(500)
            errThread.join(500)
            ok
        } catch (e: Exception) {
            RemoteLog.d(TAG, "su unavailable: ${e.message}")
            false
        }
    }
}

/**
 * 保活执行器：周期巡检与解绑后一次性恢复共用。
 * 开关关闭时直接跳过（cancel 只是省电优化，判断开关才是唯一真源）。
 */
class KeepAliveWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        // 巡检顺带触发日志上传（待传超阈值才发，平时零流量）
        RemoteLog.maybeUpload("keepalive-work")
        if (!RootKeeper.isKeepAliveOn(context)) return Result.success()
        if (CopyAccessibilityService.isSelfEnabled(context)) return Result.success()

        val prefs = context.getSharedPreferences("lightcopy_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_recovery_ms", 0) < 30_000L) return Result.success()

        if (RootKeeper.ensureServiceEnabled(context)) {
            prefs.edit().putLong("last_recovery_ms", now).apply()
            Toast.makeText(context, R.string.toast_keepalive_recovered, Toast.LENGTH_SHORT).show()
        }
        return Result.success()
    }
}
