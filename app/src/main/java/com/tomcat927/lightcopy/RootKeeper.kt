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

    /**
     * 清洗 `enabled_accessibility_services` 列表。
     *
     * 该设置是冒号分隔的组件名列表，但实机环境里常被第三方工具（自动化脚本、广告拦截、
     * 各种「助手」类 App）写入脏数据：空条目、重复项、尾部多余分隔符。
     * 10-09 实测回显出现 `A:B:C:com.tomcat927.lightcopy/.CopyAccessibilityService::D`——
     * 中间夹空条目。系统解析这种列表时行为未定义，很可能直接拒绝启用整份列表，
     * 于是服务"设置里看着是启用的，实际永远绑不上"。
     * 因此凡是写回该设置的地方，都必须先过这个清洗。
     */
    private fun sanitizeList(raw: String): String =
        raw.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(":")

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
        // 合并而非覆盖：用户可能同时开着 MT 管理器等其他无障碍服务。
        // 同时清洗脏数据（空条目/重复项），否则系统可能拒绝启用整份列表。
        val merged = current.split(':')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()
            .apply {
                if (none { it.equals(flat, true) || it.equals(cn.flattenToString(), true) }) {
                    add(flat)
                }
            }
            .distinct()
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
                // 注意用 `;` 而非 `&&`：settings put 偶发返回非 0 会让整条链中断，
                // 导致列表写了、master 却没写（服务依旧不会绑），所以两步都要独立执行。
                val script = "settings put secure enabled_accessibility_services '$merged'; " +
                    "settings put secure accessibility_enabled 1"
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

    /** 挂回后等待系统重新 bind 的时间（设置写入是异步的，要留出窗口） */
    private const val REBIND_SETTLE_MS = 1_200L

    /**
     * 强制重绑：设置里服务已启用但实际没被绑定（热更新/进程死亡后的僵死状态，
     * 设置值不变系统不会触发重绑）→ 把本服务从启用列表摘掉再挂回，
     * 两次列表变更触发 AccessibilityManagerService 重新 bind。
     * 只动本服务，其他无障碍服务不受影响。
     *
     * === 关键修正（10-09 16:2x 实证）===
     * 早先的实现在这里额外做了「总开关 accessibility_enabled 1→0→1 翻转」，
     * 那是**错误且有害**的：
     *   - 实测两次"天然绑定成功"（10-07 22:09:02、10-09 13:55:38）都只做了
     *     "写列表 + master=1"，系统 150ms 内就 bind 上了，**从未翻转总开关**。
     *   - 而每次翻转总开关后，`dumpsys accessibility` 显示 Bound services 只剩
     *     先绑上的那一个（AutoJs6），本服务和用户其它 3 个无障碍服务全部卡在
     *     `Binding services` 且 `onServiceConnected` 永不触发。
     *   - 即：翻转总开关会让本 ROM 在"全量解绑 → 重新绑定"过程中把大部分服务丢掉，
     *     既救不活自己，还会打断用户其它正在并行的无障碍服务。
     * 因此这里改为**只改列表、不碰总开关**，让系统按列表变更自然重新 bind。
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
        val currentRaw = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: run {
                RemoteLog.w(TAG, "force rebind skipped: enabled services setting is null")
                return false
            }
        // 关键：先清洗再判断/再写回。
        // 脏列表（空条目/重复项）会让系统解析失败，表现为"设置了但永远绑不上"。
        val current = sanitizeList(currentRaw)
        if (current.split(':').none { it.equals(short, true) || it.equals(full, true) }) {
            RemoteLog.w(TAG, "force rebind skipped: service is not listed in enabled services")
            return false
        }
        val withoutRaw = current.split(':')
            .filter { it.isNotBlank() && !it.equals(short, true) && !it.equals(full, true) }
            .joinToString(":")
        // 空列表哨兵：确保"摘除"这一步一定是一次可见的状态变更
        val without = withoutRaw.ifBlank { REBIND_EMPTY_SENTINEL }
        if (currentRaw != current) {
            RemoteLog.w(
                TAG,
                "force rebind: enabled services list was dirty, sanitized " +
                    "(before='$currentRaw' after='$current')",
            )
        }
        return try {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_SECURE_SETTINGS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // 只改列表，两步：摘除 → 挂回。总开关保持不动（原因见方法头注释）。
                val removed = Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without)
                Thread.sleep(REBIND_GAP_MS)
                val restored = Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, current)
                // 兜底：万一本机总开关被 ROM 关掉了，这里只做"补 1"，绝不主动写 0。
                val master = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
                val masterWritten = if (master == 1) true
                    else Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Thread.sleep(REBIND_SETTLE_MS)
                val enabledAfter = CopyAccessibilityService.isSelfEnabled(context)
                RemoteLog.i(
                    TAG,
                    "force rebind via secure settings removed=$removed restored=$restored " +
                        "masterBefore=$master masterWritten=$masterWritten enabledAfter=$enabledAfter " +
                        "emptiedList=${withoutRaw.isBlank()} elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                )
                // 写入成功 + 服务确实回到了启用列表，才算这次重绑动作生效。
                // 真正"是否绑上"由调用方 awaitInstance 判定（设置生效是异步的）。
                removed && restored && masterWritten && enabledAfter
            } else {
                // 一条 su 命令内完成 摘除→挂回，避免多次弹 su。
                // **不翻转总开关**（原因见方法头注释）：只写列表，末了确保 master=1。
                // 注意：这里不能用 `&&` 串联——settings put 偶发返回非 0 会中断整条链，
                // 导致"摘掉没挂回"，服务被永久关闭。改用 `;` 保证每一步都执行。
                //
                // 关键诊断：每一步后都 `settings get` 回显真实值。
                // 只在 App 侧读设置是不够的——如果 su 里的写入被 SELinux/ROM 静默拒绝，
                // App 侧读到的是"没变"，无法区分"没写成"和"写成了但系统没反应"。
                val script = buildString {
                    append("echo step=remove; settings put secure enabled_accessibility_services '$without'; sleep 0.7; ")
                    append("echo afterRemove=\$(settings get secure enabled_accessibility_services); ")
                    append("echo step=restore; settings put secure enabled_accessibility_services '$current'; ")
                    append("echo afterRestore=\$(settings get secure enabled_accessibility_services); ")
                    append("echo step=master; settings put secure accessibility_enabled 1; ")
                    append("echo afterOn=\$(settings get secure accessibility_enabled); ")
                    // 稳定期后再读一次：若与 afterRestore 不同，说明有第三方工具
                    // （自动化脚本/助手类 App）正在同时改写这份列表，与我们互相打架。
                    append("sleep 2; echo settleList=\$(settings get secure enabled_accessibility_services); ")
                    append("echo settleMaster=\$(settings get secure accessibility_enabled)")
                }
                val (suSucceeded, suOut) = runSuCapture(script, timeoutMs = 20_000)
                Thread.sleep(REBIND_SETTLE_MS)
                val enabledAfter = CopyAccessibilityService.isSelfEnabled(context)
                val masterNow = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, -1)
                RemoteLog.i(
                    TAG,
                    "force rebind via su succeeded=$suSucceeded enabledAfter=$enabledAfter " +
                        "masterNow=$masterNow emptiedList=${withoutRaw.isBlank()} " +
                        "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms detail=[$suOut]",
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

    /**
     * 执行 su 命令。
     * 返回 (是否成功, 标准输出+标准错误)。输出会带进日志——
     * 排查"su 报成功但设置没变"这类问题时，唯一可靠的证据是命令自身的回显。
     */
    private fun runSuCapture(command: String, timeoutMs: Long = 5_000): Pair<Boolean, String> {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            // 排空输出防止管道写满阻塞
            var out = ""
            var err = ""
            val outThread = Thread { out = runCatching { process.inputStream.use { it.readBytes().decodeToString() } }.getOrDefault("") }
            val errThread = Thread { err = runCatching { process.errorStream.use { it.readBytes().decodeToString() } }.getOrDefault("") }
            outThread.isDaemon = true
            errThread.isDaemon = true
            outThread.start()
            errThread.start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val exitCode = if (finished) process.exitValue() else null
            if (!finished) process.destroyForcibly()
            outThread.join(800)
            errThread.join(800)
            val succeeded = finished && exitCode == 0
            val combined = (out.trim() + " " + err.trim()).trim().replace("\n", " | ")
            RemoteLog.i(
                TAG,
                "su command finished succeeded=$succeeded timedOut=${!finished} " +
                    "exitCode=${exitCode ?: -1} out=[$combined] elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
            )
            Pair(succeeded, combined)
        } catch (e: Exception) {
            RemoteLog.w(TAG, "su command failed elapsed=${SystemClock.elapsedRealtime() - startedAt}ms", e)
            Pair(false, "exception:${e.message}")
        }
    }

    private fun runSu(command: String, timeoutMs: Long = 5_000): Boolean =
        runSuCapture(command, timeoutMs).first

    /**
     * 采集系统无障碍服务的真实绑定状态（`dumpsys accessibility` 摘要）。
     *
     * 为什么必须用这个：App 层能读到的只有 `Settings.Secure` 里的开关值，
     * 而"设置说启用、系统不绑定"这种状态，设置值是看不出原因的。
     * `dumpsys accessibility` 里的 `Bound services` / `Enabled services` 才是系统视角的事实，
     * 也是判断"服务是否被系统接受"的唯一权威依据。
     */
    fun dumpAccessibilityState(): String {
        // 只 grep 标题行会漏掉条目内容：dumpsys 里 Bound/Binding/Enabled services 的列表是
        // 换行展开的（每项一行 "Service[...]"），grep 标题只能抓到紧随其后的第一条。
        // 这里按"标题行 + 其后续条目行"一起截取，段间用 --- 分隔；同时抓 logcat 中
        // 与本包相关的绑定失败线索，定位"卡在 Binding 却始终不 Bound"的原因。
        val script = buildString {
            append("dumpsys accessibility 2>/dev/null | grep -A 12 -E ")
            append("'^ *Bound services:|^ *Binding services:|^ *Crashed services:|^ *Enabled services:' ")
            append("| head -80; ")
            append("echo ===LOGCAT===; ")
            append("logcat -d -t 500 2>/dev/null | grep -iE ")
            append("'lightcopy|AccessibilityManagerService|accessibilityservice' | tail -50")
        }
        val (ok, out) = runSuCapture(script, timeoutMs = 15_000)
        return if (ok) out else "dumpsys failed: $out"
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
