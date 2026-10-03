package com.kuaishou.auto.detector

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 识别当前页面类型。
 *
 * 实机调研依据（小米 13 / Android 16）：
 * - 普通视频首页: Activity com.yxcorp.gifshow.HomeActivity
 * - 直播间:       Activity com.kuaishou.live.core.basic.activity.LiveSlideActivity
 *
 * 重要实测发现：
 * - 快手首页的窗口里【同时存在】直播间控件（LivePlayTextureView）与
 *   普通视频控件（SlidePlaySeekBar），因为首页会预加载直播 View。
 *   因此不能用"含直播控件"直接判定直播间。
 * - 无障碍的节点树也不一定包含 TextureView 这类无内容控件。
 *
 * 结论：以【窗口根节点的类名 / 包名】为主判据，
 * 因为 Activity 切换一定会改变窗口类名；首页内翻页不改变 Activity，
 * 对首页而言本来就是同一类页面，无需区分。
 */
object PageDetector {

    const val PKG_KUAISHOU = "com.kuaishou.nebula"

    enum class PageType {
        /** 快手首页信息流（普通视频） */
        HOME_FEED,

        /** 直播间 */
        LIVE_ROOM,

        /** 快手内其它页面 */
        KUAISHOU_OTHER,

        /** 不是快手 / 无法识别 */
        UNKNOWN,
    }

    /** 直播间 Activity 关键片段 */
    private val LIVE_ACTIVITY_MARKERS = listOf(
        "liveslideactivity",
        "live.core.basic.activity",
        "liveroomactivity",
    )

    /** 首页 Activity 关键片段 */
    private val HOME_ACTIVITY_MARKERS = listOf(
        "gifshow.homeactivity",
    )

    /**
     * 首页**可靠标志** id（实测存在于首页窗口节点树里）。
     *
     * 这是"是否在上滑信息流里"的判据，比 Activity 名更抗误判：
     * 实测 `PhotoDetailActivity`（图文详情页）**不含** `home_activity_root`
     * 与 `home_fragment_container`，但它含有 `slide_v2_content_layout` 与
     * `nasa_slide_play_view_pager_layout`（这两个是复用的，不能单独作判据）。
     */
    val HOME_MARKER_IDS = listOf(
        "home_activity_root",
        "home_fragment_container",
    )

    /**
     * 是否为"横向/纵向滑动的详情页"（图文详情、视频详情等）。
     *
     * 实测：这些页面里 `AutoScrollService.currentWindowClass` 可能停留在
     * 上一次的 HomeActivity（事件没刷新），导致页面类型判成 HOME_FEED，
     * 于是任务会一直对着一个**静止的图文页**读"进度条"（实测读出恒定的
     * `强度=255.0 进度=54%`，其实是静态图片被当成了进度条），
     * 上滑也永远翻不了页。
     *
     * 因此额外用 Activity 名兜底识别这类页面。
     */
    private val DETAIL_ACTIVITY_MARKERS = listOf(
        "photodetailactivity",
        "detailactivity",
        "videodetail",
        "photoslide",
    )

    /**
     * 用节点树判断"是否真的在上滑信息流里"。
     *
     * 判据：窗口节点树里存在首页专有标志 id。
     * 这条路不依赖 Activity 事件缓存，因此在缓存过期时仍然准确。
     */
    fun hasHomeMarker(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        return search(root, HOME_MARKER_IDS, 0, 40)
    }

    /** 是否为详情页（图文/视频），这类页面需要退回首页而不是继续等进度条 */
    fun isDetailPage(windowClassName: String?): Boolean {
        val cls = windowClassName?.lowercase() ?: return false
        return DETAIL_ACTIVITY_MARKERS.any { cls.contains(it) }
    }

    /**
     * 由窗口类名判断页面类型。这是最可靠的判据。
     */
    fun classifyByClassName(windowClassName: String?, packageName: String?): PageType {
        if (windowClassName == null) {
            return if (packageName == PKG_KUAISHOU) PageType.KUAISHOU_OTHER else PageType.UNKNOWN
        }
        if (packageName != null && packageName != PKG_KUAISHOU) {
            return PageType.UNKNOWN
        }

        val lower = windowClassName.lowercase()
        for (m in LIVE_ACTIVITY_MARKERS) {
            if (lower.contains(m)) return PageType.LIVE_ROOM
        }
        for (m in HOME_ACTIVITY_MARKERS) {
            if (lower.contains(m)) return PageType.HOME_FEED
        }
        return PageType.KUAISHOU_OTHER
    }

    /**
     * 从窗口根节点推断（用于补充：当窗口类名拿不到时）。
     */
    fun classifyByRoot(root: AccessibilityNodeInfo?): PageType {
        if (root == null) return PageType.UNKNOWN
        val pkg = root.packageName?.toString()
        if (pkg != PKG_KUAISHOU) return PageType.UNKNOWN

        // 遍历查找带 Activity 信息的特征控件
        val liveHit = search(root, LIVE_ROOT_MARKERS, 0, 30)
        if (liveHit) return PageType.LIVE_ROOM

        val homeHit = search(root, HOME_ROOT_MARKERS, 0, 30)
        if (homeHit) return PageType.HOME_FEED

        return PageType.KUAISHOU_OTHER
    }

    /**
     * 直播间根节点特征：直播间的关闭按钮 + 直播播放器同时存在。
     * 首页虽然有 LivePlayTextureView，但没有直播间的关闭按钮/礼物面板。
     */
    private val LIVE_ROOT_MARKERS = listOf(
        "live_close",
        "live_gift_wall",
        "live_gift_panel",
        "live_audience_count",
        "live_input_edit",
    )

    private val HOME_ROOT_MARKERS = listOf(
        "slide_play_progress",
        "side_progress_group",
        "slide_playerkit_view",
    )

    private fun search(
        node: AccessibilityNodeInfo,
        markers: List<String>,
        depth: Int,
        maxDepth: Int,
    ): Boolean {
        if (depth > maxDepth) return false
        val id = node.viewIdResourceName ?: ""
        val cls = node.className?.toString() ?: ""
        for (m in markers) {
            if (id.contains(m, ignoreCase = true) || cls.contains(m, ignoreCase = true)) {
                return true
            }
        }
        val n = node.childCount
        for (i in 0 until n) {
            val child = node.getChild(i) ?: continue
            if (search(child, markers, depth + 1, maxDepth)) return true
        }
        return false
    }
}
