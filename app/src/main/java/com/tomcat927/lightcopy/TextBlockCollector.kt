package com.tomcat927.lightcopy

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque

/** 屏幕上的一个可复制文本块；bounds 为屏幕坐标系（getBoundsInScreen） */
data class TextBlock(val text: String, val bounds: Rect)

/**
 * 从无障碍节点树采集文本块。
 *
 * 双路策略：
 * 1. rootInActiveWindow 非本包 → 只遍历它（常规情况，快）；
 * 2. 否则（root 为 null 或属于本包，比如悬浮层正持有焦点时再触发）遍历 service.windows
 *    全部窗口，排除本包、SystemUI、输入法键盘窗、无障碍覆盖层（防采到自己）。
 */
object TextBlockCollector {

    private const val TAG = "LightCopy"
    private const val SYSTEM_UI_PKG = "com.android.systemui"

    // 兜底上限：病态庞大的节点树不该拖死系统，采满即止
    private const val MAX_NODES = 5000
    private const val MAX_BLOCKS = 1000

    fun collect(service: AccessibilityService): List<TextBlock> {
        val startMs = System.currentTimeMillis()
        val ownPackage = service.packageName
        val blocks = LinkedHashMap<String, TextBlock>()
        val allocatedNodes = ArrayList<AccessibilityNodeInfo>()
        val allocatedWindows = ArrayList<AccessibilityWindowInfo>()
        var sourcePkgs: List<String> = emptyList()
        var dualPath = false

        try {
            val roots = ArrayList<AccessibilityNodeInfo>()
            val activeRoot = service.rootInActiveWindow
            if (activeRoot != null) allocatedNodes.add(activeRoot)

            if (activeRoot != null && activeRoot.packageName?.toString() != ownPackage) {
                roots.add(activeRoot)
            } else {
                val windows = service.windows ?: emptyList()
                allocatedWindows.addAll(windows)
                for (window in windows) {
                    if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                    if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                    val root = window.root ?: continue
                    allocatedNodes.add(root)
                    val pkg = root.packageName?.toString()
                    if (pkg == ownPackage || pkg == SYSTEM_UI_PKG) continue
                    roots.add(root)
                }
            }

            // 屏幕外的块（列表回收的离屏项）点不到，只会污染「复制全部」
            val displayBounds = Rect(
                0, 0,
                service.resources.displayMetrics.widthPixels,
                service.resources.displayMetrics.heightPixels,
            )
            for (root in roots) {
                bfs(root, blocks, displayBounds, allocatedNodes)
            }
            sourcePkgs = roots.mapNotNull { it.packageName?.toString() }.distinct()
            dualPath = activeRoot != null && activeRoot.packageName?.toString() == ownPackage
        } finally {
            // API 33 起节点不再需要手动 recycle
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                for (node in allocatedNodes) runCatching { node.recycle() }
                for (window in allocatedWindows) runCatching { window.recycle() }
            }
        }

        val result = ArrayList(blocks.values)
        // 阅读序：先按 top、再按 left
        result.sortWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val filtered = dropAncestors(result)
        RemoteLog.d(
            TAG,
            "collect: ${filtered.size} blocks (${result.size} raw, roots=$sourcePkgs, " +
                "dualPath=$dualPath) in ${System.currentTimeMillis() - startMs} ms"
        )
        return filtered
    }

    /** 显式 ArrayDeque 做 BFS（不递归防爆栈），按节点身份去重防重复扫描 */
    private fun bfs(
        root: AccessibilityNodeInfo,
        blocks: LinkedHashMap<String, TextBlock>,
        displayBounds: Rect,
        allocated: ArrayList<AccessibilityNodeInfo>,
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val visited = HashSet<AccessibilityNodeInfo>()
        queue.addLast(root)
        visited.add(root)
        var scanned = 0

        while (queue.isNotEmpty()) {
            if (scanned >= MAX_NODES || blocks.size >= MAX_BLOCKS) return
            val node = queue.removeFirst()
            scanned++

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                allocated.add(child)
                if (visited.add(child)) queue.addLast(child)
            }

            extractBlock(node, blocks, displayBounds)
        }
    }

    /** 文本三级回退：text → contentDescription → stateDescription（API 30+），空白跳过 */
    private fun nodeText(node: AccessibilityNodeInfo): String? {
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /**
     * 可见即收；不可见但文本非空的也收（微信视频号评论区等节点误报不可见的场景，
     * 这类节点 bounds 是真实的，空 bounds 已在上面丢弃）
     */
    private fun extractBlock(
        node: AccessibilityNodeInfo,
        blocks: LinkedHashMap<String, TextBlock>,
        displayBounds: Rect,
    ) {
        val text = nodeText(node) ?: return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return
        if (!Rect.intersects(bounds, displayBounds)) return

        // 去重第一道：同 bounds + 同文本只收一次
        val key = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}|$text"
        blocks.putIfAbsent(key, TextBlock(text, bounds))
    }

    /**
     * 去重第二道：某块矩形完全包含面积更小的另一块 → 丢弃祖先，保留最小文本块
     * （父容器带 contentDescription、父子文本重复的常见情况）
     */
    private fun dropAncestors(blocks: List<TextBlock>): List<TextBlock> =
        blocks.filter { block ->
            blocks.none { other ->
                other !== block &&
                    other.bounds.contains(block.bounds) &&
                    area(other.bounds) > area(block.bounds)
            }
        }

    private fun area(rect: Rect): Long = rect.width().toLong() * rect.height().toLong()
}
