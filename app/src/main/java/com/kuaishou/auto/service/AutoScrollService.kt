package com.kuaishou.auto.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlin.random.Random

/**
 * 无障碍服务：负责执行上滑手势，并对外暴露滑动能力。
 *
 * 实测：dispatchGesture 无需 root，可在前台应用之上注入真实触摸。
 */
class AutoScrollService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected = true
        // 注意：**不要**在这里无条件 reset 截图能力确认结果。
        // 能力（系统是否支持 takeScreenshot）与"哪个服务实例在跑"无关，
        // 每次重连都清掉会导致"确认结果反复丢失 → AUTO 模式反复退回
        // MediaProjection → 又弹一次授权框"（实测踩过）。
        // 只在首次确认失败时才需要重试，因此交给下面的预热按需处理。
        A11yCaptureSupport.warmUpAsync()
        A11yCaptureSupport.retryPendingWarmUp()
        Log.i(TAG, "无障碍服务已连接")
        refreshWindowInfo()
    }

    /**
     * 主动从当前活动窗口刷新 Activity 信息。
     * 服务刚连接时可能收不到窗口事件，需要主动取一次。
     */
    fun refreshWindowInfo() {
        try {
            val root = rootInActiveWindow ?: return
            val pkg = root.packageName?.toString() ?: return
            if (pkg != PKG_KUAISHOU_TARGET) return
            currentPackage = pkg
            // 节点树拿不到 Activity 名，用窗口列表里的标题补充
            val ws = windows
            for (w in ws) {
                val r = try {
                    w.root
                } catch (_: Throwable) {
                    null
                }
                if (r?.packageName?.toString() == PKG_KUAISHOU_TARGET) {
                    // Android 不直接暴露 Activity 名，退回用节点特征判断
                    break
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val pkg = event.packageName?.toString() ?: return

        // ---- 前台包名：**所有**应用都要记录 ----
        //
        // 这是"当前应用检测"的基础。早期实现是"非快手事件直接 return"，
        // 结果离开快手后 currentPackage 永远停在 com.kuaishou.nebula，
        // 任务据此认为"还在快手"并继续派发上滑手势——实测踩过：
        // 用户切到天气应用后，任务仍对着天气界面读"进度=19%"并持续上滑，
        // 会在别人的应用里乱点，**这是很严重的安全问题**。
        // 因此前台包名必须无条件跟踪。
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foregroundPackage = pkg
        }

        // ---- 快手相关信息按原逻辑处理 ----
        if (pkg != PKG_KUAISHOU_TARGET) return
        currentPackage = pkg

        // 内容变化计数：作为"页面/视频发生了变化"的独立旁证（见 contentChangeSeq）
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            contentChangeSeq++
        }

        val cls = event.className?.toString()
        // 内容变化事件的 className 常是普通控件名而非 Activity，
        // 只在拿到 Activity 形态的类名时才覆盖，避免污染判据。
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !cls.isNullOrEmpty()) {
            currentWindowClass = cls
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        connected = false
        Log.w(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        connected = false
        Log.w(TAG, "无障碍服务已销毁")
        super.onDestroy()
    }

    /**
     * 服务是否仍然连着无障碍框架。
     *
     * 实测：服务被系统断开后 [instance] 可能仍非空（进程里对象还在），
     * 但 `dispatchGesture` / `performGlobalAction` 会静默失败、
     * `rootInActiveWindow` 恒为 null，而日志里只有"手势未完成"。
     * 露出这个状态后，任务层就能给出"请重新开启无障碍"这种可操作的提示，
     * 而不是无限重试。
     */
    fun isConnected(): Boolean = connected

    /**
     * 执行一次上滑手势：屏幕中间偏左，自下往上，带小范围随机抖动。
     *
     * 走廊约束（防误触右侧操作栏）：
     * 实测首页右侧操作栏 group_right_action_bar_root_layout 占据 x=915~1080，
     * 其中评论按钮在 x≈915~1080 / y≈1494~1687。滑动一旦起手落在那里，
     * 会被识别成"点击评论"从而弹出评论面板，把上滑流程卡死。
     * 因此这里把 x 夹在屏幕左侧 1/3 区域，远离操作栏。
     *
     * 起止点：y 从 0.72h 到 0.22h。起点不能太靠底部，
     * 因为底部信息区（文案/昵称）会吃掉手势。
     *
     * @param onDone 手势完成回调（true=成功派发）
     */
    fun swipeUp(onDone: (Boolean) -> Unit) {
        // 服务被系统断开后 dispatchGesture 只会静默失败（onCancelled），
        // 因此这里先检查连接状态，让调用方能区分"手势没做成"和"服务坏了"。
        if (!connected) {
            Log.w(TAG, "无障碍服务未连接，上滑跳过（请重新开启无障碍）")
            onDone(false)
            return
        }
        // ---- 安全闸门：前台不是快手就绝不滑动 ----
        //
        // 这是**最后一道防线**，放在服务里而不是只放在任务循环里：
        // 任何调用路径（任务循环、调试代码、未来新增的功能）都必须经过它。
        // 实测事故：用户切到天气应用后任务仍持续上滑，在第三方应用里乱点。
        if (!isTargetForegroundStrict()) {
            Log.w(
                TAG,
                "前台不是快手（当前=${currentForegroundPackage() ?: "未知"}），拒绝上滑",
            )
            onDone(false)
            return
        }
        // 上一个手势还没结束时再派发会被系统直接取消（onCancelled），
        // 因此这里显式拒绝重叠，并让调用方按"未执行"处理。
        if (!gestureFree.compareAndSet(true, false)) {
            Log.w(TAG, "上一个手势尚未结束，跳过本次上滑")
            onDone(false)
            return
        }
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        // x 走廊：屏幕左侧 1/3 带内抖动，绝不进入右侧操作栏
        val safeLeft = (w * SWIPE_X_MIN_RATIO).toInt()
        val safeRight = (w * SWIPE_X_MAX_RATIO).toInt()
        val corridorCenter = (safeLeft + safeRight) / 2
        val jitter = Random.nextInt(-JITTER_X, JITTER_X + 1)
        val x = (corridorCenter + jitter).coerceIn(safeLeft, safeRight)

        val yStart = pickStartY(h)
        val yEnd = (h * SWIPE_Y_END_RATIO).toInt() +
            Random.nextInt(-JITTER_Y, JITTER_Y + 1)
        val duration = BASE_DURATION + Random.nextInt(0, DURATION_JITTER)

        // ---- 横向漂移：让轨迹不再是一刀切的竖直线 ----
        //
        // 真人上滑有两点特征：中段略偏（弓形），收指时还带一点横向位移。
        // 两处漂移都必须留在 x 走廊内——走廊是"绝不进入右侧操作栏"的硬约束，
        // 轨迹一旦被推出去，末端就会落到 x≥915 的点赞/评论按钮上
        // （实测踩过：上滑被判成点击评论，评论面板把流程卡死）。
        // 因此这里不是"抽一个数直接用"，而是按当前剩余空间夹住：
        // 起手点位于走廊中心附近时这个夹紧不起作用，只在极端取值时才生效。
        val roomLeft = (safeLeft - x).toFloat()
        val roomRight = (safeRight - x).toFloat()
        val bow = Random.nextInt(-JITTER_CURVE, JITTER_CURVE + 1).toFloat()
            .coerceIn(roomLeft, roomRight)
        val tail = Random.nextInt(-JITTER_CURVE, JITTER_CURVE + 1).toFloat()
            .coerceIn(roomLeft - bow, roomRight - bow)

        // 起手先原地停顿 HOLD_MS 再上滑：让系统明确判定为"拖拽"而非"点击"，
        // 避免快速起停被当成 tap 落在下面的可点控件上。
        val span = (yStart - yEnd).toFloat()
        val path = Path().apply {
            moveTo(x.toFloat(), yStart.toFloat())
            lineTo(x.toFloat(), yStart.toFloat())
            // 平滑曲线用等分折线近似：与原本"直线也是一条折线"同源，
            // 不引入额外 API，也不改变手势的起止点与总时长。
            for (i in 1..CURVE_STEPS) {
                val t = i.toFloat() / CURVE_STEPS
                val curveY = yStart - span * t
                val curveX = x + bow * t * t + tail * t * t * t
                lineTo(curveX, curveY)
            }
        }


        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path, 0, (HOLD_MS + duration).toLong(),
                )
            )
            .build()

        // 日志前缀 "上滑 x=" 被 research 下的分析脚本解析，保持不变；
        // 漂移量追加在后面，便于核对每次手势的实际轨迹形状。
        Log.i(
            TAG,
            "上滑 x=$x y=$yStart->$yEnd dur=${HOLD_MS + duration}ms " +
                "漂移=${"%.0f".format(bow)} 收指=${"%.0f".format(tail)}",
        )

        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureFree.set(true)
                onDone(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureFree.set(true)
                onDone(false)
            }
        }, null)

        if (!ok) {
            Log.w(TAG, "手势派发失败")
            gestureFree.set(true)
            onDone(false)
        }
    }

    /**
     * 计算上滑起手点的 y 坐标（屏幕像素）。
     *
     * 关键约束：**起手点绝不能落在视频区中部的可点按钮带里**
     * （[TAP_BAND_TOP_RATIO]..[TAP_BAND_BOTTOM_RATIO]）。
     * 实测直播卡片上那个"点击进入直播间"按钮就在这条带内，
     * 起手点落上去会被系统判成点击，直接把任务带进直播间
     * （实测连续 35 次被困在直播流里）。
     *
     * 实现上不靠"调好一个比例"，而是显式做区间排除：
     * 先在默认位置加抖动；一旦落进禁区，就推送到禁区下沿之外。
     * 这样换任何分辨率/宽高比都不会再踩到这个坑。
     */
    private fun pickStartY(h: Int): Int {
        val bandTop = (h * TAP_BAND_TOP_RATIO).toInt()
        val bandBottom = (h * TAP_BAND_BOTTOM_RATIO).toInt()
        val navTop = (h * NAV_BAR_TOP_RATIO).toInt()

        var y = (h * SWIPE_Y_START_RATIO).toInt() +
            Random.nextInt(-JITTER_Y, JITTER_Y + 1)

        // 落进中部按钮带 → 推到带下方
        if (y in bandTop..bandBottom) {
            y = bandBottom + 1 + Random.nextInt(0, JITTER_Y + 1)
        }
        // 不要压到底部导航栏上（导航栏区域里有"我/去赚钱"等按钮）
        if (y >= navTop - NAV_BAR_GUARD_PX) {
            y = navTop - NAV_BAR_GUARD_PX - Random.nextInt(0, JITTER_Y + 1)
        }

        return y.coerceIn(1, h - 1)
    }

    /**
     * 返回键（用于关闭评论面板、退出直播流等）。
     *
     * 同样带安全闸门：在别人的应用里按返回键会把用户自己的页面关掉，
     * 与误触点按一样属于必须避免的副作用。
     *
     * @param allowOffTarget 仅用于"确实有意离开当前页"的场景；
     *   默认 false，即前台不是快手时拒绝执行
     */
    fun pressBack(allowOffTarget: Boolean = false): Boolean {
        if (!allowOffTarget && !isTargetForegroundStrict()) {
            Log.w(
                TAG,
                "前台不是快手（当前=${currentForegroundPackage() ?: "未知"}），拒绝返回键",
            )
            return false
        }
        return try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 给点击落点叠加小范围随机抖动。
     *
     * 幅度刻意取得比调用方余量小一个量级：暂停点与恢复点周围都留了
     * 100px 以上的安全距离（见 ScrollTask 的取点约束与
     * 研究脚本 research/_round2/point_collision.py 的碰撞检查），
     * 因此 ±TAP_JITTER_U / ±TAP_JITTER_V 的抖动不可能把落点带上可点控件。
     *
     * 坐标收敛在屏幕范围内，避免把点击派发到屏幕外。
     */
    private fun jitteredTapPoint(x: Int, y: Int): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val nx = (x + Random.nextInt(-TAP_JITTER_U, TAP_JITTER_U + 1))
            .coerceIn(0, (dm.widthPixels - 1).coerceAtLeast(0))
        val ny = (y + Random.nextInt(-TAP_JITTER_V, TAP_JITTER_V + 1))
            .coerceIn(0, (dm.heightPixels - 1).coerceAtLeast(0))
        return nx to ny
    }

    /**
     * 在指定屏幕坐标点一下。
     *
     * 用途：实测首页视频的进度条**不是一直在画**——播放时常常整条不画，
     * 暂停后才显示出来（20 个页面里 7 个如此）。因此读不到进度条时，
     * 需要"暂停一下把进度条逼出来读一次、再恢复播放"。
     * 暂停/恢复就是点视频区，这与上滑手势是两个不同动作，必须单独实现。
     *
     * @param onDone 结果回调（true=手势成功派发）
     */
    fun tapAt(x: Int, y: Int, onDone: ((Boolean) -> Unit)? = null) {
        if (!connected) {
            Log.w(TAG, "无障碍服务未连接，点击跳过 ($x,$y)")
            onDone?.invoke(false)
            return
        }
        // 安全闸门同上：点击也要确保前台是快手，否则会在别人的应用里乱点
        if (!isTargetForegroundStrict()) {
            Log.w(
                TAG,
                "前台不是快手（当前=${currentForegroundPackage() ?: "未知"}），拒绝点击 ($x,$y)",
            )
            onDone?.invoke(false)
            return
        }
        if (!gestureFree.compareAndSet(true, false)) {
            Log.w(TAG, "上一个手势尚未结束，跳过本次点击 ($x,$y)")
            onDone?.invoke(false)
            return
        }
        // 落点抖动：真人手指不会每次都点在同一个像素上。
        // 调用方给的是"理想落点"（按屏幕比例算出来的），实际落点在这里再叠一层小抖动。
        val (tapX, tapY) = jitteredTapPoint(x, y)
        val path = Path().apply {
            moveTo(tapX.toFloat(), tapY.toFloat())
            lineTo(tapX.toFloat(), tapY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(path, 0, TAP_HOLD_MS.toLong())
            )
            .build()
        Log.i(TAG, "点击 x=$tapX y=$tapY (理想落点 $x,$y)")
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureFree.set(true)
                onDone?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureFree.set(true)
                onDone?.invoke(false)
            }
        }, null)
        if (!ok) {
            Log.w(TAG, "点击派发失败 ($tapX,$tapY)")
            gestureFree.set(true)
            onDone?.invoke(false)
        }
    }

    /**
     * 前台是否真的是快手的**直播 Activity**。
     *
     * 为什么需要单独一个判据（实测）：
     * 首页会**预加载**直播视图，因此首页与直播间的节点 id 集合完全相同
     * （都含 `live_slide_container` / `live_slide_view_pager` / ...），
     * 基于 id 的判据会误判。而 `currentWindowClass` 在预览卡自动跳转后
     * 可能来不及更新，导致已经进了直播间却仍被判成首页。
     *
     * 因此这里直接读窗口列表里**带焦点窗口**所属 Activity 的类名，
     * 这是最贴近"当前真正在显示什么"的信号。
     *
     * @return true 表示前台确实是直播相关 Activity
     */
    fun isLiveActivityForeground(): Boolean {
        // 1) 先看窗口列表里活动/带焦点的窗口
        try {
            for (w in windows) {
                if (!w.isActive && !w.isFocused) continue
                val r = safeWindowRoot(w) ?: continue
                val pkg = r.packageName?.toString() ?: continue
                if (pkg != PKG_KUAISHOU_TARGET) continue
                if (isLiveClassName(AutoScrollService.currentWindowClass)) return true
            }
        } catch (_: Throwable) {
        }
        // 2) 退回事件回调缓存的类名
        return isLiveClassName(currentWindowClass)
    }

    /** 类名是否属于直播相关 Activity */
    private fun isLiveClassName(cls: String?): Boolean {
        if (cls.isNullOrEmpty()) return false
        return cls.contains("LiveSlideActivity") ||
            cls.contains("LivePlayActivity") ||
            cls.contains("live.core.basic.activity")
    }

    /** 屏幕尺寸（像素），取不到时返回 0×0 */
    fun screenSize(): Pair<Int, Int> {
        return try {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        } catch (_: Throwable) {
            0 to 0
        }
    }

    /** 回到桌面 */
    fun goHome(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 拉起快手极速版首页。
     *
     * @return true 表示已派发启动意图（不代表已到达首页）
     */
    fun launchKuaishou(): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(PKG_KUAISHOU_TARGET)
            if (intent == null) {
                Log.w(TAG, "拿不到快手启动意图（检查 manifest 的 <queries> 声明）")
                return false
            }
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            Log.i(TAG, "拉起快手: $intent")
            startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "拉起快手失败", t)
            false
        }
    }

    /**
     * 当前前台应用的包名（**任何**应用）。
     *
     * 三个来源取并集，与 [isKuaishouForeground] 同一套"多判据"思路，
     * 但这里要回答的是"现在屏幕上是谁"：
     * 1. `rootInActiveWindow`：最准，但本应用自己的悬浮窗出现时会变成自己
     * 2. 事件回调维护的 [foregroundPackage]：切应用必有窗口状态变化事件，
     *    因此这是主要来源
     * 3. 窗口列表里带焦点的那个窗口
     *
     * @return 包名；完全读不到时返回 null（调用方应保守处理）
     */
    fun currentForegroundPackage(): String? {
        // 1) 活动窗口最准
        try {
            val r = rootInActiveWindow
            val pkg = r?.packageName?.toString()
            if (!pkg.isNullOrEmpty()) return pkg
        } catch (_: Throwable) {
        }

        // 2) 事件回调记录（切应用必有事件）
        foregroundPackage?.let { return it }

        // 3) 窗口列表里带焦点/活动的那个
        try {
            for (w in windows) {
                if (!w.isFocused && !w.isActive) continue
                val r = safeWindowRoot(w) ?: continue
                val pkg = r.packageName?.toString()
                if (!pkg.isNullOrEmpty()) return pkg
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * 前台是否是**目标应用（快手）**。
     *
     * 与 [isKuaishouForeground] 的区别：这里是"严格版"，用于**派发手势前的
     * 最后一道闸门**。只有明确读到前台就是快手才返回 true；
     * 读不到任何前台信息时返回 false（宁可停下来，也不要在别人的应用里乱点）。
     *
     * 实测背景：任务曾在用户切到天气应用后仍持续上滑
     * （`currentPackage` 停在快手，判定以为还在快手首页），
     * 这会在第三方应用里产生误触，属于必须堵住的安全问题。
     */
    fun isTargetForegroundStrict(): Boolean {
        val pkg = currentForegroundPackage() ?: return false
        // 自己的悬浮窗覆盖时 rootInActiveWindow 会是自己，
        // 这时要用窗口列表判断快手是否仍在（悬浮窗是叠加层，不是全屏 Activity）
        if (pkg == packageName) {
            return kuaishouWindows().isNotEmpty()
        }
        return pkg == PKG_KUAISHOU_TARGET
    }

    /**
     * 当前前台是否是本应用自己。
     * 用于避免在用户停留在本应用界面时误判/误滑。
     *
     * 注意：本应用自己也有一个常驻的 APPLICATION_OVERLAY 悬浮窗，
     * 因此**不能**只看"窗口列表里有没有本应用的窗口"——那会恒为 true，
     * 于是在快手已经在前台时误报"本应用未退到后台"（实测踩过）。
     * 判据改为"快手不在前台（按最严格的方式判断）"。
     */
    fun isSelfForeground(): Boolean {
        // 快手确实有窗口 → 本应用最多是那个悬浮窗，不算占据前台
        if (kuaishouWindows().isNotEmpty()) return false
        // 本应用有窗口，且没有任何证据表明快手在前台 → 判定为本应用在前台
        return try {
            for (w in windows) {
                val r = safeWindowRoot(w) ?: continue
                if (r.packageName?.toString() == packageName) return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 当前前台是否是快手。
     *
     * 三个判据取并集，因为实测三者都可能单独失效：
     * - `windows` 列表：最可靠，但服务刚连上/窗口切换瞬间可能还没刷新
     * - `rootInActiveWindow`：活动窗口被本应用占据时会返回本应用
     * - `currentPackage`：来自最近一次窗口事件；服务晚于快手启动时才有效
     * 只用其中一个都会出现"快手明明在前台却判成不在前台"（实测踩过：
     * 对齐阶段反复报"等待快手前台超时"）。
     */
    fun isKuaishouForeground(): Boolean {
        if (kuaishouWindows().isNotEmpty()) return true
        try {
            if (rootInActiveWindow?.packageName?.toString() == PKG_KUAISHOU_TARGET) return true
        } catch (_: Throwable) {
        }
        // currentPackage 只在"最近的事件来自快手"时更新；
        // 本应用一启动就会覆盖它，因此只在没有其它证据时作为兜底，
        // 且必须同时确认本应用不在前台。
        if (currentPackage == PKG_KUAISHOU_TARGET && !isSelfForeground()) return true
        return false
    }

    /**
     * 是否停在快手首页信息流。
     *
     * 判据用首页特有的容器 id（实测存在于窗口里）：
     * home_activity_root / home_fragment_container / slide_v2_content_layout。
     *
     * 除了窗口列表，还要查一次活动窗口：实测服务刚连上/进程重启后
     * `windows` 会短暂为空，只看窗口列表会得出"不在首页"的错误结论。
     */
    fun isHomeFeed(): Boolean {
        for (root in kuaishouWindows()) {
            if (hasMarker(root, HOME_FEED_MARKERS, 0, 40)) return true
        }
        try {
            val active = rootInActiveWindow
            if (active?.packageName?.toString() == PKG_KUAISHOU_TARGET &&
                hasMarker(active, HOME_FEED_MARKERS, 0, 40)
            ) {
                return true
            }
        } catch (_: Throwable) {
        }
        return false
    }

    /**
     * 窗口信息是否可用（节点树能不能读到）。
     *
     * 用于区分"确实不在首页"和"根本读不到窗口"两种状态：
     * 后者不能当作"不在首页"去按返回键，否则会把用户从首页一路退出去。
     */
    fun hasWindowInfo(): Boolean {
        if (kuaishouWindows().isNotEmpty()) return true
        return try {
            rootInActiveWindow != null
        } catch (_: Throwable) {
            false
        }
    }

    /** 取所有属于快手的窗口根节点 */
    private fun kuaishouWindows(): List<android.view.accessibility.AccessibilityNodeInfo> {
        val out = ArrayList<android.view.accessibility.AccessibilityNodeInfo>(2)
        try {
            for (w in windows) {
                val r = safeWindowRoot(w) ?: continue
                if (r.packageName?.toString() == PKG_KUAISHOU_TARGET) out.add(r)
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun safeWindowRoot(
        w: android.view.accessibility.AccessibilityWindowInfo,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        return try {
            w.root
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 主动查询当前窗口的 Activity 类名。
     *
     * 无障碍事件缓存可能拿不到（服务在快手已打开时才连接），
     * 且节点树不含 Activity 名，因此用 windows 列表里的 root 包名 + 
     * 已知 Activity 特征来推断。这里优先用缓存，缓存为空时主动探测。
     */
    fun resolveWindowClass(): String? {
        currentWindowClass?.let { return it }

        // 主动探测：从 windows 列表找目标应用的窗口
        return try {
            for (root in kuaishouWindows()) {
                // 通过该窗口子树里的特征控件推断是首页还是直播间
                val isLive = hasMarker(root, LIVE_PROBE_MARKERS, 0, 30)
                return if (isLive) LIVE_ACTIVITY_NAME else HOME_ACTIVITY_NAME
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun hasMarker(
        node: android.view.accessibility.AccessibilityNodeInfo,
        markers: List<String>,
        depth: Int,
        maxDepth: Int,
    ): Boolean {
        if (depth > maxDepth) return false
        try {
            val id = node.viewIdResourceName ?: ""
            val cls = node.className?.toString() ?: ""
            for (m in markers) {
                if (id.contains(m, ignoreCase = true) || cls.contains(m, ignoreCase = true)) {
                    return true
                }
            }
            val n = node.childCount
            for (i in 0 until n) {
                val c = node.getChild(i) ?: continue
                if (hasMarker(c, markers, depth + 1, maxDepth)) return true
            }
        } catch (_: Throwable) {
            // 节点可能在遍历途中失效，跳过即可
        }
        return false
    }

    companion object {
        private const val TAG = "AutoScrollService"
        /** 目标应用包名（快手极速版） */
        const val PKG_KUAISHOU_TARGET = "com.kuaishou.nebula"

        /** 供悬浮窗/任务线程访问的服务实例 */
        @Volatile
        var instance: AutoScrollService? = null
            private set

        /**
         * 最近一次窗口状态变化事件里的 Activity 类名。
         * 实测：dumpsys window 的 mCurrentFocus 等价信息，
         * 直播间为 com.kuaishou.live.core.basic.activity.LiveSlideActivity。
         */
        @Volatile
        var currentWindowClass: String? = null
            private set

        /** 最近一次窗口事件里的包名 */
        @Volatile
        var currentPackage: String? = null
            private set

        /**
         * 当前**前台**应用的包名（任何应用都会更新）。
         *
         * 与 [currentPackage] 的区别：[currentPackage] 只在快手的事件里更新，
         * 因此离开快手后它仍停在快手；而这个是判断"现在屏幕上是谁"的
         * 唯一可靠来源，用于防止在别人的应用里派发手势。
         */
        @Volatile
        var foregroundPackage: String? = null
            private set

        /**
         * 快手的"窗口内容变化"事件计数器（单调递增）。
         *
         * 用途：给"视频播完了"提供一条**独立于像素**的旁证。
         * 进度条读数来自截图分析（脆弱、易受遮挡影响），而这条信号来自
         * 系统无障碍事件通道，两者原理完全不同——同时指向"页面变了"
         * 时判定更可信。任务循环只读它，不参与手势。
         *
         * 实测：视频切换时快手会产生 `TYPE_WINDOW_CONTENT_CHANGED`，
         * 因此"计数变化 + 进度回退"组合出现即是一次可靠的换页。
         */
        @Volatile
        var contentChangeSeq: Long = 0L
            private set

        /**
         * 手势闸门：true 表示当前没有手势在执行。
         *
         * 系统一次只接受一个 [dispatchGesture]，重叠派发会被直接取消
         * （回调落到 onCancelled）。实测曾因此形成"每 0.4 秒派发一次、
         * 每次都被取消"的上滑风暴。
         */
        private val gestureFree = java.util.concurrent.atomic.AtomicBoolean(true)

        /** 服务是否仍连着无障碍框架（见 [isConnected]） */
        @Volatile
        private var connected = false

        /** 实测的首页 Activity 名 */
        const val HOME_ACTIVITY_NAME = "com.yxcorp.gifshow.HomeActivity"

        /** 实测的直播间 Activity 名 */
        const val LIVE_ACTIVITY_NAME = "com.kuaishou.live.core.basic.activity.LiveSlideActivity"

        /** 主动探测直播间时使用的标志控件 */
        private val LIVE_PROBE_MARKERS = listOf(
            "live_close",
            "live_gift_wall",
            "live_audience_count",
            "live_input_edit",
        )

        /**
         * 首页信息流的标志控件（实测存在于首页窗口节点树里）。
         * 用于"是否已停在首页"的判断。
         */
        private val HOME_FEED_MARKERS = listOf(
            "home_activity_root",
            "slide_v2_content_layout",
            "nasa_slide_play_view_pager_layout",
        )

        /**
         * 上滑的水平走廊（屏幕宽度的比例）。
         *
         * 实测右侧操作栏 group_right_action_bar_root_layout 占 x=915~1080，
         * 评论按钮就在其中；手势起手落到那里会被识别成点击评论。
         * 因此走廊收在屏幕左侧这一段，远离操作栏。
         */
        private const val SWIPE_X_MIN_RATIO = 0.12f
        private const val SWIPE_X_MAX_RATIO = 0.42f

        /**
         * 上滑起点（屏幕高度比例）。
         *
         * 实测踩坑：直播间卡片上盖着一个"点击进入直播间"按钮，
         * 包围盒 y 1687..1800 = **0.70h..0.75h**、x 290..830 = 0.27w..0.77w。
         * 而原起点 0.72h±80px 与 x 0.12w..0.42w 都和该按钮重叠，
         * 于是上滑被判成点击 → 直接进入直播间（实测连续 35 次困在直播流）。
         *
         * 现在把起点移到按钮带**下方**（0.84h），底部导航栏（0.944h 起）
         * 之上，既不碰中部按钮，也不碰导航栏。
         */
        private const val SWIPE_Y_START_RATIO = 0.84f

        /**
         * 上滑起点必须避开的"中部按钮带"（屏幕高度比例）。
         *
         * 见 [SWIPE_Y_START_RATIO]。取按钮包围盒实测值并向两侧留出余量，
         * 覆盖"点击进入直播间"这类覆盖在视频中部的可点控件。
         */
        private const val TAP_BAND_TOP_RATIO = 0.66f
        private const val TAP_BAND_BOTTOM_RATIO = 0.80f

        /** 上滑终点（屏幕高度比例） */
        private const val SWIPE_Y_END_RATIO = 0.22f

        /**
         * 底部导航栏上沿（屏幕高度比例）。
         *
         * 实测首页视频区底边 = 0.944h（2266/2400），导航栏从这里开始。
         * 起手点压到导航栏上有两个坏处：可能点到"我/去赚钱"这类按钮，
         * 而且上滑会被导航栏吞掉。
         */
        private const val NAV_BAR_TOP_RATIO = 0.944f

        /** 起手点与导航栏之间至少留出的像素距离 */
        private const val NAV_BAR_GUARD_PX = 24

        /** 水平抖动范围（像素），围绕走廊中心 */
        private const val JITTER_X = 40

        /** 纵向起止点抖动范围（像素） */
        private const val JITTER_Y = 80

        /**
         * 上滑轨迹的横向漂移上限（像素）。
         *
         * 真人上滑不是竖直线：中段有轻微弧度，收指时还有一点横向位移。
         * 两项各留 ±[JITTER_CURVE]，因此轨迹离起手点的横向偏移不超过 2 倍
         * （实测机型上 52px ≈ 屏宽的 4.8%），末段横向速度对应的夹角约 0°~5°。
         *
         * 取值理由：明显小于 [JITTER_X]（40），避免曲线把整条轨迹推离走廊；
         * 同时足以让每次手势不再都是"完美竖直的一刀切"。
         */
        private const val JITTER_CURVE = 26

        /**
         * 曲线分段数（等分折线近似）。
         *
         * 取 8：实测机型上相邻采样点相距约 180px，与系统注入器自身的
         * 采样步长（约 1000 事件/秒）相当，折角细到看不出来；
         * 再翻倍只会增加节点数，不改变形状。
         */
        private const val CURVE_STEPS = 8

        /** 起手原地停顿，用于区分"拖拽"与"点击" */
        private const val HOLD_MS = 40

        /** 单点点击的按压时长：够短才算点击，够长才不被判成滑动 */
        private const val TAP_HOLD_MS = 60

        /**
         * 点击落点的抖动幅度（像素）。
         *
         * 真人手指的可重复性有限，不会每次都落在同一个像素上。
         * 幅度取小值，远小于调用方余量，因此"像真人"与"不误触控件"可以同时成立。
         *
         * 纵向（[TAP_JITTER_V]）比横向（[TAP_JITTER_U]）给得多一点：
         * 手指在竖直方向本来就更难重复，而这些落点在竖直方向上的余量也更大。
         */
        private const val TAP_JITTER_U = 12
        private const val TAP_JITTER_V = 20

        /**
         * 滑动时长。实测 250~370ms 的快速滑有时会被系统判成 fling/点击，
         * 放缓到 320~460ms，配合起手停顿，手势识别更稳定。
         */
        private const val BASE_DURATION = 320
        private const val DURATION_JITTER = 140
    }
}
