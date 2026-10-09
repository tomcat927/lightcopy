package com.tomcat927.lightcopy

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 快捷设置瓦片「复制模式」：用户习惯的触发入口。
 * 不 addView、不 startService——无障碍服务本就常驻，直接静态调它的单例。
 */
class CopyModeTileService : TileService() {

    companion object {
        private const val TAG = "LightCopy"

        /**
         * 系统写回设置后 bind 服务通常在几百毫秒内（实测 150~170ms），3 秒足够。
         * 面板收起改由无障碍服务用 performGlobalAction 完成，不在此处造窗口，无额外延迟。
         */
        private const val BIND_WAIT_MS = 3_000L
        /**
         * 重绑后的等待。重绑现在是「摘除 700ms → 挂回 → settle 1200ms」，
         * 不再翻转总开关（实证翻转会让系统重绑时丢服务），所以不必给太久。
         */
        private const val BIND_WAIT_LONG_MS = 6_000L
        private const val BIND_POLL_MS = 100L
        /**
         * 点瓦片后、开始恢复绑定前，等 QS 面板收起动画走完的时间。
         *
         * 为什么必须等：本 ROM 的 AccessibilityManagerService 绑定无障碍服务时要走
         * "添加窗口"并等待客户端响应，而 QS 面板的展开/收拢动画会与它抢窗口资源 →
         * 绑定超时 → 服务永久卡在 `Binding services`（logcat:
         * `wait for adding window timeout: <pid>`）。实测"纯后台无窗口事务"的恢复路径
         * 能在 150ms 内绑上，而"瓦片点击（面板展开中）"路径必失败。
         */
        private const val QS_SETTLE_WAIT_MS = 2_000L
        private val startupInProgress = AtomicBoolean(false)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onClick() {
        super.onClick()
        val service = CopyAccessibilityService.instance
        RemoteLog.i(
            TAG,
            "tile: click bound=${service != null} enabled=${CopyAccessibilityService.isSelfEnabled(this)} sdk=${Build.VERSION.SDK_INT}",
        )
        // 不在这里做任何"造窗口"的操作（曾经用 showDialog(空 Dialog) 折 QS 面板）。
        // 实证：那个 Dialog 会让系统的无障碍窗口添加流程卡超时
        // （logcat: AccessibilityManagerService: wait for adding window timeout: <pid>），
        // 导致无障碍服务永远停在 Binding services、bind 永不完成 —— 这是瓦片点了没反应的根因。
        //
        // 收起面板这件事改由 CopyAccessibilityService 在进入复制模式时用
        // performGlobalAction(GLOBAL_ACTION_BACK) 完成（系统执行、不造窗口，安全）。
        // 这里不能指望"onClick 返回后系统自动收起" —— 实测不会，面板会留在原位，
        // 导致采集到的全是通知栏文字（roots=[com.android.systemui]）。

        if (service != null) {
            RemoteLog.d(TAG, "tile: toggle requested on bound service")
            service.toggleCopyMode()
            mainHandler.post { updateTile(service.isCopyModeActive) }
            return
        }

        if (!startupInProgress.compareAndSet(false, true)) {
            Toast.makeText(this, R.string.toast_tile_starting, Toast.LENGTH_SHORT).show()
            RemoteLog.d(TAG, "tile: startup already in progress")
            return
        }

        // 先给用户明确反馈。root 首次授权可能要等用户在 Magisk/KernelSU 中确认，
        // 但所有阻塞操作都留在后台线程，不能让瓦片看起来像完全没响应。
        Toast.makeText(this, R.string.toast_tile_starting, Toast.LENGTH_LONG).show()
        val appContext = applicationContext
        Thread({
            val startedAt = SystemClock.elapsedRealtime()
            try {
                RemoteLog.i(
                    TAG,
                    "tile: service recovery begin enabled=${CopyAccessibilityService.isSelfEnabled(appContext)}",
                )
                val enabled = RootKeeper.ensureServiceEnabled(appContext, suTimeoutMs = 15_000L)
                RemoteLog.i(
                    TAG,
                    "tile: enable attempt result=$enabled elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                )
                if (!enabled) {
                    mainHandler.post {
                        Toast.makeText(appContext, R.string.toast_enable_a11y_first, Toast.LENGTH_LONG).show()
                        openAccessibilitySettings()
                        updateTile(false)
                    }
                    return@Thread
                }

                var bound = awaitInstance(BIND_WAIT_MS)
                RemoteLog.i(
                    TAG,
                    "tile: initial bind wait bound=${bound != null} elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                )
                if (bound == null) {
                    // 设置里已启用但服务没被系统绑定（热更新/进程死亡后的僵死状态，
                    // 设置值不变就不会触发重绑）。
                    //
                    // 关键修正：**要等 QS 面板彻底收起**再恢复。用户点瓦片 = 面板展开/动画中，
                    // 而本 ROM 的 AccessibilityManagerService 在 bind 时要走"添加窗口"并等待
                    // 客户端响应，面板动画会与它抢资源 → 超时 → 服务永久卡在 Binding services
                    // （logcat 实证 `wait for adding window timeout`）。
                    // 因此这里先静默 2 秒让面板动画走完，再交给统一的空闲恢复流程。
                    RemoteLog.i(
                        TAG,
                        "tile: unbound, waiting for QS panel to settle before recovery " +
                            "sinceProcStartMs=${SystemClock.elapsedRealtime() - CopyAccessibilityService.processStartAt}",
                    )
                    Thread.sleep(QS_SETTLE_WAIT_MS)
                    RemoteLog.i(
                        TAG,
                        "tile: pre-recovery state dump [${RootKeeper.dumpAccessibilityState()}]",
                    )
                    // 统一走"空闲时机恢复"：写对设置 → 轻量重绑 → 仍不行则进程级重启。
                    RootKeeper.recoverBindingWhenIdle(appContext)
                    bound = awaitInstance(BIND_WAIT_LONG_MS)
                    RemoteLog.i(
                        TAG,
                        "tile: after recoverBindingWhenIdle bound=${bound != null} " +
                            "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                    )
                }

                mainHandler.post {
                    val svc = bound ?: CopyAccessibilityService.instance
                    if (svc != null) {
                        Toast.makeText(appContext, R.string.toast_tile_auto_enabled, Toast.LENGTH_SHORT).show()
                        RemoteLog.i(TAG, "tile: service connected, entering copy mode")
                        svc.toggleCopyMode()
                        mainHandler.post { updateTile(svc.isCopyModeActive) }
                    } else {
                        // 走到这里说明设置层面全部成功但系统始终不 bind。
                        // 只有系统视角（dumpsys）能解释原因，务必抓下来。
                        RemoteLog.w(
                            TAG,
                            "tile: service still unbound after recovery elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                        )
                        RemoteLog.w(
                            TAG,
                            "tile: accessibility state dump [${RootKeeper.dumpAccessibilityState()}]",
                        )
                        Toast.makeText(appContext, R.string.toast_tile_wait_bind, Toast.LENGTH_LONG).show()
                        openAccessibilitySettings()
                        updateTile(false)
                    }
                }
            } catch (e: Exception) {
                RemoteLog.e(TAG, "tile: startup failed elapsed=${SystemClock.elapsedRealtime() - startedAt}ms", e)
                mainHandler.post {
                    Toast.makeText(appContext, R.string.toast_tile_wait_bind, Toast.LENGTH_LONG).show()
                    openAccessibilitySettings()
                    updateTile(false)
                }
            } finally {
                startupInProgress.set(false)
            }
        }, "TileCopyMode").apply {
            isDaemon = true
            start()
        }
    }

    /** 轮询等待无障碍服务连接 */
    private fun awaitInstance(timeoutMs: Long): CopyAccessibilityService? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            CopyAccessibilityService.instance?.let { return it }
            Thread.sleep(BIND_POLL_MS)
        }
        return CopyAccessibilityService.instance
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile(CopyAccessibilityService.instance?.isCopyModeActive == true)
    }

    private fun updateTile(active: Boolean) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            // startActivityAndCollapse(PendingIntent) 是 API 34 才新增的重载。
            // 之前误判成 API 31（Build.VERSION_CODES.S）可用，导致 Android 13 设备上
            // 抛 NoSuchMethodError（见 10-09 15:25 日志）。这里严格按 34 分流，
            // 且用反射调用以防在旧 SDK 上被编译期内联/校验拒绝。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                val m = TileService::class.java.getMethod("startActivityAndCollapse", PendingIntent::class.java)
                m.invoke(this, pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { RemoteLog.w(TAG, "open accessibility settings failed", it) }
    }

    /**
     * 折叠 QS 面板**不由本类负责**。
     *
     * 曾实现为「发 ACTION_CLOSE_SYSTEM_DIALOGS / 或 API 31+ 用 showDialog(空 Dialog)」，
     * 但那两条路都有害：
     *  - showDialog(空 Dialog) 会在系统绑定无障碍服务时制造窗口，令
     *    `AccessibilityManagerService` 的「添加窗口」步骤超时，服务永远停在
     *    `Binding services`、bind 永不完成（logcat 实证）。
     *  - ACTION_CLOSE_SYSTEM_DIALOGS 自 API 31 起已受限，且同样是易碎 hack。
     *
     * 现在改由 [CopyAccessibilityService] 在进入复制模式时用
     * `performGlobalAction(GLOBAL_ACTION_BACK)` 折叠 —— 系统执行、不创建窗口，
     * 既不会卡住绑定，也确实能让底层页面文字暴露给采集器。
     * （实测 `TileService.onClick()` 返回后系统**不会**自动收起面板。）
     */
}
