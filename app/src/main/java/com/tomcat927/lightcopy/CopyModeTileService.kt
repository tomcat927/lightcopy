package com.tomcat927.lightcopy

import android.app.Dialog
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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

        /** 系统写回设置后 bind 服务通常在几百毫秒内，这里等最多 3 秒 */
        private const val BIND_WAIT_MS = 3_000L
        /** 强制重绑后的等待稍微放宽 */
        private const val BIND_WAIT_LONG_MS = 5_000L
        private const val BIND_POLL_MS = 100L
        /** 重绑最多尝试轮数：系统 bind 异步且可能被 ROM 节流，一次不成很常见 */
        private const val REBIND_MAX_ATTEMPTS = 2
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
        collapseQsPanel()

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
                    // 设置值不变就不会触发重绑）→ 把本服务摘掉再挂回，强制系统重新 bind。
                    // 系统 bind 是异步的且可能被 ROM 节流，一次不成很常见，这里给两轮机会。
                    var attempt = 0
                    while (bound == null && attempt < REBIND_MAX_ATTEMPTS) {
                        attempt++
                        val rebound = RootKeeper.forceRebind(appContext)
                        RemoteLog.i(
                            TAG,
                            "tile: force rebind attempt=$attempt result=$rebound " +
                                "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                        )
                        if (!rebound) {
                            // 设置层没写成功（无 root/WSS，或服务已不在启用列表），
                            // 再重试也没意义 → 跳出交给用户手动处理
                            break
                        }
                        bound = awaitInstance(BIND_WAIT_LONG_MS)
                        RemoteLog.i(
                            TAG,
                            "tile: rebind attempt=$attempt wait bound=${bound != null} " +
                                "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                        )
                    }
                }

                mainHandler.post {
                    val svc = bound ?: CopyAccessibilityService.instance
                    if (svc != null) {
                        Toast.makeText(appContext, R.string.toast_tile_auto_enabled, Toast.LENGTH_SHORT).show()
                        RemoteLog.i(TAG, "tile: service connected, entering copy mode")
                        svc.toggleCopyMode()
                        mainHandler.post { updateTile(svc.isCopyModeActive) }
                    } else {
                        RemoteLog.w(
                            TAG,
                            "tile: service still unbound after recovery elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { RemoteLog.w(TAG, "open accessibility settings failed", it) }
    }

    /**
     * 先折叠 QS 面板再进选择模式：
     * - API 31+：showDialog(Dialog) 是官方 API，文档行为即「收起 QS 面板并显示对话框」；
     *   配一个透明空对话框，显示后立即 dismiss，面板收掉了也不留可见痕迹
     * - S 以下：发 ACTION_CLOSE_SYSTEM_DIALOGS 广播（API 31 起该广播受限，走上面的路）
     */
    private fun collapseQsPanel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val dialog = Dialog(this).apply {
                    window?.apply {
                        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                        setDimAmount(0f)
                    }
                }
                showDialog(dialog)
                // dialog.show 内部是 post 到主线程的，紧随其后 post dismiss 保证 show 先执行
                Handler(Looper.getMainLooper()).post { dialog.dismiss() }
            }.onFailure { RemoteLog.w(TAG, "collapse via showDialog failed", it) }
        } else {
            runCatching { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) }
        }
    }
}
