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

        /**
         * 派发"收起通知栏"后、开始采集屏幕文字前的等待。
         * 面板收起动画 + 窗口层级更新需要一点时间，太早采集仍会拿到 SystemUI 的窗口。
         */
        private const val SHADE_COLLAPSE_WAIT_MS = 350L

        /**
         * 采集重试：面板刚收起的过渡态里 getWindows() 可能仍只暴露 SystemUI。
         * 若首采结果为 0 块就按此间隔再试，最多 [COLLECT_MAX_ATTEMPTS] 次。
         */
        private const val COLLECT_RETRY_WAIT_MS = 250L
        private const val COLLECT_MAX_ATTEMPTS = 4

        @Volatile
        var instance: CopyAccessibilityService? = null
            private set

        /** 本服务当前是否处于系统无障碍开关开启状态（兼容完整/短两种组件写法） */
        fun isSelfEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val cn = ComponentName(context, CopyAccessibilityService::class.java)
            // 列表可能被第三方工具写入空条目/尾部多余分隔符，split 后要过滤空串再比较，
            // 否则空条目既会干扰匹配判断，也说明这份列表本身是脏的。
            val serviceListed = enabled.split(':').any {
                val item = it.trim()
                item.isNotEmpty() && (
                    item.equals(cn.flattenToShortString(), ignoreCase = true) ||
                        item.equals(cn.flattenToString(), ignoreCase = true)
                    )
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
        // 第一步：先收起通知栏 / QS 面板。
        // 点瓦片时面板是展开的，此时 getWindows() 顶层窗口是 SystemUI，
        // 直接采集只会拿到通知栏自己的短文本（实测 roots=[com.android.systemui]，23 个块），
        // 底下的真实页面文字采不到 → 选择模式形同虚设。
        // 收起动作是异步的，因此把"采集"推到等待之后再执行，避免阻塞主线程。
        collapseShade()
        mainHandler.postDelayed({ collectAndShowOverlay() }, SHADE_COLLAPSE_WAIT_MS)
    }

    /** 采集屏幕文字并挂上选择悬浮层（须在通知栏已收起后调用） */
    private fun collectAndShowOverlay(attempt: Int = 1) {
        if (overlay != null) return
        val startedAt = SystemClock.elapsedRealtime()
        RemoteLog.i(TAG, "copy mode enter: collecting screen text (attempt=$attempt)")
        val blocks = try {
            // 必须排除 SystemUI：通知栏/QS 面板很可能还在收拢中，
            // 不排除就会把整屏文字采成通知栏自己的一堆短文本（实测 roots=[com.android.systemui]）。
            TextBlockCollector.collect(this, excludeSystemUi = true)
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
            // 面板收拢动画尚未结束时，底层 App 的窗口可能这一刻还读不到 → 稍后重试，
            // 而不是立刻报"没找到文字"让用户以为功能坏了。
            if (attempt < COLLECT_MAX_ATTEMPTS) {
                mainHandler.postDelayed(
                    { collectAndShowOverlay(attempt + 1) },
                    COLLECT_RETRY_WAIT_MS,
                )
                return
            }
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

    /**
     * 收起通知栏 / QS 面板。返回系统是否接受了这次收起请求。
     *
     * 为什么必须做：点瓦片时通知栏是展开的，此时 `getWindows()` 的顶层窗口是 SystemUI，
     * 采集到的"屏幕文字"其实是通知栏自己的一堆短文本（实测 roots=[com.android.systemui]），
     * 底下的真实页面文字根本采不到 → 选择模式形同虚设。
     *
     * 为什么用 `performGlobalAction(GLOBAL_ACTION_BACK)`：
     * - 由系统执行，**不创建任何窗口**（瓦片里若造窗口会令无障碍绑定卡死，已踩过坑）；
     * - 无障碍服务已绑定时即可用，权限足够；
     * - 比 `ACTION_CLOSE_SYSTEM_DIALOGS` 广播可靠（该广播自 Android 12 起受限）。
     *
     * 注意返回值只表示"收起动作已派发成功"，不代表面板已完全收起 —— 收拢是异步动画。
     * 因此调用方仍需等 [SHADE_COLLAPSE_WAIT_MS]，并由采集侧的 SystemUI 排除 + 重试兜底。
     */
    private fun collapseShade(): Boolean {
        val ok = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
            .onFailure { RemoteLog.w(TAG, "collapse shade: global back failed", it) }
            .getOrDefault(false)
        RemoteLog.d(TAG, "collapse shade: global back dispatched=$ok")
        return ok
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
