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
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * 快捷设置瓦片「复制模式」：用户习惯的触发入口。
 * 不 addView、不 startService——无障碍服务本就常驻，直接静态调它的单例。
 */
class CopyModeTileService : TileService() {

    companion object {
        private const val TAG = "LightCopy"

        /** 系统写回设置后 bind 服务通常在几百毫秒内，这里等最多 3 秒 */
        private const val BIND_WAIT_MS = 3_000L
        private const val BIND_POLL_MS = 100L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onClick() {
        super.onClick()
        collapseQsPanel()

        val service = CopyAccessibilityService.instance
        if (service != null) {
            service.toggleCopyMode()
            mainHandler.post { updateTile(service.isCopyModeActive) }
            return
        }

        // 无障碍未开启：不急着把用户踢去设置，先尝试自动开启
        // （adb 授予的 WRITE_SECURE_SETTINGS 免弹窗；root 则首次会弹 Magisk 授权框），
        // 成功就等服务 bind 后直接进复制模式，失败才提示跳设置。
        // su 阻塞绝不能发生在主线程（onClick 在主线程，会 ANR），放后台线程。
        val appContext = applicationContext
        Thread {
            val enabled = RootKeeper.ensureServiceEnabled(appContext, suTimeoutMs = 15_000L)
            if (!enabled) {
                mainHandler.post {
                    Toast.makeText(appContext, R.string.toast_enable_a11y_first, Toast.LENGTH_LONG).show()
                    openAccessibilitySettings()
                    updateTile(false)
                }
                return@Thread
            }

            var bound: CopyAccessibilityService? = CopyAccessibilityService.instance
            var waited = 0L
            while (bound == null && waited < BIND_WAIT_MS) {
                Thread.sleep(BIND_POLL_MS)
                waited += BIND_POLL_MS
                bound = CopyAccessibilityService.instance
            }

            mainHandler.post {
                val svc = bound ?: CopyAccessibilityService.instance
                if (svc != null) {
                    Toast.makeText(appContext, R.string.toast_tile_auto_enabled, Toast.LENGTH_SHORT).show()
                    svc.toggleCopyMode()
                    mainHandler.post { updateTile(svc.isCopyModeActive) }
                } else {
                    // 已写回设置但绑定还没完成（极少数慢场景），让用户再点一次即可
                    Toast.makeText(appContext, R.string.toast_tile_wait_bind, Toast.LENGTH_LONG).show()
                    updateTile(false)
                }
            }
        }.start()
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
