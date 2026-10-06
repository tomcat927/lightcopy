package com.tomcat927.lightcopy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 复制模式悬浮层：全屏直触窗口（不设 FLAG_NOT_FOCUSABLE，可收返回键）。
 *
 * 坐标约定：TextBlock.bounds 是屏幕坐标，绘制时用 getLocationOnScreen 求偏移换算到视图坐标
 * （不手算状态栏高度）；命中测试直接用 event.rawX/rawY 对屏幕坐标，两套坐标天然一致。
 */
class CopyModeOverlay(
    context: Context,
    private val blocks: List<TextBlock>,
    private val onCopy: (String) -> Unit,
    private val onCopyAll: (String, Int) -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {

    companion object {
        private const val TIMEOUT_MS = 30_000L
        private const val SCRIM_COLOR = 0x28000000
        private const val BLOCK_FILL_COLOR = 0x18FFFFFF
        private const val BLOCK_STROKE_COLOR = 0x44FFFFFF
        private const val TOOLBAR_BG_COLOR = 0xE6212124
        private const val BUTTON_BG_COLOR = 0x33FFFFFF
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun dpInt(v: Float): Int = dp(v).toInt()

    private val cornerRadius = dp(4f)
    private val tapPadding = dp(10f)

    private val scrimPaint = Paint().apply { color = SCRIM_COLOR }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BLOCK_FILL_COLOR
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BLOCK_STROKE_COLOR
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    /** 与 blocks 一一对应的视图坐标矩形 */
    private var localRects: List<RectF> = emptyList()
    private var downHitIndex = -1
    private lateinit var toolbar: LinearLayout

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { onDismiss() }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) onDismiss()
        }
    }

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        // 熄屏兜底退出
        ContextCompat.registerReceiver(
            context, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // 30 秒超时兜底退出
        timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        buildToolbar()

        // 工具条避开手势条/导航栏
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            (toolbar.layoutParams as LayoutParams).apply {
                bottomMargin = dpInt(24f) + bars.bottom
                leftMargin = dpInt(16f) + bars.left
                rightMargin = dpInt(16f) + bars.right
            }
            toolbar.requestLayout()
            insets
        }
    }

    private fun buildToolbar() {
        toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpInt(10f), dpInt(10f), dpInt(10f), dpInt(10f))
            background = GradientDrawable().apply {
                setColor(TOOLBAR_BG_COLOR)
                cornerRadius = dp(16f)
            }
        }
        toolbar.addView(
            makeBarButton(context.getString(R.string.btn_copy_all)) {
                onCopyAll(blocks.joinToString("\n") { it.text }, blocks.size)
            },
            linearWeight(1f),
        )
        toolbar.addView(
            makeBarButton(context.getString(R.string.btn_close)) { onDismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpInt(10f)
            },
        )
        addView(
            toolbar,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = dpInt(24f)
            },
        )
    }

    private fun makeBarButton(text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            setPadding(0, dpInt(10f), 0, dpInt(10f))
            background = GradientDrawable().apply {
                setColor(BUTTON_BG_COLOR)
                cornerRadius = dp(12f)
            }
            setOnClickListener { onClick() }
        }

    private fun linearWeight(weight: Float) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val location = IntArray(2)
        getLocationOnScreen(location)
        val offsetX = location[0].toFloat()
        val offsetY = location[1].toFloat()
        localRects = blocks.map { block ->
            RectF(
                block.bounds.left - offsetX,
                block.bounds.top - offsetY,
                block.bounds.right - offsetX,
                block.bounds.bottom - offsetY,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(SCRIM_COLOR)
        for (rect in localRects) {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downHitIndex = hitTest(event.rawX, event.rawY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val down = downHitIndex
                downHitIndex = -1
                val up = hitTest(event.rawX, event.rawY)
                when {
                    // 同一块上按下并抬起 → 复制该块
                    down >= 0 && up == down -> onCopy(blocks[down].text)
                    // 空白点击（按下抬起都未命中）→ 退出
                    down < 0 && up < 0 -> onDismiss()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downHitIndex = -1
                return true
            }
        }
        return true
    }

    /** 屏幕坐标命中测试：嵌套时面积最小的块胜出，tapPadding 扩大可点区 */
    private fun hitTest(rawX: Float, rawY: Float): Int {
        var best = -1
        var bestArea = Long.MAX_VALUE
        for (i in blocks.indices) {
            val b = blocks[i].bounds
            if (rawX >= b.left - tapPadding && rawX <= b.right + tapPadding &&
                rawY >= b.top - tapPadding && rawY <= b.bottom + tapPadding
            ) {
                val area = b.width().toLong() * b.height().toLong()
                if (area < bestArea) {
                    bestArea = area
                    best = i
                }
            }
        }
        return best
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onDismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** 取消超时与熄屏监听（服务拆窗前调用；重复调用安全） */
    fun release() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        runCatching { context.unregisterReceiver(screenOffReceiver) }
    }
}
