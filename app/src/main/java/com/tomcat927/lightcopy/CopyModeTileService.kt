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
        private const val BIND_POLL_MS = 100L
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

        // 先给用户明确反馈。所有阻塞操作都留在后台线程，
        // 不能让瓦片看起来像完全没响应。
        Toast.makeText(this, R.string.toast_tile_starting, Toast.LENGTH_LONG).show()
        val appContext = applicationContext
        Thread({
            val startedAt = SystemClock.elapsedRealtime()
            try {
                // === 方案 C：只检测 + 引导，绝不写设置、绝不重绑 ===
                // 原因：本 ROM 上每次写 `enabled_accessibility_services` 都会触发一次新的 bind，
                // 而 bind 要走「添加窗口」，被窗口事务（尤其 QS 面板动画、App 冷启动闪屏）打断后
                // 就永久停在 `Binding services`。实测重绑次数与卡死次数正相关、与服务真正连通负相关。
                // 因此这里不再尝试"自动修复"，只判断状态并引导用户手动关/开一次
                // （这是 AutoJS6 等成熟实现验证过的唯一稳定做法）。
                val state = RootKeeper.checkBinding(appContext)
                RemoteLog.i(
                    TAG,
                    "tile: click state=$state elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                )

                // 已绑上：直接进入复制模式。
                if (state == RootKeeper.BindingCheck.BOUND) {
                    val svc = CopyAccessibilityService.instance
                    if (svc != null) {
                        mainHandler.post {
                            svc.toggleCopyMode()
                            mainHandler.post { updateTile(svc.isCopyModeActive) }
                        }
                        return@Thread
                    }
                }

                // 未绑上：给系统一点点时间（刚开机/刚更新后的首次 bind 可能正在路上），
                // 短暂等待可以避免误报，也绝不涉及任何设置写入。
                val bound = awaitInstance(BIND_WAIT_MS)
                RemoteLog.i(
                    TAG,
                    "tile: initial bind wait bound=${bound != null} " +
                        "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                )
                if (bound != null) {
                    mainHandler.post {
                        Toast.makeText(appContext, R.string.toast_tile_auto_enabled, Toast.LENGTH_SHORT).show()
                        bound.toggleCopyMode()
                        mainHandler.post { updateTile(bound.isCopyModeActive) }
                    }
                    return@Thread
                }

                // 仍未绑上 → 只引导，不动设置。
                RemoteLog.w(
                    TAG,
                    "tile: service not bound (state=$state), guiding user to system settings " +
                        "(no settings write, no rebind). " +
                        "stateDump=[${RootKeeper.dumpAccessibilityState()}]",
                )
                mainHandler.post {
                    val msgRes = if (state == RootKeeper.BindingCheck.ENABLED_ONLY) {
                        R.string.toast_tile_need_manual_toggle
                    } else {
                        R.string.toast_enable_a11y_first
                    }
                    Toast.makeText(appContext, msgRes, Toast.LENGTH_LONG).show()
                    openAccessibilitySettings()
                    updateTile(false)
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
