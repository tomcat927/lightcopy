package com.tomcat927.lightcopy

import android.app.Dialog
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.DialogFragment

/**
 * 快捷设置瓦片「复制模式」：用户习惯的触发入口。
 * 不 addView、不 startService——无障碍服务本就常驻，直接静态调它的单例。
 */
class CopyModeTileService : TileService() {

    companion object {
        private const val TAG = "LightCopy"
    }

    override fun onClick() {
        super.onClick()
        collapseQsPanel()

        val service = CopyAccessibilityService.instance
        if (service == null) {
            // 无障碍未开启：提示 + 跳系统无障碍设置
            Toast.makeText(this, R.string.toast_enable_a11y_first, Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            updateTile(active = false)
        } else {
            service.toggleCopyMode()
            // toggle 内部 post 到主线程执行，这里同样 post 保证读到切换后的状态
            Handler(Looper.getMainLooper()).post { updateTile(service.isCopyModeActive) }
        }
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
        }.onFailure { Log.w(TAG, "open accessibility settings failed", it) }
    }

    /**
     * 先折叠 QS 面板再进选择模式：
     * - API 31+：showDialog + 立即 dismiss —— showDialog 会让系统收起面板，空对话框随即撤掉
     * - S 以下：发 ACTION_CLOSE_SYSTEM_DIALOGS 广播（API 31 起该广播受限，走上面的路）
     */
    private fun collapseQsPanel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { showDialog(PanelCollapseFragment()) }
                .onFailure { Log.w(TAG, "collapse via showDialog failed", it) }
        } else {
            runCatching { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) }
        }
    }

    /** 空对话框：透明无阴影，显示后立即自行关闭，不留可见痕迹 */
    private class PanelCollapseFragment : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val dialog = super.onCreateDialog(savedInstanceState)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(0f)
            }
            return dialog
        }

        override fun onStart() {
            super.onStart()
            dismissAllowingStateLoss()
        }
    }
}
