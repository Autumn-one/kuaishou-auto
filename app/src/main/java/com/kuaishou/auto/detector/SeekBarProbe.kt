package com.kuaishou.auto.detector

import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/**
 * 尝试从无障碍节点树直接读取进度条数值。
 *
 * 快手首页的进度条控件是 com.yxcorp.gifshow.detail.view.SlidePlaySeekBar
 * （resource-id: slide_play_progress）。若它是标准 SeekBar 派生类，
 * AccessibilityNodeInfo.rangeInfo 会带出 current/min/max，
 * 这样无需截图即可拿到精确进度。
 */
object SeekBarProbe {

    private const val TAG = "SeekBarProbe"

    private const val ID_PROGRESS = "slide_play_progress"
    private const val ID_LOADING = "slide_play_loading_progress"

    data class Info(
        val found: Boolean,
        val current: Float,
        val max: Float,
        val progress: Float?,
        val nodeId: String,
        val className: String,
    )

    fun probe(root: AccessibilityNodeInfo?): Info {
        if (root == null) return Info(false, 0f, 0f, null, "", "")
        val hit = findProgressNode(root, 0, 30)
            ?: return Info(false, 0f, 0f, null, "", "")
        val range = hit.rangeInfo
        val id = hit.viewIdResourceName ?: ""
        val cls = hit.className?.toString() ?: ""
        if (range == null) {
            return Info(true, 0f, 0f, null, id, cls)
        }
        val p = if (range.max > 0) range.current / range.max else null
        return Info(true, range.current, range.max, p, id, cls)
    }

    /**
     * 遍历节点树查找进度条节点。
     * 匹配 resource-id 含 slide_play_progress，且类名含 SeekBar/ProgressBar。
     */
    private fun findProgressNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
    ): AccessibilityNodeInfo? {
        if (depth > maxDepth) return null
        val id = node.viewIdResourceName ?: ""
        val cls = node.className?.toString() ?: ""

        if (id.contains(ID_PROGRESS) && !id.contains(ID_LOADING)) {
            return node
        }
        if (cls.contains("SlidePlaySeekBar")) {
            return node
        }

        val n = node.childCount
        for (i in 0 until n) {
            val c = node.getChild(i) ?: continue
            val r = findProgressNode(c, depth + 1, maxDepth)
            if (r != null) return r
        }
        return null
    }

    /**
     * 用系统侧 View ID 索引查询进度条节点。
     *
     * 与 [findProgressNode] 的**递归匹配行为不同**：这里走
     * `findAccessibilityNodeInfosByViewId()`，是 AccessibilityService 在
     * 系统侧维护的 id 索引查询。两条路的可见性/过滤规则不一样——
     * 尤其本服务配置里开了 `flagIncludeNotImportantViews`，
     * 被标为 not-important 的节点可能只有这条查询能拿到。
     *
     * 实测背景：项目曾据 `dumpsys` 里的 `......I.` 认定进度条控件
     * "被排除在无障碍之外"，但该标志位实际是
     * **IMPORTANT_FOR_ACCESSIBILITY 已置位**（含义相反），
     * 而那两处 `0,0-0,0` + `GFED` 是 RecyclerView 预加载的 GONE 实例。
     * 因此"能不能拿到"必须实测，不能靠推断。
     *
     * @return 每个候选 id 的查询结果描述（命中数 + rangeInfo）
     */
    fun probeByViewId(root: AccessibilityNodeInfo?): String {
        if (root == null) return "根节点为空"
        val sb = StringBuilder()
        for (id in CANDIDATE_IDS) {
            val full = "$PKG_KUAISHOU_APP:id/$id"
            val hits = try {
                root.findAccessibilityNodeInfosByViewId(full)
            } catch (t: Throwable) {
                null
            }
            if (hits == null) {
                sb.append("$id=异常 ")
                continue
            }
            if (hits.isEmpty()) {
                sb.append("$id=0 ")
                continue
            }
            var withRange = 0
            var sample = ""
            for (n in hits) {
                val r = n.rangeInfo
                if (r != null) {
                    withRange++
                    if (sample.isEmpty()) {
                        sample = "cur=${r.current} max=${r.max}"
                    }
                }
            }
            sb.append("$id=命中${hits.size}带range$withRange")
            if (sample.isNotEmpty()) sb.append("($sample)")
            sb.append(' ')
        }
        return sb.toString().trim()
    }

    /** 候选进度条控件 id（首页已知/可能出现的两种命名） */
    private val CANDIDATE_IDS = listOf(
        "slide_play_progress",
        "milano_player_seekbar",
        "nasa_milano_seekbar",
        "slide_progress",
    )

    /** 快手极速版包名（用于拼 View ID） */
    private const val PKG_KUAISHOU_APP = "com.kuaishou.nebula"

    /** 诊断：统计节点树里的控件数量与特征，用于确认无障碍可见性 */
    fun diagnose(root: AccessibilityNodeInfo?): String {
        if (root == null) return "根节点为空"
        var total = 0
        var seekBars = 0
        var progressBars = 0
        var withRange = 0
        val ids = HashSet<String>()

        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > 30) return
            total++
            val cls = n.className?.toString() ?: ""
            if (cls.contains("SeekBar")) seekBars++
            if (cls.contains("ProgressBar")) progressBars++
            if (n.rangeInfo != null) withRange++
            n.viewIdResourceName?.let { if (it.isNotEmpty()) ids.add(it) }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it, d + 1) }
            }
        }
        walk(root, 0)

        val sample = ids.take(8).joinToString(",") { it.substringAfterLast('/') }
        return "节点=$total SeekBar=$seekBars ProgressBar=$progressBars 带range=$withRange id样例=[$sample]"
    }
}
