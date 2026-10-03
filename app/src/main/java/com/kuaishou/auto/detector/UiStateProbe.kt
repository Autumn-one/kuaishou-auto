package com.kuaishou.auto.detector

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 读取页面"状态型"信息：评论面板是否打开、某个控件的位置。
 *
 * 与进度条数值不同，状态信息在无障碍节点树里是**可读的**（实测确认）：
 * - 进度条控件被标记 not-important，不在树里，拿不到数值
 * - 但 comment_panel_container 这类容器在树里，且可见性可直接读出
 *
 * 因此进度值走像素、页面状态走节点树，两者互补。
 */
object UiStateProbe {

    /** 评论面板根容器 */
    private const val ID_COMMENT_PANEL = "comment_panel_container"

    /**
     * 电商/活动弹层容器（实测 id）。
     *
     * 实测：视频流里会弹出"关注领粉丝专属券"这类电商浮层，
     * 它**盖住整个视频区**，此时进度条读不到、上滑也不翻页
     * （手势落在浮层上），任务会退化成反复空滑。
     * 实测按一次系统返回键即可关闭（`merchant_rn_container` 从树里消失）。
     */
    private val ID_BLOCKING_POPUPS = listOf(
        "merchant_rn_container",
        "live_merchant_krn_gesture_dispatch_layout",
    )

    /** 播放进度条所在的容器，用它的底边校准进度条行位置 */
    const val ID_SIDE_PROGRESS_GROUP = "side_progress_group"

    private const val MAX_DEPTH = 40

    /**
     * 进度条容器的上下边界。
     *
     * 实测 side_progress_group 为 `0,1716-1080,2266`，
     * 进度条本体在它的底边往上数个像素处，因此底边是可靠的校准锚点。
     */
    data class NodeBounds(val found: Boolean, val rect: Rect?)

    /** 取指定 id 的控件的可见边界 */
    fun findNodeBounds(root: AccessibilityNodeInfo?, id: String): NodeBounds {
        if (root == null) return NodeBounds(false, null)
        val hit = findById(root, id, 0) ?: return NodeBounds(false, null)
        val r = Rect()
        hit.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return NodeBounds(true, null)
        return NodeBounds(true, r)
    }

    /**
     * 评论面板当前是否打开。
     *
     * 判据（实测对比"关闭/打开"两种状态的节点树得出）：
     * 面板容器 comment_panel_container 可见。
     * 关闭时该容器仍留在树里，但标志位为 `GF....C..`（G=GONE）且没有 V；
     * 打开时为 `VFE...C..`（V=VISIBLE）。因此必须判可见性。
     *
     * 反例记录：editor_holder_text 在两种状态下都是 `V.ED.....`，
     * 不能用它判别，否则会一直误判为"评论已打开"。
     */
    fun isCommentPanelOpen(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        return isVisibleById(root, ID_COMMENT_PANEL, 0)
    }

    /**
     * 是否有盖住视频区的弹层（电商浮层等）。
     *
     * 实测：这类浮层会让"进度条读不到 + 上滑不翻页"同时发生，
     * 任务只会空滑；按一次系统返回键即可关闭。
     *
     * @return 命中弹层的 id（便于日志区分），未命中返回 null
     */
    fun blockingPopupId(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        for (id in ID_BLOCKING_POPUPS) {
            if (isVisibleById(root, id, 0)) return id
        }
        return null
    }

    /** 遍历查找 id 匹配且可见的节点 */
    private fun isVisibleById(node: AccessibilityNodeInfo, id: String, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        if (node.isVisibleToUser) {
            val nodeId = node.viewIdResourceName ?: ""
            if (nodeId.endsWith("/$id")) return true
        }
        val n = node.childCount
        for (i in 0 until n) {
            val c = safeChild(node, i) ?: continue
            if (isVisibleById(c, id, depth + 1)) return true
        }
        return false
    }

    /**
     * 首页信息流里"直播卡片"的判据（实测文本）。
     *
     * 实测（应用自己的节点树，`uiautomator dump` 在视频页不可用）：
     * 刷到直播预告视频时，节点树里会出现 `text=点击进入直播间`
     * （同时还有 `text=直播`、`text=直播团购`）。
     *
     * 为什么必须识别它：这类卡片上盖着一个"点击进入直播间"按钮，
     * 而**上滑手势的起点正好落在该按钮上**（实测按钮包围盒
     * x 290..830 / y 1687..1800，上滑起点 x≈130..453 / y≈1728），
     * 于是上滑被系统判成"点击"→ 直接进入直播间。
     * 连续 35 次都困在直播流里就是这个原因。
     *
     * 处理方式：识别到就**立刻上滑划走**，不在卡片上做暂停探测。
     */
    private val LIVE_CARD_TEXTS = listOf(
        "点击进入直播间",
    )

    /**
     * 直播**预览卡**的容器 id（实测）。
     *
     * 这类卡片是 `LivebCardExternalDisplay` 组件，**没有**「点击进入直播间」
     * 那句文字，所以只看文本判不出来。实测证据（快手自身日志）：
     * `jumpUrl=kwaipreviewlive://enterCurrentLive`、`showCardMillis=15000`
     * —— 卡片展示 15 秒后**自动**进入直播间，不需要用户点击。
     * 实测因此被带进过 `LiveSlideActivity`。
     *
     * 注意：这里**只放首页卡片专属的 id**。不要放 `live_gift_sticker_container_layout`
     * 这类同时在直播间内出现的 id——直播间与首页的 id 集合会重叠（见
     * [hasLiveRoomMarkers] 的警告），混入会造成误判。
     */
    private val LIVE_CARD_IDS = listOf(
        "live_preview_auto_enter_view_style_container",
        "live_preview_bottom_general_entry_item",
    )

    /** 直播间内部布局的标志 id，用于识别"已经进了直播间" */
    private val LIVE_ROOM_IDS = listOf(
        "live_slide_container",
        "live_slide_view_pager",
        "live_play_root_container",
        "live_play_top_float_cover",
    )
    /**
     * 当前页面是否是"直播相关"的卡片，应当立刻划走。
     *
     * 覆盖两种形态（实测）：
     *
     * 1. **普通直播卡片**：节点树里有文字「点击进入直播间」。
     * 2. **自动跳转的直播预览卡**（`LivebCardExternalDisplay`）：
     *    它**没有**上面那句文字，所以早期实现判不出来。实测证据：
     *    快手日志里 `jumpUrl=kwaipreviewlive://enterCurrentLive`、
     *    `showCardMillis=15000` —— 卡片展示 **15 秒后会自动进入直播间**，
     *    并不是我们点进去的。判据改为看它的容器 id：
     *    `live_preview_auto_enter_view_style_container` 等。
     *    实测踩过：任务因此被带进 `LiveSlideActivity`。
     *
     * 另附 [hasLiveRoomMarkers]：即便已经进了直播间（布局 id 出现
     * `live_slide_container` 等），也要能被识别出来，交给退出逻辑处理。
     *
     * @return true 表示当前是直播卡片/直播间，应当划走或退出
     */
    fun isLiveCard(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (hasVisibleText(root, LIVE_CARD_TEXTS, 0)) return true
        return hasAnyViewId(root, LIVE_CARD_IDS, 0)
    }

    /**
     * 诊断：把命中"直播卡片"文本的节点的**屏幕坐标**列出来。
     *
     * 用途：区分"真的有一张直播卡"与"残留/离屏节点造成的误判"。
     * 判据：真实卡片的按钮落在视频区中部（实测 y≈1687..1800）；
     * 若节点坐标是 0,0-0,0 或落在屏幕外，说明它是 RecyclerView
     * 预加载的 GONE 实例，属于误判。
     */
    fun describeLiveCardHits(root: AccessibilityNodeInfo?): String {
        if (root == null) return "根节点为空"
        val out = ArrayList<String>()
        val rect = Rect()

        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > MAX_DEPTH || out.size >= 6) return
            val text = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            val id = n.viewIdResourceName?.substringAfterLast('/') ?: ""
            val hit = LIVE_CARD_TEXTS.any { text.contains(it) || desc.contains(it) } ||
                LIVE_CARD_IDS.any { it == id }
            if (hit) {
                n.getBoundsInScreen(rect)
                out.add(
                    "[$id] visible=${n.isVisibleToUser} " +
                        "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} " +
                        "text=${text.take(16)}",
                )
            }
            for (i in 0 until n.childCount) {
                val c = safeChild(n, i) ?: continue
                walk(c, d + 1)
            }
        }
        walk(root, 0)
        return if (out.isEmpty()) "无命中(与isLiveCard结果可能不一致)" else out.joinToString(" || ")
    }

    /**
     * 页面里是否出现"直播间内部布局"的标志 id。
     *
     * **警告：不能单独用它判断"是否在直播间"。**
     * 实测踩坑（已记录于 ScrollTask.kuaishouRoot 的注释）：快手会在首页
     * **预加载**直播视图，于是首页节点的 id 集合与直播间**完全相同**
     * （都含 `live_slide_container` / `live_slide_view_pager` / ...），
     * 使基于 id 的页面判据失效。
     *
     * 因此本方法只作**辅助**信号，必须与"当前 Activity 是否为
     * LiveSlideActivity"等更强判据结合使用。
     */
    fun hasLiveRoomMarkers(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        return hasAnyViewId(root, LIVE_ROOM_IDS, 0)
    }

    /**
     * 遍历查找 viewId 末段命中任一 id 的节点。
     *
     * **必须校验可见性与实际尺寸**（实测教训）：
     * 快手会在信息流里**预加载**直播相关视图，它们的节点常驻于节点树，
     * 但 `visible=false` 且尺寸无效。实测到的两类占位节点：
     * - `live_preview_auto_enter_view_style_container` → `rect=540,1834-540,1834`（0 宽高）
     * - `live_preview_bottom_general_entry_item` → `rect=0,4532-1080,2266`（top > bottom，负高度）
     *
     * 早期实现只比对 id、不查可见性，于是它们在**每一个视频上**都命中，
     * 导致任务在每个视频停留两三秒就上滑（用户报告的"连续多次短暂停留上滑"）。
     * 现在要求：可见、且宽高都为正、且在屏幕范围内。
     */
    private fun hasAnyViewId(
        node: AccessibilityNodeInfo,
        ids: List<String>,
        depth: Int,
    ): Boolean {
        if (depth > MAX_DEPTH) return false
        val nodeId = node.viewIdResourceName ?: ""
        if (nodeId.isNotEmpty()) {
            val tail = nodeId.substringAfterLast('/')
            for (id in ids) {
                if (tail == id && isReallyVisible(node)) return true
            }
        }
        val n = node.childCount
        for (i in 0 until n) {
            val c = safeChild(node, i) ?: continue
            if (hasAnyViewId(c, ids, depth + 1)) return true
        }
        return false
    }

    /**
     * 节点是否"真的画在屏幕上"。
     *
     * 只信 `isVisibleToUser` 不够：实测有 `visible=true` 但尺寸为 0 的节点，
     * 也有尺寸正常但不可见的预加载节点。因此两者都要满足。
     */
    private fun isReallyVisible(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val r = Rect()
        node.getBoundsInScreen(r)
        return r.width() > 0 && r.height() > 0
    }

    /**
     * 遍历查找 text 或 contentDescription 命中任一关键词的**真实可见**节点。
     *
     * 注意必须用 [isReallyVisible] 而不是只查 `isVisibleToUser`：
     * 实测快手会保留离屏的预加载节点（`visible=false`，
     * 且坐标是 `365,0-715,-398` 这种跑到屏幕外的值），
     * 若只比对文本就会误判成"当前是直播卡片"。
     */
    private fun hasVisibleText(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        depth: Int,
    ): Boolean {
        if (depth > MAX_DEPTH) return false
        if (isReallyVisible(node)) {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            for (k in keywords) {
                if (text.contains(k) || desc.contains(k)) return true
            }
        }
        val n = node.childCount
        for (i in 0 until n) {
            val c = safeChild(node, i) ?: continue
            if (hasVisibleText(c, keywords, depth + 1)) return true
        }
        return false
    }

    /** 遍历查找 id 匹配的节点（不要求可见） */
    private fun findById(
        node: AccessibilityNodeInfo,
        id: String,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        val nodeId = node.viewIdResourceName ?: ""
        if (nodeId.endsWith("/$id")) return node
        val n = node.childCount
        for (i in 0 until n) {
            val c = safeChild(node, i) ?: continue
            val r = findById(c, id, depth + 1)
            if (r != null) return r
        }
        return null
    }

    /**
     * 调试：列出节点树里所有 id 的**末段**（去重）。
     *
     * 用途：对比"首页信息流"与"直播间"两类页面的 id 集合，找出可靠区分特征。
     * 背景（用户提示）：直播间页面**没有底部导航栏**（首页那条
     * 首页/朋友/+/去赚钱/我），这是肉眼最直观的区分点；
     * 但它是否以可读 id 暴露给无障碍，必须先验证再依赖。
     */
    /**
     * 当前视频的**唯一标识**（作者名 + 标题等文本的指纹）。
     *
     * 为什么需要它（实测根因）：
     * 进度条读数来自截图分析，而**换页后的头几帧仍在渲染上一个视频**
     * （视图复用 + 视频层还没换新）。实测到：每次上滑之后紧接着读到的进度
     * 就是 `99% / 97%`——新视频不可能瞬间播到 99%，这只能是**旧视频的残留帧**。
     * 于是"读到的 99%"被算到新视频头上，立刻又触发一次上滑，
     * 形成 3.7 秒一次的连滑，进度显示彻底乱掉。
     *
     * 解法：用节点树里的文本（作者/标题）给每个视频打指纹。
     * 换页后如果指纹**还没变**，说明画面仍是旧视频，此时读到的进度必须丢弃，
     * 不能计入新页面的峰值。
     *
     * 只取**可见且非空**的文本，按屏幕位置排序以保证同一页面指纹稳定。
     */
    fun videoIdentity(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val parts = ArrayList<Pair<Int, String>>()
        val rect = Rect()

        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > MAX_DEPTH || parts.size >= IDENTITY_MAX_PARTS) return
            var label = n.text?.toString()?.trim().orEmpty()
            if (label.isEmpty()) {
                label = n.contentDescription?.toString()?.trim().orEmpty()
            }
            if (label.isNotEmpty() && !n.isPassword && isReallyVisible(n)) {
                // 必须实际取一次边界：rect.top 用于排序
                n.getBoundsInScreen(rect)
                parts.add(rect.top to label)
            }
            for (i in 0 until n.childCount) {
                val c = safeChild(n, i) ?: continue
                walk(c, d + 1)
            }
        }
        walk(root, 0)

        if (parts.isEmpty()) return ""
        // 按 y 排序，保证同一屏内容顺序稳定（节点树遍历顺序可能变）
        parts.sortBy { it.first }
        val joined = parts.joinToString("\u0001") { it.second }
        // 过长时取哈希，日志与比较都更省
        return if (joined.length <= 64) joined else joined.hashCode().toString()
    }

    /** 指纹最多取多少段文本（够区分视频即可，避免整页文案拖慢遍历） */
    private const val IDENTITY_MAX_PARTS = 12

    fun dumpIds(root: AccessibilityNodeInfo?, limit: Int = 60): String {
        if (root == null) return "根节点为空"
        val ids = LinkedHashSet<String>()

        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > MAX_DEPTH || ids.size >= limit) return
            val id = n.viewIdResourceName ?: ""
            if (id.isNotEmpty()) ids.add(id.substringAfterLast('/'))
            for (i in 0 until n.childCount) {
                val c = safeChild(n, i) ?: continue
                walk(c, d + 1)
            }
        }
        walk(root, 0)
        return ids.joinToString(" | ")
    }

    /**
     * 调试：列出节点树里所有"可见文本 / contentDescription / id"，
     * 用于确认某个页面特征到底以什么形式暴露给无障碍。
     *
     * 实测背景：直播卡片上写着"点击进入直播间"，但
     * `uiautomator dump` 在视频页会因画面持续播放而报
     * `could not get idle state`，完全 dump 不出来；
     * 而应用自己通过 AccessibilityService 拿到的节点树是可读的。
     * 因此用这个函数把真实节点内容打进日志。
     *
     * @param keyword 只输出包含该关键词（不区分大小写）的条目；空串表示全部
     * @param limit 最多输出多少条
     */
    fun dumpTexts(
        root: AccessibilityNodeInfo?,
        keyword: String = "",
        limit: Int = 40,
    ): String {
        if (root == null) return "根节点为空"
        val out = ArrayList<String>(limit)
        val key = keyword.lowercase()

        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > MAX_DEPTH || out.size >= limit) return
            val text = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            val id = n.viewIdResourceName ?: ""
            val cls = n.className?.toString() ?: ""
            for ((label, v) in listOf("text" to text, "desc" to desc, "id" to id, "cls" to cls)) {
                if (v.isEmpty()) continue
                if (key.isNotEmpty() && !v.lowercase().contains(key)) continue
                // id/cls 只输出末段，日志更短
                val shown = if (label == "id" || label == "cls") v.substringAfterLast('/') else v
                out.add("$label=$shown")
                break
            }
            for (i in 0 until n.childCount) {
                val c = safeChild(n, i) ?: continue
                walk(c, d + 1)
            }
        }
        walk(root, 0)
        return if (out.isEmpty()) "无匹配($keyword)" else out.joinToString(" | ")
    }


    /**
     * 取子节点。深树里 getChild 会抛异常（节点已被回收），
     * 单点失败不应中断整棵树遍历。
     */
    private fun safeChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? {
        return try {
            node.getChild(index)
        } catch (_: Throwable) {
            null
        }
    }
}
