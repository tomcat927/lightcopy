package com.tomcat927.lightcopy

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
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

    /**
     * 「摘除本服务后列表为空」时写入的哨兵组件名。
     * 必须是合法组件名格式（包名/类名），且指向不存在的组件，
     * 这样系统会把它当成一次真实的服务列表变更（触发解绑本服务），
     * 又不会真的绑定到任何东西。挂回那一步会把它整个覆盖掉。
     */
    private const val REBIND_EMPTY_SENTINEL = "com.tomcat927.lightcopy/.RebindNoop"

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
        val startedAt = SystemClock.elapsedRealtime()
        if (CopyAccessibilityService.isSelfEnabled(context)) {
            RemoteLog.d(TAG, "enable service: already enabled")
            return true
        }
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
        val hasWriteSecureSettings = ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

        RemoteLog.i(TAG, "enable service: begin writeSecureSettings=$hasWriteSecureSettings suTimeout=${suTimeoutMs}ms")
        return try {
            if (hasWriteSecureSettings) {
                val listWritten = Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged
                )
                val masterWritten = Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                val enabledAfter = CopyAccessibilityService.isSelfEnabled(context)
                RemoteLog.i(
                    TAG,
                    "enable service: secure settings listWrite=$listWritten masterWrite=$masterWritten " +
                        "enabledAfter=$enabledAfter elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                )
                enabledAfter
            } else {
                val script = "settings put secure enabled_accessibility_services '$merged'" +
                    " && settings put secure accessibility_enabled 1"
                val suSucceeded = runSu(script, timeoutMs = suTimeoutMs)
                val enabledAfter = CopyAccessibilityService.isSelfEnabled(context)
                RemoteLog.i(
                    TAG,
                    "enable service: suSucceeded=$suSucceeded enabledAfter=$enabledAfter " +
                        "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                )
                suSucceeded && enabledAfter
            }
        } catch (e: Exception) {
            RemoteLog.e(TAG, "enable service: recovery failed elapsed=${SystemClock.elapsedRealtime() - startedAt}ms", e)
            false
        }
    }

    /** 摘除后到挂回之间的等待：太短系统来不及处理解绑，太长用户会看到服务闪断 */
    private const val REBIND_GAP_MS = 700L

    /**
     * 关掉总开关后到改服务列表之间的等待。
     * AccessibilityManagerService 处理"总开关关闭"是异步的，需要给它时间真正解绑全部服务，
     * 否则随后的 1→0→1 翻转会被合并成一次无变化写入，重绑仍然不生效。
     */
    private const val REBIND_MASTER_OFF_MS = 800L

    /** 挂回后等待系统重新 bind 的时间（设置写入是异步的，要留出窗口） */
    private const val REBIND_SETTLE_MS = 1_200L

    /**
     * 强制重绑：设置里服务已启用但实际没被绑定（热更新/进程死亡后的僵死状态，
     * 设置值不变系统不会触发重绑）→ 把本服务从启用列表摘掉再挂回，
     * 两次设置变更必然触发 AccessibilityManagerService 先解绑再绑定。
     * 只动本服务，其他无障碍服务不受影响。
     *
     * 关键：**摘除后列表可能为空**（本机只开本服务时）。
     * 空字符串写入 `enabled_accessibility_services` 在多数 ROM 上会被当作无效变更忽略，
     * 系统看不到"服务消失"这一步 → 只当成一次无意义的重复写入 → 不触发重绑。
     * 因此列表为空时改写成一个必然无效的哨兵组件名，保证这一步是一次真实的状态变更。
     */
    fun forceRebind(context: Context): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        val cr = context.contentResolver
        val cn = ComponentName(context, CopyAccessibilityService::class.java)
        val short = cn.flattenToShortString()
        val full = cn.flattenToString()
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: run {
                RemoteLog.w(TAG, "force rebind skipped: enabled services setting is null")
                return false
            }
        if (current.split(':').none { it.equals(short, true) || it.equals(full, true) }) {
            RemoteLog.w(TAG, "force rebind skipped: service is not listed in enabled services")
            return false
        }
        val withoutRaw = current.split(':')
            .filter { it.isNotBlank() && !it.equals(short, true) && !it.equals(full, true) }
            .joinToString(":")
        // 空列表哨兵：确保"摘除"这一步一定是一次可见的状态变更
        val without = withoutRaw.ifBlank { REBIND_EMPTY_SENTINEL }
        return try {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_SECURE_SETTINGS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // 第一步：把总开关真正关掉。这是重绑能生效的关键——
                // 只改 enabled_accessibility_services 列表时，若总开关始终是 1，
                // AccessibilityManagerService 看到的是"1→1"无变化，不会重新 bind。
                // 必须让它经历 1→0→1 才会触发全量解绑 + 重新绑定。
                val masterOff = Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
                Thread.sleep(REBIND_MASTER_OFF_MS)
                val removed = Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without)
                Thread.sleep(REBIND_GAP_MS)
                val restored = Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, current)
                val masterOn = Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Thread.sleep(REBIND_SETTLE_MS)
                val enabledAfter = CopyAccessibilityService.isSelfEnabled(context)
                RemoteLog.i(
                    TAG,
                    "force rebind via secure settings masterOff=$masterOff removed=$removed " +
                        "restored=$restored masterOn=$masterOn enabledAfter=$enabledAfter " +
                        "emptiedList=${withoutRaw.isBlank()} elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                )
                // 写入成功 + 服务确实回到了启用列表，才算这次重绑动作生效。
                // 真正"是否绑上"由调用方 awaitInstance 判定（设置生效是异步的）。
                masterOff && removed && restored && masterOn && enabledAfter
            } else {
                // 一条 su 命令内完成 关总开关→摘除→挂回→开总开关，避免多次弹 su。
                // 注意：这里不能用 `&&` 串联——settings put 偶发返回非 0 会中断整条链，
                // 导致"摘掉没挂回"或"总开关停在 0"，服务被永久关闭。改用 `;` 保证每一步都执行。
                val script = "settings put secure accessibility_enabled 0; sleep 0.5; " +
                    "settings put secure enabled_accessibility_services '$without'; sleep 0.7; " +
                    "settings put secure enabled_accessibility_services '$current'; " +
                    "settings put secure accessibility_enabled 1"
                val suSucceeded = runSu(script, timeoutMs = 20_000)
                Thread.sleep(REBIND_SETTLE_MS)
                val enabledAfter = CopyAccessibilityService.isSelfEnabled(context)
                RemoteLog.i(
                    TAG,
                    "force rebind via su succeeded=$suSucceeded enabledAfter=$enabledAfter " +
                        "emptiedList=${withoutRaw.isBlank()} elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                )
                suSucceeded && enabledAfter
            }
        } catch (e: Exception) {
            RemoteLog.e(TAG, "force rebind failed elapsed=${SystemClock.elapsedRealtime() - startedAt}ms", e)
            // 兜底：确保服务没有被我们留在"已摘除 / 总开关关闭"状态
            runCatching {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, current)
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
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
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            // 排空输出防止管道写满阻塞
            val outThread = Thread { process.inputStream.use { it.readBytes() } }
            val errThread = Thread { process.errorStream.use { it.readBytes() } }
            outThread.isDaemon = true
            errThread.isDaemon = true
            outThread.start()
            errThread.start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val exitCode = if (finished) process.exitValue() else null
            if (!finished) process.destroyForcibly()
            outThread.join(500)
            errThread.join(500)
            val succeeded = finished && exitCode == 0
            RemoteLog.i(
                TAG,
                "su command finished succeeded=$succeeded timedOut=${!finished} " +
                    "exitCode=${exitCode ?: -1} elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
            )
            succeeded
        } catch (e: Exception) {
            RemoteLog.w(TAG, "su command failed elapsed=${SystemClock.elapsedRealtime() - startedAt}ms", e)
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
