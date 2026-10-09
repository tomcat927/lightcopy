package com.tomcat927.lightcopy

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * 无障碍服务：进程常驻单例，唯一的悬浮层宿主。
 * 瓦片直接静态调用 instance.toggleCopyMode()，不走 startService
 * （Android 14+ 从 TileService startService 会被系统阻断，且本服务本就常驻无需唤起）。
 */
class CopyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LightCopy"

        @Volatile
        var instance: CopyAccessibilityService? = null
            private set

        /** 本服务当前是否处于系统无障碍开关开启状态（兼容完整/短两种组件写法） */
        fun isSelfEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val cn = ComponentName(context, CopyAccessibilityService::class.java)
            val serviceListed = enabled.split(':').any {
                it.equals(cn.flattenToShortString(), ignoreCase = true) ||
                    it.equals(cn.flattenToString(), ignoreCase = true)
            }
            val accessibilityMasterOn = Settings.Secure.getInt(
                context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
            ) == 1
            return serviceListed && accessibilityMasterOn
        }
    }

    private var overlay: CopyModeOverlay? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val isCopyModeActive: Boolean
        get() = overlay != null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        RemoteLog.i(TAG, "accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        RemoteLog.w(TAG, "accessibility service unbound")
        teardown()
        // 保活开启时排一个 20 秒后的一次性恢复：开关被 ROM 翻掉能在进程存活期间拉回
        RootKeeper.onServiceUnbound(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** 已在选择模式则先 dismiss（再点瓦片=取消），否则进入选择模式 */
    fun toggleCopyMode() {
        RemoteLog.d(TAG, "copy mode toggle requested active=${overlay != null}")
        mainHandler.post {
            // 服务可能在排队期间被关闭（instance 已清空），此时不再加窗
            if (instance !== this) {
                RemoteLog.w(TAG, "copy mode toggle dropped: service instance changed before dispatch")
                return@post
            }
            if (overlay == null) enterCopyMode() else exitCopyMode()
        }
    }

    private fun enterCopyMode() {
        if (overlay != null) return
        val startedAt = SystemClock.elapsedRealtime()
        RemoteLog.i(TAG, "copy mode enter: collecting screen text")
        val blocks = try {
            TextBlockCollector.collect(this)
        } catch (e: Exception) {
            RemoteLog.e(TAG, "copy mode enter: text collection failed", e)
            Toast.makeText(this, R.string.toast_no_text, Toast.LENGTH_SHORT).show()
            return
        }
        RemoteLog.i(
            TAG,
            "copy mode enter: collected ${blocks.size} blocks in ${SystemClock.elapsedRealtime() - startedAt}ms",
        )
        if (blocks.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_text, Toast.LENGTH_SHORT).show()
            return
        }

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val newOverlay = CopyModeOverlay(
            context = this,
            blocks = blocks,
            onCopy = { text ->
                copyToClipboard(text)
                exitCopyMode()
            },
            onCopyAll = { text, count ->
                copyAllToClipboard(text, count)
                exitCopyMode()
            },
            onDismiss = { exitCopyMode() },
        )

        // 窗口类型优先 TYPE_ACCESSIBILITY_OVERLAY（免「显示在其他应用上层」权限），
        // 异常时回落 TYPE_APPLICATION_OVERLAY
        try {
            windowManager.addView(newOverlay, buildOverlayParams(fallback = false))
            overlay = newOverlay
            RemoteLog.d(TAG, "copy mode on: ${blocks.size} blocks")
        } catch (first: Exception) {
            RemoteLog.w(TAG, "TYPE_ACCESSIBILITY_OVERLAY failed, falling back", first)
            try {
                windowManager.addView(newOverlay, buildOverlayParams(fallback = true))
                overlay = newOverlay
                RemoteLog.d(TAG, "copy mode on (fallback window type): ${blocks.size} blocks")
            } catch (second: Exception) {
                RemoteLog.e(TAG, "add overlay window failed", second)
                newOverlay.release()
                Toast.makeText(this, R.string.toast_overlay_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exitCopyMode() {
        val current = overlay ?: return
        overlay = null
        current.release()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 逐段 runCatching 拆窗：残留的全屏直触窗会吞掉整屏触摸，是最危险的故障形态
        runCatching { windowManager.removeViewImmediate(current) }
            .onFailure { runCatching { windowManager.removeView(current) } }
        RemoteLog.d(TAG, "copy mode off")
    }

    private fun teardown() {
        exitCopyMode()
        if (instance === this) instance = null
    }

    private fun buildOverlayParams(fallback: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (fallback) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // 不设 FLAG_NOT_FOCUSABLE：悬浮窗要接收返回键退出
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clip_label), text))
        val preview = if (text.length > 24) text.take(24) + "…" else text
        Toast.makeText(this, getString(R.string.toast_copied, preview), Toast.LENGTH_SHORT).show()
        RemoteLog.d(TAG, "copied ${text.length} chars")
    }

    private fun copyAllToClipboard(text: String, count: Int) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clip_label), text))
        Toast.makeText(this, getString(R.string.toast_copied_all, count), Toast.LENGTH_SHORT).show()
        RemoteLog.d(TAG, "copied all: $count blocks, ${text.length} chars")
    }
}
