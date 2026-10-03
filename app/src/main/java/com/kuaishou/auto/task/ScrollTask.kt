package com.kuaishou.auto.task

import android.util.Log
import com.kuaishou.auto.AppSettings
import com.kuaishou.auto.detector.PageDetector
import com.kuaishou.auto.detector.ProgressDetector
import com.kuaishou.auto.detector.SeekBarProbe
import com.kuaishou.auto.detector.UiStateProbe
import com.kuaishou.auto.service.AutoScrollService
import com.kuaishou.auto.service.FrameSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 主任务循环。
 *
 * 实机调研依据：
 * - 首页视频进度条在 y=2262，视频循环播放，周期 9~12 秒
 * - 直播间 Activity 为 LiveSlideActivity，无进度条
 *
 * 时序约束（避免误判导致翻页过快）：
 * - 每页至少停留 MIN_WATCH_MS 才允许滑动
 * - "进度回退"用本页进度峰值判据（曾经播到高位、随后大幅回落），
 *   不能用"连续两次回退"：循环周期 9~12 秒远大于轮询间隔，
 *   回退后的下一帧进度已在上升，连击计数会被清零而永远判不出播完
 * - 接近播完需连续多次读到高进度，单次噪声不触发
 */
class ScrollTask(
    private val capture: FrameSource,
    private val onStateChange: (String) -> Unit,
    /**
     * 进度条读数回调（0.0~1.0）。
     *
     * 传 **null 表示"当前没有读数"**（换页时清空、或本帧没有可读进度），
     * 界面据此隐藏数字。必须支持 null：换页后要立刻清掉上一段的读数，
     * 否则悬浮窗会一直挂着旧值，看起来像"进度没重置"（实测踩过）。
     */
    private val onProgress: ((Float?) -> Unit)? = null,
    /**
     * 单条视频的停留上限（分钟），来自设置页，默认 5。
     *
     * 实际生效值会在每页抽一次 ±[AppSettings.WATCH_JITTER_RATIO] 的抖动，
     * 因此不是"整 5 分钟"（用户要求：不要刚刚好 5 分钟）。
     */
    private val maxWatchMinutes: Int = AppSettings.DEFAULT_MAX_WATCH_MIN,
) {

    companion object {
        private const val TAG = "ScrollTask"

        /** 轮询间隔 */
        private const val POLL_INTERVAL_MS = 350L

        /**
         * 进度"停滞"多久判定为本页不会再播了。
         *
         * **这是替代原来 `MAX_PAGE_WAIT_MS = 60s` 无条件超时的关键改动。**
         *
         * 原实现的问题（实测）：`timeoutHit = watchMs > 60s` 只看时间流逝，
         * 与进度完全无关。结果 5 次滑动里 4 次都是它触发的，
         * 滑走时进度只有 13%~62% —— **视频明明还在正常播放就被换掉了**。
         *
         * 现在改为看"进度是否还在推进"：只要还在推进就绝不滑走，
         * 只有长时间读不到推进（页面冻结 / 进度条卡死 / 播放停止）
         * 才滑走，避免死等。
         */
        private const val STALL_TIMEOUT_MS = 40_000L

        /**
         * 判定"进度在推进"的最小累积涨幅（1%）。
         *
         * 取 1% 而不是逐帧比较：进度条像素精度约 0.1%，而长视频
         * （5~10 分钟）在 0.4 秒轮询下每次只涨 0.07%~0.13%，
         * 逐帧比较会被量化噪声淹没、把"正常播放"误判成"停滞"。
         * 用累积涨幅则长视频也会自然涨过 1%，可靠区分"在播"与"卡住"。
         */
        private const val ADVANCE_MIN_DELTA = 0.01f

        /**
         * "换页事件"信号的保鲜期。
         *
         * 无障碍服务收到快手的窗口内容变化事件后，只在此时间内算作
         * "刚刚发生过页面变化"。用于给"播完"判定做**独立于像素的旁证**：
         * 像素路径与事件路径同时指向"页面变了"，比单看像素更可信。
         */
        private const val CONTENT_CHANGE_FRESH_MS = 2_500L

        /**
         * 上滑后等待"画面换成新视频"的最长时间。
         *
         * 正常换页后节点树里的作者/标题文字会立刻更新（实测约 1 秒内），
         * 因此 4 秒足够。超时则放弃校验、按原逻辑继续——
         * 有些页面（广告、无文案视频）可能拿不到标识，此时宁可漏判也不能卡死。
         */
        private const val FRESH_VIDEO_TIMEOUT_MS = 4_000L

        /**
         * 确认换页后再丢帧的时长。
         *
         * 原因：换页判据来自**节点树**（作者/标题文字），而进度来自**截图**。
         * 两者更新不同步——实测刚确认换页就读到 `0.0% -> 71.5%`（旧视频的进度），
         * 同时出现 `无进度条 强度=0.0 行号=-1`（全黑帧）。
         * 因此确认后仍要丢弃一小段，等视频层真正切新再开始读数。
         */
        private const val FRESH_VIDEO_SETTLE_MS = 700L

        /**
         * 换页后逐帧打日志的帧数（诊断"新片段开头进度跳变"用）。
         *
         * 平时每 6 帧才记一条，看不清跳变形态；换页后前若干帧必须逐帧记。
         */
        private const val POST_SWIPE_LOG_FRAMES = 16

        /**
         * 换页后"投票冷静期"的帧数。
         *
         * 这段时间内不采信"内容变化/接近播完"这些弱信号，只认强回退。
         * 原因见投票处的注释：换页本身会刷新内容变化时间戳，
         * 若不设冷静期，它会被误当成"本页播完"的证据，导致刚换页就划走。
         */
        private const val VOTE_COOLDOWN_FRAMES = 12

        /**
         * 锚点相对基准允许的最大累计漂移（像素）。
         *
         * 实测：锚点曾从 2262 逐步漂到 2139（累计 123px），每步都小于
         * `ANCHOR_TOLERANCE`(60) 因而全部放行。取 40 作为累计上限，
         * 既允许真实进度条位置的正常微调（不同页面可能差几像素），
         * 又能挡住这种渐进漂移。
         */
        private const val ANCHOR_MAX_DRIFT = 40

        /**
         * 允许校准锚点的最低"横向覆盖率"。
         *
         * 真实进度条横贯整屏（实测覆盖 0.92~0.95）；
         * 视频内容里的高光/字幕很少能横贯 90% 以上宽度。
         * 用这个形态特征把非进度条的行挡在门外。
         */
        private const val ANCHOR_MIN_COVERAGE = 0.85f

        /**
         * 换页后多少帧内，拒绝"不可能的高进度"读数。
         *
         * 刚换过来的视频必定从 0% 附近开始。若在开头就读到很高的进度，
         * 那一定是**上一个视频的残留帧**（视频层未切新）。
         * 实测证据：换页后第 1 帧读到 `80%`（强度 67.7，且第 2 帧又掉到 29.0），
         * 紧接着就被"多信号"判定划走 —— 用户看到的就是
         * "九十几就上滑，下一段开头进度数字跳变"。
         */
        private const val POST_SWIPE_MAX_PLAUSIBLE = 0.35f

        /**
         * 两次"直播卡片划走"之间的最小间隔。
         *
         * 实测：卡片节点的清除比画面切换慢，刚划过卡片但还没换页时
         * `isLiveCard` 会重复命中，日志里连续出现 14 次
         * 「检测到直播卡片」，于是成对连滑（间隔仅 1.7 秒）。
         * 这个间隔用于把重复命中识别为"残留节点"并跳过。
         */
        private const val LIVE_CARD_MIN_INTERVAL_MS = 3_000L

        /** 每页最短观看时长：低于此值绝不滑动，防止连滑 */
        private const val MIN_WATCH_MS = 3_000L

        /**
         * 连续读不到进度条多少次后，进入"随机等待再划走"模式。
         *
         * 这类页面（广告、直播卡片、播放时不画进度条的视频）像素路径拿不到
         * 任何读数。用户要求：**不再点击暂停去确认**，而是在 2 分钟内
         * 随机选一个时刻划过去。原先的暂停采样已全部移除，原因见主循环注释。
         */
        private const val NO_BAR_THRESHOLD = 6

        /**
         * 无进度条页面的随机停留时长下限。
         *
         * 取 8 秒：明显长于 [MIN_WATCH_MS]（3 秒），确保确实停留过一段时间，
         * 不会因为"恰好读不到进度"就秒滑过去。
         */
        private const val NO_BAR_WAIT_MIN_MS = 8_000L

        /**
         * 无进度条页面的随机停留时长上限 = 2 分钟。
         *
         * 用户要求"在 2 分钟内的时间选择一个随机的时间之后划过去"。
         */
        private const val NO_BAR_WAIT_MAX_MS = 120_000L

        /**
         * 手势连续失败多少次就判定无障碍服务已失效并中止任务。
         *
         * 取 3：正常情况下手势偶发失败会自己恢复（`swipeAndReset` 会复位计数），
         * 而失效状态是**每次都失败**，3 次足够区分，且每次间隔一个页面周期
         * （十几秒到 60 秒），不会因为网络/卡顿之类的一次抖动就误停。
         */
        private const val GESTURE_FAIL_ABORT = 3

        /**
         * 上滑手势的起手点必须避开的"中部按钮带"（屏幕比例）。
         *
         * 实测：直播卡片的"点击进入直播间"按钮在 y 1687..1800
         * （0.70h..0.75h），而上滑起手点 y=0.72h ± 80px 正好落在其中，
         * x=0.12w..0.42w 也与按钮 x 范围重叠 → 上滑被判成点击 → 进直播间。
         * 实测连续 35 次被困在直播流里就是这个原因。
         */
        const val TAP_BAND_TOP_RATIO = 0.68f
        const val TAP_BAND_BOTTOM_RATIO = 0.78f

        /** 上滑后的稳定等待 */
        private const val AFTER_SWIPE_DELAY_MS = 1_000L

        /** 直播间最短停留 */
        private const val LIVE_MIN_DELAY_MS = 1_500L

        /**
         * 连续滑走多少个直播间后主动按返回键退出直播流。
         *
         * 实测：直播间内部上滑只会切换直播间，**永远回不到**普通视频首页
         * （连续 35 次上滑、手动连滑 6 次都仍在 LiveSlideActivity），
         * 而一次返回键就能立即回到 HomeActivity。
         * 取 1：进入直播间不是期望行为（用户要的是刷普通视频），
         * 因此一旦识别到就立刻退出，不在直播流里浪费时间。
         */
        private const val LIVE_STREAK_BEFORE_BACK = 1

        /**
         * 退出直播流时最多按几次返回键。
         *
         * 实测：**一个直播间可能需要两次返回键**才回到首页
         * （踩过：第一次无效、第二次才退出）。因此每次按完都要复核页面类型。
         */
        private const val LIVE_EXIT_BACK_TRIES = 4

        /** 连续这么多次退不出直播流就停任务（避免死循环） */
        private const val MAX_LIVE_EXIT_FAIL = 3

        /**
         * 连续这么多次"不在上滑信息流里"就停任务。
         *
         * 通用容错兜底：任何未被单独识别的误入页面（图文详情、个人页、
         * 私信、搜索…）都会命中这条判据并按返回键退回，
         * 因此不需要为每种页面写专门逻辑。
         */
        private const val MAX_OFF_FEED_FAIL = 5

        /**
         * 允许"离开信息流→按返回"反复发生的次数上限。
         *
         * 实测事故：图文页上暂停探测会点出播放控制层（Dialog），
         * 触发"缺首页标志 → 按返回"，而返回后 `resetPage` 又把探测标记清掉，
         * 于是下次回到首页又做暂停采样 → 又弹出控制层，8 秒一轮跑了 135 轮。
         * 这个上限是独立于 [MAX_OFF_FEED_FAIL] 的第二道保险：
         * 即便单轮都"成功回到信息流"，反复出现也说明在打转。
         */
        private const val MAX_OFF_FEED_RECOVER = 6

        /**
         * 检测到"前台不是快手"累计这么多次后，主动把快手拉回前台。
         *
         * 取 2 而不是 1：切换应用/系统弹窗可能产生一次瞬时误判，
         * 若立刻拉起快手会把用户从正在用的应用里拽出来。
         * 但**手势派发是每一次都立刻停止的**（这条没有宽限），
         * 因为误触第三方应用是不可接受的。
         */
        private const val OFF_TARGET_REALIGN_AFTER = 2

        /** 接近播完判定的连续确认次数 */
        private const val NEAR_DONE_CONFIRM = 3

        /** 进度回退幅度阈值 */
        private const val LOOP_DROP = 0.45f

        /**
         * "回到起播"判据的读数上限。
         *
         * 与 [LOOP_RESTART_MIN_PEAK] 配对使用，两条路径（像素轮询 / 暂停探测）
         * 共用。详见下方 [LOOP_RESTART_MIN_PEAK] 的说明。
         */
        private const val LOOP_RESTART_MAX = 0.06f

        /**
         * "回到起播"判据所需的峰值下限。
         *
         * 实测漏判（真实缺陷）：一条视频探测到 `29% → 3%` 这种明显重播，
         * 却因为峰值 29% 未达 [CYCLE_MIN_PEAK]（60%）、
         * 也未达 [CYCLE_MIN_PEAK]（60%）而没被判成循环，
         * 只能白等到 40 秒停滞超时（甚至要等更久）。日志里能看到它把
         * 一条已经重播的视频继续当"在播"。
         *
         * 因此补一条判据：读数回到起播附近（<= [LOOP_RESTART_MAX]）
         * 且此前确实涨过（峰值 >= 本值）→ 判为短视频重播。
         *
         * 用"此前确实涨过"排除起播瞬间 `0% → 0%` 的噪声；
         * 用 20% 这个下限排除"刚开始播就被误读成 0%"的情况。
         */
        private const val LOOP_RESTART_MIN_PEAK = 0.20f

        /**
         * 判定"播放循环回退"所需的本页进度峰值下限。
         *
         * 低于这个值说明视频还没真正播起来（刚进页面的加载/起播阶段），
         * 此时读数的抖动不算"播完"。
         */
        private const val CYCLE_MIN_PEAK = 0.60f

        /** 接近播完的进度阈值 */
        /**
         * "接近播完"的判定阈值。
         *
         * 反馈："基本上进度数字 96% 的时候就划走"。96% 属于"已播完绝大部分"，
         * 但既然要求"没到 100% 不应该换页"，这里抬高到 98%，
         * 让换页更贴近真实播完；同时保留连续确认（[NEAR_DONE_CONFIRM]）防噪声。
         *
         * 不能直接设成 100%：进度条像素精度约 0.3%，且快播完时读数的
         * 最后一两个百分点常因播放器淡出/重播而读不到，
         * 强求 100% 会导致每页都退化到停滞超时，反而更差。
         */
        private const val NEARLY_DONE = 0.98f

        /** 启动时等待快手前台/首页出现的上限 */
        private const val LAUNCH_WAIT_MS = 8_000L

        /** 启动时等待无障碍服务连接的上限与轮询间隔 */
        private const val AX_READY_WAIT_MS = 8_000L
        private const val AX_READY_POLL_MS = 300L

        /** 窗口信息未就绪时的等待步长与次数上限 */
        private const val HOME_INFO_WAIT_MS = 600L
        private const val HOME_INFO_WAIT_TRIES = 6

        /** 评论面板恢复：最多连按几次返回键 */
        private const val MAX_BACK_RETRIES = 3

        /** 按下返回键后等待界面收起 */
        private const val AFTER_BACK_DELAY_MS = 1_200L

        /** 发现评论面板累计这么多次后给出提示并停止，避免死循环 */
        private const val MAX_PANEL_RECOVER_FAIL = 3

        /**
         * 遮挡弹层（电商/活动浮层）连续关不掉这么多次就停止。
         *
         * 比评论面板给得多一点：实测这类浮层有时会连续弹好几个，
         * 每个都能用一次返回关掉，但换成新的又会弹出来。
         */
        private const val MAX_POPUP_RECOVER_FAIL = 5

        /** 重新对齐首页的节流间隔，避免频繁把用户从别的应用拽回快手 */
        private const val REALIGN_THROTTLE_MS = 10_000L

        /** 等待本应用退到后台的上限（防止抓到自己的界面） */
        private const val SELF_BACKGROUND_WAIT_MS = 4_000L

        /** side_progress_group 底边到进度条行号的偏移（实测底边 2266、进度条 2262） */
        private const val BAR_ROW_OFFSET_FROM_GROUP_BOTTOM = 4

        /**
         * 用于取"视频区底边"的控件 id（实测首页存在）。
         *
         * 进度条一定在视频区内，底部导航栏里那条更亮的硬边界（2266 起）
         * 经常被误判成进度条，因此必须知道视频区到哪结束。
         */
        private val VIDEO_BOTTOM_IDS = listOf(
            "player",
            "nasa_slide_play_view_pager_layout",
            "slide_playerkit_view",
        )

        /** 像素自校准：行号连续多少次一致后才写回锚点 */
        private const val ANCHOR_CONFIRM = 6

        /**
         * 诊断开关：把节点树的可见文本打进日志。
         *
         * 由 MainActivity 通过 `--es dbg 1` 打开，默认关闭（避免刷屏）。
         * 用途：`uiautomator dump` 在视频页完全不可用，确认页面特征
         * （直播卡片文案、直播间控件等）只能靠应用自己的节点树。
         */
        @Volatile
        var debugTexts = false

        /**
         * 故障注入：跳过任务循环里的"前台不是快手"检查。
         *
         * **仅供验证服务层闸门用**，正常从不开启。
         * 通过 `--es skipfg 1` 打开，可在运行时证明
         * "即使任务循环漏判，服务层的 [AutoScrollService.isTargetForegroundStrict]
         * 也会拦下手势"——两道闸门都各自有效，而不是只靠一道。
         */
        @Volatile
        var skipForegroundCheckForTest = false
        /** 进程内唯一的进度条锚点自校准计数（见 onLastDetection） */
        private var anchorCandidate = -1
        private var anchorStableCount = 0
    }

    private var job: Job? = null

    /** 最近一次成功检测到的进度条几何（诊断/校准用） */
    private var lastBarY = -1
    private var lastTrackLeft = -1
    private var lastTrackRight = -1

    /** 最近一次写入检测器的视频区底边，用于抑制重复日志 */
    private var lastVideoBottom = -1

    /**
     * 锚点基准行（像素）。
     *
     * 首次校准时确立，之后只允许在 [ANCHOR_MAX_DRIFT] 内微调。
     * 目的是挡住"每步都合规、累计跑偏"的渐进漂移（实测漂了 123px）。
     * 对同机型的多个视频，真实进度条行是固定的，因此这个约束是安全的。
     */
    private var anchorBaselineY = -1

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * 记录最近一次检测到的进度条几何，并用它校准像素检测的行锚点。
     *
     * 实测：节点树里【没有】side_progress_group / slide_play_progress
     * （控件被标记 not-important），因此原来的"按控件底边校准锚点"永远不生效。
     * 改为用像素检测自己定位到的行做锚点自校准：
     * 只有检测连续成功多次、且行号稳定时才更新，避免被单帧噪声带偏。
     */
    private fun onLastDetection(barY: Int, trackLeft: Int, trackRight: Int) {
        if (barY <= 0) return
        lastBarY = barY
        lastTrackLeft = trackLeft
        lastTrackRight = trackRight
        // 用播放态测到的干净轨道端点校准轨道比例（供暂停路径换算进度用）。
        // 播放态的轨道是横贯整屏的实心条，端点最可靠；暂停态会被视频内容打断。
        if (trackLeft >= 0 && trackRight > trackLeft) {
            val (sw, sh) = screenSize()
            if (sw > 0 && sh > 0) {
                ProgressDetector.calibrateTrack(trackLeft, trackRight, sw, sh)
            }
        }
        // ---- 关键：兜底候选不得参与锚点自校准 ----
        //
        // `findBarRow` 在锚点附近找不到候选时，会退回"覆盖度最高的行"。
        // 那很可能是视频画面内容（高光/字幕），不是进度条。
        // 实测踩过：锚点被这种兜底行污染成 y=2139（真实进度条在 2262），
        // 此后每帧都在读视频画面 —— 表现为"进度数字乱跳"，
        // 且永远等不到播完，只能靠停滞超时划走。
        //
        // 因此只接受**来自锚点附近**的可信行来校准。
        if (!ProgressDetector.wasLastRowTrusted()) {
            Log.w(TAG, "检测行 y=$barY 来自兜底候选，不用于锚点校准")
            anchorCandidate = -1
            anchorStableCount = 0
            return
        }
        if (barY == anchorCandidate) {
            anchorStableCount++
            if (anchorStableCount == ANCHOR_CONFIRM) {
                val screenH = screenHeightPx()
                if (screenH > 0) {
                    // ---- 防渐进漂移 ----
                    //
                    // 实测踩过：锚点从 2262 一路漂到 2139
                    // （2262 → 2203 → 2193 → 2140 → 2139，累计 123px）。
                    // 每步位移都小于 ANCHOR_TOLERANCE(60)，所以"可信"检查
                    // 全部放行，但最终落进了视频画面里 —— 表现为进度数字乱跳、
                    // 且读不到高进度只能靠停滞超时划走。
                    //
                    // 因此校准必须满足两个条件：
                    // 1. 距**初始/上次可信基准**不能漂太远（累计约束）
                    // 2. 该行必须是"横贯整屏"的进度条形态（覆盖率足够高）
                    //    视频内容的高光/字幕很少横贯 90% 以上宽度
                    val drift = if (anchorBaselineY > 0) {
                        kotlin.math.abs(barY - anchorBaselineY)
                    } else {
                        0
                    }
                    val tooFar = anchorBaselineY > 0 && drift > ANCHOR_MAX_DRIFT
                    val coverage = ProgressDetector.lastCoverage()
                    val tooNarrow = coverage < ANCHOR_MIN_COVERAGE
                    if (tooFar || tooNarrow) {
                        Log.w(
                            TAG,
                            "拒绝锚点校准 y=$barY：漂移=${drift}px(上限$ANCHOR_MAX_DRIFT) " +
                                "覆盖率=${"%.2f".format(coverage)}(下限$ANCHOR_MIN_COVERAGE) " +
                                "基准=$anchorBaselineY",
                        )
                        anchorCandidate = -1
                        anchorStableCount = 0
                        return
                    }
                    ProgressDetector.setBarAnchor(barY, screenH)
                    // 首次校准确立基准；后续只允许小幅微调
                    if (anchorBaselineY <= 0) anchorBaselineY = barY
                    Log.i(
                        TAG,
                        "像素锚点自校准: y=$barY / $screenH " +
                            "(连续 $anchorStableCount 次一致, 漂移=${drift}px)",
                    )
                }
            }
        } else {
            anchorCandidate = barY
            anchorStableCount = 1
        }
    }

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        job = scope.launch(Dispatchers.Default) { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        onStateChange("已停止")
        Log.i(TAG, "任务已停止")
    }

    private suspend fun runLoop() {
        onStateChange("运行中")
        Log.i(TAG, "任务开始")

        // 无障碍服务必须先连上，否则后面的一切都会失败：
        // 窗口列表为空 → isKuaishouForeground/isHomeFeed 全判 false →
        // 对齐直接超时退出。实测踩过：应用进程刚被重启（如安装后）时点开始，
        // 服务还在绑定中，任务立刻就"启动对齐失败，任务结束"。
        val ready = waitForAccessibility()
        if (!ready) {
            onStateChange("无障碍服务未连接，请重新开启")
            Log.e(TAG, "等待无障碍服务超时，任务不启动")
            return
        }

        // 启动对齐：先把快手拉到前台并确认落在首页，再开始检测
        val aligned = alignToHomeFeed()
        if (!aligned) {
            onStateChange("无法进入快手首页")
            Log.w(TAG, "启动对齐失败，任务结束")
            return
        }

        // 关键：必须等本应用自己退到后台再开始抓帧。
        // 实测在主界面还可见时抓到的画面就是本应用的白色界面，
        // 那会让像素检测读到强度 200+ 的假"进度条"，把判定带偏。
        val leftSelf = waitSelfToBackground()
        if (!leftSelf) {
            Log.w(TAG, "本应用未退到后台，仍继续（结果可能不可靠）")
        }

        var pageStartAt = System.currentTimeMillis()
        var noBarCount = 0
        var lastPageType: PageDetector.PageType? = null
        var cycleMax = 0f
        var nearDoneConfirm = 0
        var diagnosed = false
        var diagAttempts = 0
        var pollCount = 0
        var panelRecoverFail = 0
        var popupRecoverFail = 0
        var liveStreak = 0
        var gestureFailStreak = 0
        var liveExitFail = 0
        var offFeedFail = 0
        var offTargetCount = 0
        var idProbeDone = false
        var textProbeDone = false
        /** 距上次滑动的帧数，用于取证"换页后首帧"的读数（见调试日志） */
        var swipesSinceLastDump = 0
        /** 上一次看到的视频标识，用于判断"画面是否已换成新视频" */
        var lastVideoIdentity = ""

        /**
         * 是否正在等待"画面换成新视频"。
         *
         * 上滑之后置真，直到节点树里的视频标识**确实变化**（或超时）为止。
         * 期间读到的进度一律丢弃——那些帧属于上一个视频，会把新页面的
         * 峰值污染成 99%，进而立刻又触发一次上滑（实测的连滑根因）。
         */
        var awaitingFreshVideo = false

        /** 发起"等待新视频"的时刻，用于超时兜底 */
        var freshVideoWaitStart = 0L

        /** 换页确认后的丢帧截止时刻，等视频层真正切新（见 FRESH_VIDEO_SETTLE_MS） */
        var freshVideoSettleUntil = 0L

        /** 触发上滑时的视频标识，用于比较"是否真的换了视频" */
        var identityAtSwipe = ""

        /** 上次因"直播卡片"而划走的时刻，用于识别残留节点的重复命中 */
        var lastLiveCardSwipeAt = 0L

        /** 换页后已过了几帧，用于逐帧诊断"新片段开头的进度跳变" */
        var postSwipeFrames = Int.MAX_VALUE

        /**
         * "离开信息流→按返回"连续发生的次数。
         *
         * 每次成功回到信息流就清零；超过 [MAX_OFF_FEED_RECOVER] 判定为死循环。
         */
        var offFeedRecoverStreak = 0

        /**
         * 本页无进度条时的随机停留目标（毫秒）。
         *
         * 由 resetPage 每页抽一次，整页保持不变；
         * 范围 NO_BAR_WAIT_MIN_MS..NO_BAR_WAIT_MAX_MS（最长 2 分钟）。
         */
        var noBarTargetMs = NO_BAR_WAIT_MIN_MS

        /**
         * 本页的停留上限（毫秒），由 resetPage 每页抽一次。
         *
         * 基准 = 设置页的分钟数；实际值再叠加 ±[AppSettings.WATCH_JITTER_RATIO]
         * 的抖动，因此**不会刚好是整 5 分钟**（用户明确要求）。
         * 同理整页保持不变：若每帧重抽，判定会一直在抖，永远到不了。
         */
        var pageWatchLimitMs = 0L

        // ---- 多信号投票用的状态（第三项）----
        // 进度推进观察：上次确认推进时的进度水位与时间
        var advanceRefProgress = 0f
        var lastAdvanceAt = System.currentTimeMillis()
        // 换页事件信号：无障碍服务在视频切换时会收到窗口内容变化，
        // 配合"进度回退"一起投票，比单看像素更稳
        var lastContentChangeAt = 0L
        var lastContentSeq = -1L
        var lastAlignAt = 0L

        fun resetPage(pageType: PageDetector.PageType?) {
            lastPageType = pageType
            noBarCount = 0
            cycleMax = 0f
            nearDoneConfirm = 0
            pageStartAt = System.currentTimeMillis()
            // 无进度条页面的随机停留目标：**整页保持不变**。
            // 若每帧重抽，等待时长会在 8s~120s 间抖动，永远等不到目标值。
            noBarTargetMs = Random.nextLong(
                NO_BAR_WAIT_MIN_MS, NO_BAR_WAIT_MAX_MS + 1,
            )
            // 本页停留上限：基准来自设置页，再叠加 ±20% 抖动。
            // 用户要求"不要刚刚好 5 分钟"，因此每页的实际上限会在
            // 例如 4~6 分钟之间浮动，而不是固定 5 分钟。
            val baseMs = maxWatchMinutes.coerceAtLeast(1) * 60_000L
            val jitter = (baseMs * AppSettings.WATCH_JITTER_RATIO).toLong()
            pageWatchLimitMs = baseMs + Random.nextLong(-jitter, jitter + 1)
            // 进度推进观察一并重置，否则新页面会误用上一页的基准
            advanceRefProgress = 0f
            lastAdvanceAt = pageStartAt
            lastContentSeq = -1L
        }

        /**
         * 派发一次上滑，并**按结果无条件重置本页观察状态**。
         *
         * 关键：手势失败也要重置。
         * 触发条件里有"本页停留超过 [STALL_TIMEOUT_MS] / 设置页的停留上限"
         * 这类有状态判据；
         * 只在成功时重置，会让失败那一轮继续满足触发条件，于是每一轮
         * （约 0.4 秒）都派发一次手势，而手势本身的时长就有 0.36~0.5 秒，
         * 派发时上一个还没结束 → 被系统取消 → 返回 false → 条件依旧成立，
         * 形成无限上滑风暴。实测踩过：15 秒内连派 20+ 次上滑。
         * 失败时一并重置，等于给本页重新计时，最多再等一个周期。
         *
         * @return 手势是否成功完成
         */
        suspend fun swipeAndReset(
            service: AutoScrollService,
            pageType: PageDetector.PageType?,
            reason: String,
        ): Boolean {
            val ok = swipe(service)
            if (!ok) {
                gestureFailStreak++
                Log.w(TAG, "上滑手势未完成（$reason），本页重新计时（连续 $gestureFailStreak 次）")
            } else {
                gestureFailStreak = 0
            }
            // 标记"刚换页"，供取证日志识别换页后首帧
            swipesSinceLastDump = 1
            postSwipeFrames = 0
            // 立刻把悬浮窗的进度读数清掉。
            //
            // 实测踩过：`onProgress` 原本只在每 3 次轮询时上报，滑动时**不重置**，
            // 于是划走后悬浮窗一直挂着上一段的 96%，直到新读数通过帧校验闸门
            // （最长 4 秒多）才更新。用户看到的就是
            // "标题都变了，进度还不会立即重置" —— 这不合理，属于显示缺陷。
            // 换页那一刻就应清空，让界面与实际状态一致。
            onProgress?.invoke(null)
            // 关键：进入"等待新视频"状态。
            // 上滑后头几帧仍是旧视频的残留画面，其进度读数必须丢弃，
            // 否则会把新页面的峰值直接污染成 99%（实测到的连滑根因）。
            // 记下当前标识，等它变化才认为画面真的换了。
            identityAtSwipe = lastVideoIdentity
            awaitingFreshVideo = true
            freshVideoWaitStart = System.currentTimeMillis()
            resetPage(pageType)
            delay(AFTER_SWIPE_DELAY_MS)
            return ok
        }

        /**
         * 手势连续派发失败时是否应当中止任务。
         *
         * 实测：无障碍服务崩溃后，系统会把它记进 "Crashed services" 并重新绑定，
         * 此时 `onServiceConnected` 照常回调（所以 [AutoScrollService.isConnected] 为 true）、
         * `dumpsys accessibility` 也报 `capabilities=33`，但**唯独缺了
         * CAN_PERFORM_GESTURES(16)**，于是 `dispatchGesture` 一律返回 false：
         * 每 60 秒派发一次、每次都失败，任务表面在跑却一步都不动（实测踩过）。
         *
         * 这个状态应用自己无法修复（重新绑定无障碍只能由用户或系统设置操作），
         * 因此连续失败到阈值就停下，并给出可执行的提示，避免无限空转。
         *
         * @return true 表示应当中止
         */
        fun shouldAbortOnGestureFailure(): Boolean =
            gestureFailStreak >= GESTURE_FAIL_ABORT

        while (job?.isActive == true) {
            val service = AutoScrollService.instance
            if (service == null) {
                onStateChange("等待无障碍服务")
                delay(POLL_INTERVAL_MS * 3)
                continue
            }

            // 手势已经连续失败到阈值：说明无障碍服务虽然"连着"但已丧失
            // 手势能力（实测 capabilities 缺 CAN_PERFORM_GESTURES），
            // 继续跑只会无限空转，停下来给出可执行的提示。
            if (shouldAbortOnGestureFailure()) {
                onStateChange("无障碍服务已失效，请到系统设置重新开启")
                Log.e(TAG, "手势连续 $gestureFailStreak 次失败，判定无障碍服务失效，任务中止")
                return
            }

            // 服务被系统断开后 instance 仍非空，但手势会一直静默失败。
            // 实测踩过：连续 61 次上滑全部被取消，任务表面在跑却毫无动作。
            // 这里主动识别并停下，给出可操作的提示。
            if (!service.isConnected()) {
                onStateChange("无障碍服务已断开，请重新开启")
                Log.e(TAG, "无障碍服务已断开，任务中止")
                return
            }

            // ---- 安全闸门：前台不是快手就绝不派发任何手势 ----
            //
            // 实测事故：用户切到**天气应用**后，任务仍在对天气界面读
            // "进度=19%" 并持续上滑，造成在第三方应用里乱点。
            // 根因是 `currentPackage` / `currentWindowClass` 只在快手的
            // 无障碍事件里更新（非快手事件被直接 return），离开快手后它们
            // **永远停在快手**，于是页面判定一直认为"还在快手首页"。
            //
            // 这里用独立的"当前前台应用"判据做最后一道闸门：
            // 只要明确不是快手（或读不到前台信息），就立即停手，
            // 并尝试把快手拉回前台继续任务。
            if (!service.isTargetForegroundStrict() && !skipForegroundCheckForTest) {
                val fg = service.currentForegroundPackage() ?: "未知"
                // 先确保没有任何手势在飞：这一步是止血，不能省
                offTargetCount++
                Log.w(
                    TAG,
                    "前台不是快手（当前=$fg），已停止手势派发 (第 $offTargetCount 次)",
                )
                onStateChange("已离开快手，暂停操作")

                if (offTargetCount >= OFF_TARGET_REALIGN_AFTER) {
                    offTargetCount = 0
                    Log.i(TAG, "离开快手累计 $OFF_TARGET_REALIGN_AFTER 次，重新拉回快手")
                    // 不盲按返回键（可能在别人的应用里），直接拉起快手
                    service.launchKuaishou()
                    delay(LAUNCH_WAIT_MS)
                }
                resetPage(null)
                delay(POLL_INTERVAL_MS * 2)
                continue
            }
            offTargetCount = 0

            val root = kuaishouRoot(service)


            // 诊断（--es dbg 1）：把页面类型与节点 id 打进日志。
            // 只在调试时开启：id 列表很长，轮询下会淹没日志。
            if (debugTexts) {
                Log.i(
                    TAG,
                    "页面诊断: type=${detectPageType(service)} " +
                        "class=${AutoScrollService.currentWindowClass} " +
                        "card=${UiStateProbe.isLiveCard(root)} " +
                        "seq=${AutoScrollService.contentChangeSeq} " +
                        "ids=[${UiStateProbe.dumpIds(root, 12)}]",
                )
                // 一次性探测：节点树里有哪些**文本**可用作"视频唯一标识"。
                // 目的：换页后若标识没变，说明读到的还是上一个视频的残留帧，
                // 据此丢弃该读数，避免"用旧视频的 99% 把新视频立刻划走"。
                if (!textProbeDone) {
                    textProbeDone = true
                    Log.i(TAG, "视频标识(原始): " + UiStateProbe.dumpTexts(root, "@", 6))
                }
                // 一次性验证：系统侧 View ID 索引能不能直接拿到进度条节点。
                // 若能拿到 rangeInfo，整个像素方案即可退役。
                if (!idProbeDone) {
                    idProbeDone = true
                    Log.i(TAG, "ViewID探测: " + SeekBarProbe.probeByViewId(root))
                }
            }

            // 评论面板会盖住整个视频区，此时任何上滑都不会翻页。
            // 实测按一次返回即可收起，这里做自动恢复。
            if (UiStateProbe.isCommentPanelOpen(root)) {
                panelRecoverFail++
                if (panelRecoverFail > MAX_PANEL_RECOVER_FAIL) {
                    onStateChange("评论面板无法关闭")
                    Log.w(TAG, "连续 $panelRecoverFail 次未能关闭评论面板，停止任务")
                    return
                }
                Log.w(TAG, "检测到评论面板，按返回键收起 (第 $panelRecoverFail 次)")
                onStateChange("收起评论面板")
                var closed = false
                for (attempt in 1..MAX_BACK_RETRIES) {
                    service.pressBack()
                    delay(AFTER_BACK_DELAY_MS)
                    val r2 = kuaishouRoot(service)
                    if (!UiStateProbe.isCommentPanelOpen(r2)) {
                        closed = true
                        Log.i(TAG, "评论面板已收起 (返回键第 $attempt 次)")
                        break
                    }
                }
                if (!closed) {
                    Log.w(TAG, "返回键未能收起评论面板")
                } else {
                    panelRecoverFail = 0
                    resetPage(lastPageType)
                }
                delay(POLL_INTERVAL_MS)
                continue
            }
            panelRecoverFail = 0

            // ---- 通用容错：确认"是否真的还在上滑信息流里" ----
            //
            // 原则（用户要求）：不看"是什么页面"，而看**是否满足继续刷的条件**：
            // 前台是快手 + 有首页标志 + 没有已知遮挡物。
            // 任一不满足 → 走"返回键一次 + 重新判定"，连续失败再重新对齐首页。
            //
            // 实测踩过：误入 `PhotoDetailActivity`（图文详情）后，
            // Activity 事件缓存仍停留在 HomeActivity，页面被判成 HOME_FEED，
            // 于是任务对着一个**静止图文页**读了 3 分钟"进度条"
            // （恒定的 `强度=255.0 进度=54%`，其实是静态图片被当成进度条），
            // 上滑也永远翻不了页——因为没有这个检查。
            //
            // 注意这里**不依赖 pageType**（那是后面才算的，还依赖会被缓存污染的
            // Activity 名）；判据完全来自节点树，是最可靠的一手信息。
            // 另外只在"快手窗口可读"时才判定，避免窗口信息短暂为空时误按返回键。
            if (root != null && !PageDetector.hasHomeMarker(root)) {
                offFeedFail++
                // ---- 防止"退回信息流"自我重置形成死循环 ----
                //
                // 实测事故：在图文页上，暂停探测点出了播放控制层（Dialog），
                // 于是 `hasHomeMarker` 失败 → 按返回键 → 页面变回图文页 →
                // 再按返回回首页 → 任务又做暂停探测 → 又弹出控制层…
                // 8 秒一轮，实测跑了 **135 轮**（日志里 136 次恒定 `50.0%`
                // 假读数，那是图文页的装饰性图形被当成进度条）。
                //
                // 当时的成因：`resetPage` 会清掉"别再暂停采样"的判断，
                // 于是回到首页又采样、又弹出控制层，循环永不自止。
                // 暂停采样现已整体移除（改为无进度条时随机等待再划走），
                // 该类循环的根因随之消失；这里保留的 [MAX_OFF_FEED_RECOVER]
                // 是独立的第二道保险：任何"反复离开信息流→按返回"都会停下。
                offFeedRecoverStreak++
                if (offFeedRecoverStreak > MAX_OFF_FEED_RECOVER) {
                    onStateChange("反复离开信息流，停止任务")
                    Log.e(
                        TAG,
                        "连续 $offFeedRecoverStreak 次『离开信息流→按返回』未能稳定，" +
                            "判定为死循环，任务中止",
                    )
                    return
                }
                if (offFeedFail > MAX_OFF_FEED_FAIL) {
                    onStateChange("无法回到信息流")
                    Log.e(TAG, "连续 $offFeedFail 次未能回到信息流，任务中止")
                    return
                }
                Log.w(
                    TAG,
                    "当前不满足'在上滑信息流里'（缺首页标志 id，" +
                        "class=${AutoScrollService.currentWindowClass} " +
                        "isDetail=${PageDetector.isDetailPage(AutoScrollService.currentWindowClass)}），" +
                        "按返回键退回 (第 $offFeedFail 次, 离开信息流累计 $offFeedRecoverStreak 次)",
                )
                onStateChange("退回信息流")
                service.pressBack()
                delay(AFTER_BACK_DELAY_MS)
                if (PageDetector.hasHomeMarker(kuaishouRoot(service))) {
                    offFeedFail = 0
                    Log.i(TAG, "已回到上滑信息流")
                }
                // 只重置页面观察状态，**保留**探测相关标记：
                // 否则会把"本页不该暂停采样"的判断一起清掉，形成死循环。
                resetPage(null)
                continue
            }
            offFeedFail = 0
            offFeedRecoverStreak = 0

            // 电商/活动浮层同样会盖住视频区：进度条读不到、上滑也不翻页，
            // 任务会退化成反复空滑。实测按一次返回键即可关闭。
            val popupId = UiStateProbe.blockingPopupId(root)
            if (popupId != null) {
                popupRecoverFail++
                if (popupRecoverFail > MAX_POPUP_RECOVER_FAIL) {
                    onStateChange("弹层无法关闭")
                    Log.w(TAG, "连续 $popupRecoverFail 次未能关闭弹层($popupId)，停止任务")
                    return
                }
                Log.w(TAG, "检测到遮挡弹层 $popupId，按返回键关闭 (第 $popupRecoverFail 次)")
                onStateChange("关闭弹层")
                service.pressBack()
                delay(AFTER_BACK_DELAY_MS)
                if (UiStateProbe.blockingPopupId(kuaishouRoot(service)) == null) {
                    Log.i(TAG, "弹层已关闭")
                    popupRecoverFail = 0
                    resetPage(lastPageType)
                } else {
                    Log.w(TAG, "返回键未能关闭弹层")
                }
                delay(POLL_INTERVAL_MS)
                continue
            }
            popupRecoverFail = 0

            val pageType = detectPageType(service)

            // 用节点树量到的进度条容器底边校准像素检测的锚点，
            // 避免依赖硬编码的 y=2262（换分辨率会失效）。
            calibrateBarAnchor(root)

            // 诊断：持续探测直到找到进度条控件，用于确认无障碍可见性
            if (!diagnosed && pageType == PageDetector.PageType.HOME_FEED) {
                diagAttempts++
                val diagRoot = kuaishouRoot(service)
                val info = SeekBarProbe.probe(diagRoot)
                if (info.found) {
                    diagnosed = true
                    Log.i(TAG, "节点树诊断: ${SeekBarProbe.diagnose(diagRoot)}")
                    Log.i(
                        TAG,
                        "SeekBar 探测成功: current=${info.current} " +
                            "max=${info.max} progress=${info.progress} id=${info.nodeId}"
                    )
                } else if (diagAttempts % 20 == 1) {
                    Log.i(TAG, "节点树诊断(尝试$diagAttempts): ${SeekBarProbe.diagnose(diagRoot)}")
                }
            }

            // 页面切换时重置
            if (pageType != lastPageType) {
                Log.i(
                    TAG,
                    "页面切换: $lastPageType -> $pageType " +
                        "(windowClass=${AutoScrollService.currentWindowClass}, " +
                        "pkg=${AutoScrollService.currentPackage})"
                )
                resetPage(pageType)
            }

            val watchMs = System.currentTimeMillis() - pageStartAt
            val canSwipe = watchMs >= MIN_WATCH_MS

            when (pageType) {
                PageDetector.PageType.LIVE_ROOM -> {
                    onStateChange("直播间·上滑")
                    if (!canSwipe) {
                        delay(POLL_INTERVAL_MS)
                        continue
                    }
                    // 直播间是**独立的信息流**：在它里面上滑只会切下一个直播间，
                    // 永远回不到普通视频首页。实测连续上滑 35 次、手动连滑 6 次
                    // 都仍停在 LiveSlideActivity；而按一次返回键立即回到首页
                    // （HomeActivity）。因此连续滑走若干个直播后必须主动退出，
                    // 否则任务会一直困在直播流里。
                    if (liveStreak >= LIVE_STREAK_BEFORE_BACK) {
                        Log.i(
                            TAG,
                            "已连续滑走 $liveStreak 个直播间，按返回键退出直播流",
                        )
                        onStateChange("退出直播流")
                        // 实测：直播间**可能需要不止一次返回键**才退得出去
                        // （踩过：当前直播间按第一次无效、第二次才回到首页）。
                        // 因此这里不能"按一次就当退出成功"，必须每次复核页面类型，
                        // 未退出就继续按，直到回到首页或用尽次数。
                        var exited = false
                        for (attempt in 1..LIVE_EXIT_BACK_TRIES) {
                            service.pressBack()
                            delay(AFTER_BACK_DELAY_MS)
                            val nowType = detectPageType(service)
                            if (nowType != PageDetector.PageType.LIVE_ROOM) {
                                exited = true
                                Log.i(TAG, "已退出直播流（返回键第 $attempt 次 -> $nowType）")
                                break
                            }
                        }
                        if (!exited) {
                            Log.w(TAG, "返回键 $LIVE_EXIT_BACK_TRIES 次仍未退出直播流")
                            liveExitFail++
                            if (liveExitFail > MAX_LIVE_EXIT_FAIL) {
                                onStateChange("无法退出直播流")
                                Log.e(TAG, "连续 $liveExitFail 次退不出直播流，任务中止")
                                return
                            }
                        } else {
                            liveExitFail = 0
                        }
                        liveStreak = 0
                        resetPage(lastPageType)
                        continue
                    }
                    delay(LIVE_MIN_DELAY_MS)
                    Log.i(TAG, "检测到直播间，上滑")
                    liveStreak++
                    swipeAndReset(service, pageType, "直播间")
                }

                PageDetector.PageType.HOME_FEED -> {
                    // 回到普通视频流：直播连续计数清零
                    liveStreak = 0

                    // ---- 帧有效性闸门（本次修复的核心）----
                    //
                    // 根因（实测）：换页后的头几帧**仍在渲染上一个视频**
                    // （视图复用 + 视频层还没切新）。实测证据：每次上滑之后紧接着
                    // 读到的进度就是 `99% / 97%`——新视频不可能瞬间播到 99%。
                    //
                    // 后果：旧视频的 99% 被算到新视频头上 → 立刻又满足"播完" →
                    // 再滑一次 → 3.7 秒一次的连滑，进度显示彻底乱掉。
                    //
                    // 同一个坑也影响其它判据：实测"直播卡片"检测在换页后
                    // 连续命中 14 次（旧卡片的节点还在树里），于是成对连滑。
                    // 因此这道闸门必须放在**所有下游判定之前**。
                    //
                    // 判据：用节点树文本（作者/标题）算出的**视频标识**。
                    // 换页后若标识还没变，说明画面仍是旧视频，本帧一切判定都跳过。
                    val videoIdNow = UiStateProbe.videoIdentity(root)

                    if (awaitingFreshVideo) {
                        val changed = videoIdNow.isNotEmpty() && videoIdNow != identityAtSwipe
                        val waited = System.currentTimeMillis() - freshVideoWaitStart
                        when {
                            changed -> {
                                awaitingFreshVideo = false
                                Log.i(
                                    TAG,
                                    "已确认换页到新视频（等待 ${waited}ms）: " +
                                        "${identityAtSwipe.take(20)} -> ${videoIdNow.take(20)}",
                                )
                                // 换页瞬间同时记下锚点与轨道状态：
                                // "新片段开头进度跳变"要么来自锚点漂移，要么来自
                                // 轨道端点被视频内容截断；这两项是判断依据。
                                Log.i(
                                    TAG,
                                    "换页后检测器状态: " + ProgressDetector.describeAnchor(),
                                )
                                // 本页实际生效的两项随机时长（都整页不变）：
                                // 停留上限（设置值±20%）与无进度条等待（8s~2min）。
                                // 记出来便于核对"不是刚刚好 N 分钟"。
                                Log.i(
                                    TAG,
                                    "本页随机时长: 停留上限=${pageWatchLimitMs / 1000}s" +
                                        " (设置=${maxWatchMinutes}分钟," +
                                        " 抖动=${"%.0f".format(AppSettings.WATCH_JITTER_RATIO * 100)}%)" +
                                        " 无进度条等待=${noBarTargetMs / 1000}s",
                                )
                                // 关键补充：标识来自**节点树**，而进度来自**截图**，
                                // 两者更新不同步——节点树先变，视频层随后才切新。
                                // 实测证据：刚确认换页就读到 `0.0% -> 71.5%`（仍是旧帧），
                                // 并伴随 `无进度条 强度=0.0 行号=-1`（抓到全黑帧）。
                                // 因此设一个稳定期，让截图也切到新视频后再开始读数，
                                // 避免把旧视频的高进度算到新页面头上。
                                // （注意不能在这里 delay 后再 continue：那会立刻跳到
                                //   稳定期检查之外，等于没生效。只设截止时间即可。）
                                freshVideoSettleUntil =
                                    System.currentTimeMillis() + FRESH_VIDEO_SETTLE_MS
                            }

                            waited > FRESH_VIDEO_TIMEOUT_MS -> {
                                // 超时放弃等待：某些页面（广告/无文案）可能拿不到标识。
                                // 此时退回原有行为，宁可漏判也不能卡死。
                                awaitingFreshVideo = false
                                Log.w(
                                    TAG,
                                    "等待新视频标识超时（${waited}ms），" +
                                        "放弃帧校验，按原逻辑继续",
                                )
                            }

                            else -> {
                                // 画面仍是旧视频：跳过本帧的全部判定。
                                // 关键：不动 cycleMax，也不做卡片/无进度条判定，
                                // 否则旧页面的残留状态会立刻再触发一次滑动。
                                delay(POLL_INTERVAL_MS)
                                continue
                            }
                        }
                    }

                    // 换页后的稳定期：丢弃这几帧，等视频层真正切新
                    if (System.currentTimeMillis() < freshVideoSettleUntil) {
                        delay(POLL_INTERVAL_MS)
                        continue
                    }

                    if (videoIdNow != lastVideoIdentity) {
                        Log.i(
                            TAG,
                            "视频标识变化: ${lastVideoIdentity.take(24)} -> ${videoIdNow.take(24)}",
                        )
                        lastVideoIdentity = videoIdNow
                    }

                    // 直播卡片（画面上盖着「点击进入直播间」按钮）→ 立刻划走。
                    // 实测：这个卡片上的按钮就在视频中部，一旦被点到就会进入
                    // 直播间；用户要求"刷到就立马划走"。
                    // 注意不能在这里做暂停探测——探测点有落进该按钮的风险。
                    //
                    // 这里用**带节流的**判定：卡片节点的清除比画面切换稍慢，
                    // 刚划过卡片但仍未换页时会重复命中，故加最小间隔保护。
                    if (UiStateProbe.isLiveCard(root)) {
                        val sinceCard = System.currentTimeMillis() - lastLiveCardSwipeAt
                        if (sinceCard < LIVE_CARD_MIN_INTERVAL_MS) {
                            Log.w(
                                TAG,
                                "直播卡片判定在上次划走后 ${sinceCard}ms 内重复命中，" +
                                    "判定为残留节点，跳过",
                            )
                            delay(POLL_INTERVAL_MS)
                            continue
                        }
                        Log.i(TAG, "检测到直播卡片（点击进入直播间），立刻划走")
                        if (debugTexts) {
                            Log.i(
                                TAG,
                                "直播卡片命中节点: " + UiStateProbe.describeLiveCardHits(root),
                            )
                        }
                        onStateChange("直播卡片·划走")
                        lastLiveCardSwipeAt = System.currentTimeMillis()
                        swipeAndReset(service, pageType, "直播卡片")
                        continue
                    }

                    // 优先：直接从快手窗口的节点树读进度条数值
                    val kRoot = root ?: kuaishouRoot(service)
                    val seekInfo = SeekBarProbe.probe(kRoot)

                    if (seekInfo.progress != null) {
                        noBarCount = 0
                        val p = seekInfo.progress
                        // 与像素路径同一套峰值判据：不能用"连续两次回退"，
                        // 循环周期远大于轮询间隔，连击计数会被清零。
                        var loopDetected = false
                        if (p > cycleMax) cycleMax = p
                        if (cycleMax >= CYCLE_MIN_PEAK && (cycleMax - p) > LOOP_DROP) {
                            loopDetected = true
                            Log.i(
                                TAG,
                                "进度回退 ${"%.0f".format(cycleMax * 100)}% -> ${"%.0f".format(p * 100)}% " +
                                    "(峰值判据，来自节点树)"
                            )
                        }
                        if (p >= NEARLY_DONE) {
                            nearDoneConfirm++
                        } else {
                            nearDoneConfirm = 0
                        }

                        val doneHit = nearDoneConfirm >= NEAR_DONE_CONFIRM
                        if ((loopDetected || doneHit) && canSwipe) {
                            val reason = if (loopDetected) "播放完·上滑" else "即将播完·上滑"
                            Log.i(TAG, "触发滑动: $reason (来自节点树进度)")
                            onStateChange(reason)
                            swipeAndReset(service, pageType, reason)
                            continue
                        }
                        delay(POLL_INTERVAL_MS)
                        continue
                    }

                    // 兜底：截图像素分析
                    if (!capture.isReady) {
                        onStateChange("屏幕捕获未就绪")
                        Log.e(TAG, "屏幕捕获未就绪，任务中止")
                        delay(POLL_INTERVAL_MS * 4)
                        continue
                    }

                    val bmp = capture.capture()
                    if (bmp == null) {
                        delay(POLL_INTERVAL_MS)
                        continue
                    }
                    val result = ProgressDetector.detect(
                        bmp,
                        capture.contentTop,
                        capture.contentBottom,
                    )

                    if (!result.hasBar) {
                        noBarCount++
                        nearDoneConfirm = 0
                        // 本帧没有可读进度：把界面读数清掉，不要挂着上一次的旧值。
                        // 隐藏进度条的页面本来就读不到进度，清掉更符合实际状态。
                        if (postSwipeFrames <= VOTE_COOLDOWN_FRAMES) onProgress?.invoke(null)
                        // 注意：这里**不能**清 cycleMax。
                        // cycleMax 是"本页峰值"，是判定"播完回退"的唯一依据；
                        // 隐藏进度条的页面本来就读不到进度，清掉峰值会让
                        // 峰值恒为 0，loop 判定永远不成立（实测踩过）。
                        // 诊断：第 2 次失败时把当前帧存档，便于核对检测器看到的画面
                        if (noBarCount == 2) {
                            val p = capture.dumpFrame(bmp)
                            Log.i(TAG, "无进度条诊断帧已存档: $p (内容区${capture.contentTop}..${capture.contentBottom})")
                        }
                        bmp.recycle()
                        Log.i(
                            TAG,
                            "无进度条 #$noBarCount 强度=${"%.1f".format(result.strength)} " +
                                "行号=${result.barY} 占比=${"%.3f".format(result.litRatio)} " +
                                "剖面=${result.profile ?: "-"}"
                        )

                        // ---- 读不到进度条：随机等一段时间后划走 ----
                        //
                        // 用户要求：识别不到进度条的视频**不再点击暂停去确认**，
                        // 而是在 2 分钟内随机选一个时刻划过去。
                        //
                        // 为什么去掉暂停采样（实测教训）：
                        // - 暂停会打断播放；在图文/详情页还会点出播放控制层，
                        //   触发"缺首页标志→按返回→回首页→又采样"的 8 秒死循环
                        //   （实测跑了 135 轮，期间读到 136 次恒定的 50.0% 假读数）
                        // - 在另一台手机上暂停点击还可能误触底部导航的「去赚钱」
                        // - 这类页面本来就没有可读进度，暂停也拿不到可靠信息
                        //
                        // 改成"随机等一段"：上述副作用全部消失，且停留时长不固定，
                        // 更接近真人浏览。目标值在换页时抽定并整页保持不变
                        // （见 noBarTargetMs），避免每帧重抽导致等待时间抖动。
                        if (noBarCount >= NO_BAR_THRESHOLD && canSwipe &&
                            watchMs >= noBarTargetMs
                        ) {
                            Log.i(
                                TAG,
                                "无进度条：已停留 ${watchMs / 1000}s" +
                                    "（随机目标 ${noBarTargetMs / 1000}s，" +
                                    "连续 $noBarCount 次读不到），上滑",
                            )
                            onStateChange("无进度条·随机滑走")
                            swipeAndReset(service, pageType, "无进度条·随机")
                            continue
                        }
                        delay(POLL_INTERVAL_MS)
                        continue
                    }

                    bmp.recycle()
                    noBarCount = 0
                    val p = result.progress!!

                    // ---- 换页后的"高进度"不可信（拒绝残留帧）----
                    //
                    // 刚换过来的视频必定从 0% 附近开始播。若换页后立刻读到高进度，
                    // 那只能是**上一个视频的残留帧**（视频层尚未切新）。
                    // 实测证据：换页后第 1 帧读到 `80%`（强度 67.7，第 2 帧又掉到 29.0），
                    // 紧接着就被"多信号"判定划走 —— 用户看到的现象正是
                    // 「九十几就上滑，下一段开头进度数字跳变」。
                    //
                    // 这里直接丢弃这类读数：不更新 cycleMax、不参与任何判定。
                    // 只丢掉"高得不可能"的那些，低值（如 4%、9%）照常采信，
                    // 因此不会延迟正常起播的跟踪。
                    if (postSwipeFrames <= VOTE_COOLDOWN_FRAMES &&
                        p > POST_SWIPE_MAX_PLAUSIBLE
                    ) {
                        Log.w(
                            TAG,
                            "换页后第${postSwipeFrames}帧读到不可信的高进度 " +
                                "${"%.0f".format(p * 100)}%（强度=${"%.1f".format(result.strength)}），" +
                                "判为上一视频的残留帧，丢弃本帧",
                        )
                        if (postSwipeFrames <= POST_SWIPE_LOG_FRAMES) postSwipeFrames++
                        delay(POLL_INTERVAL_MS)
                        continue
                    }

                    // 取证（--es dbg 1）：记录"换页后前几帧"的读数与帧指纹。
                    //
                    // 目的：直接判定"换页后读到的高进度"是不是上一个视频的残留帧。
                    // 判据：滑动刚发生后（swipesSinceLastDump==1）如果能读到高进度，
                    // 说明这一帧内容属于**旧视频**——新视频不可能瞬间播到 90%+。
                    // 帧指纹用运动量：同一视频播放中运动量大，静止/残留帧明显不同。
                    if (debugTexts && swipesSinceLastDump == 1) {
                        Log.i(
                            TAG,
                            "换页后第1帧: 进度=${"%.0f".format(p * 100)}% " +
                                "行号=${result.barY} 强度=${"%.1f".format(result.strength)}",
                        )
                        swipesSinceLastDump = 2
                    }

                    // 先更新本页进度峰值，再打日志与判定，保证读到的峰值是最新的
                    if (p > cycleMax) cycleMax = p

                    // 上报给悬浮窗/主界面显示；节流到每 3 次一次，避免刷屏
                    if (pollCount % 3 == 0) onProgress?.invoke(p)
                    onLastDetection(result.barY, result.trackLeft, result.trackRight)

                    pollCount++
                    // 换页后逐帧记录（诊断"新片段开头进度跳变"）：
                    // 平时每 6 次记一条，但换页后前 16 帧必须逐帧记，
                    // 否则看不清跳变的确切形态。
                    val logThisFrame = pollCount % 6 == 1 || postSwipeFrames <= POST_SWIPE_LOG_FRAMES
                    if (logThisFrame) {
                        Log.i(
                            TAG,
                            "进度=${"%.0f".format(p * 100)}% 峰值=${"%.0f".format(cycleMax * 100)}% " +
                                "强度=${"%.1f".format(result.strength)} 行号=${result.barY}" +
                                // 覆盖率用于判定读数是否来自锚点附近的真实轨道：
                                // 真实进度条横贯整屏（0.92~0.95）。
                                // 曾观察到 行号=2213 强度=208（偏锚点 49px 但在容差内），
                                // 带上覆盖率即可直接判断那是否是画面内容。
                                " 覆盖=${"%.2f".format(ProgressDetector.lastCoverage())}" +
                                if (postSwipeFrames <= POST_SWIPE_LOG_FRAMES) {
                                    " [换页后第${postSwipeFrames}帧]"
                                } else {
                                    ""
                                },
                        )
                    }
                    if (postSwipeFrames <= POST_SWIPE_LOG_FRAMES) postSwipeFrames++
                    // 判定 1：进度回退（视频循环重播）
                    //
                    // 这里不能要求"连续两次回退"：实测视频循环周期 9~12 秒，
                    // 而轮询间隔约 0.4 秒，回退之后的下一帧进度就已经在往上走了，
                    // 连击计数会被立刻清零，导致永远判不出"播完"，
                    // 只能靠 60 秒超时兜底（这就是"进度识别不准"的直接原因）。
                    //
                    // 改为记录本页进度峰值：只要曾经播到较高位置、随后大幅回落，
                    // 就是一次循环重播。单帧即可判定，无需连击。
                    var loopDetected = false
                    if (cycleMax >= CYCLE_MIN_PEAK && (cycleMax - p) > LOOP_DROP) {
                        loopDetected = true
                        Log.i(
                            TAG,
                            "进度回退 ${"%.0f".format(cycleMax * 100)}% -> ${"%.0f".format(p * 100)}% " +
                                "(峰值判据，差值 ${"%.0f".format((cycleMax - p) * 100)}%)"
                        )
                    } else if (p <= LOOP_RESTART_MAX && cycleMax >= LOOP_RESTART_MIN_PEAK) {
                        // 短视频重播：读数回到起播附近且此前确实涨过。
                        // 与探测路径同一套补充判据——覆盖"峰值未达 CYCLE_MIN_PEAK
                        // 就重播"的内容，否则只能干等到停滞超时（实测漏判过 29%→3%）。
                        loopDetected = true
                        Log.i(
                            TAG,
                            "进度回到起播 ${"%.0f".format(cycleMax * 100)}% -> " +
                                "${"%.0f".format(p * 100)}%，判为短视频重播",
                        )
                    }

                    // 判定 2：接近播完 —— 需连续确认，避免噪声
                    if (p >= NEARLY_DONE) {
                        nearDoneConfirm++
                    } else {
                        nearDoneConfirm = 0
                    }

                    // ---- 判定 3：进度是否还在推进（多信号投票的基础信号）----
                    //
                    // 这是修复"没播完就滑走"的核心：原实现用无条件 60 秒超时，
                    // 实测 5 次滑动里 4 次是它触发、滑走时进度只有 13%~62%。
                    // 现在改为观察"进度有没有在涨"：只要还在涨就继续看，
                    // 只有确实涨不动了才认为本页不会再播。
                    //
                    // 实现要点：用**累积基准**而不是固定时间窗比较。
                    // cycleMax 单调递增，所以"距上次确认推进又涨了 ADVANCE_MIN_DELTA"
                    // 就刷新停滞计时。长视频涨得慢也没问题——它会累积到阈值后
                    // 自然刷新，不会像固定窗口那样因单窗涨幅不足而误判停滞。
                    val nowMs = System.currentTimeMillis()
                    if (cycleMax - advanceRefProgress >= ADVANCE_MIN_DELTA) {
                        val from = advanceRefProgress
                        advanceRefProgress = cycleMax
                        lastAdvanceAt = nowMs
                        if (debugTexts) {
                            Log.i(
                                TAG,
                                "进度推进确认: ${"%.1f".format(from * 100)}% -> " +
                                    "${"%.1f".format(cycleMax * 100)}%",
                            )
                        }
                    }

                    val stallMs = nowMs - lastAdvanceAt

                    val doneHit = nearDoneConfirm >= NEAR_DONE_CONFIRM

                    // 停滞超时：**只有确实不再推进才用**。
                    // 不再像原实现那样"到点就滑"，因此长视频不会被腰斩。
                    val stallHit = stallMs > STALL_TIMEOUT_MS

                    // 停留上限：本页实际限制 = 设置值 ±20% 抖动（见 resetPage）。
                    // 到点即上滑，防止"进度一直缓慢推进但永不结束"的页面死等。
                    val limitMs = if (pageWatchLimitMs > 0) {
                        pageWatchLimitMs
                    } else {
                        maxWatchMinutes.coerceAtLeast(1) * 60_000L
                    }
                    val absoluteHit = watchMs > limitMs

                    // ---- 多信号投票 ----
                    //
                    // 目标：让"播完"的判定不只依赖某一路信号。
                    // 像素路径（截图分析）脆弱——遇到遮挡、暗色内容、进度条不画
                    // 就可能读错；而窗口内容变化事件走的是系统无障碍事件通道，
                    // 原理完全不同，可作为独立旁证。
                    //
                    // 先刷新"内容变化"的新鲜度：只要服务的计数器变了，
                    // 就记下当前时刻。
                    val seqNow = AutoScrollService.contentChangeSeq
                    if (seqNow != lastContentSeq) {
                        val gap = if (lastContentChangeAt > 0) nowMs - lastContentChangeAt else -1
                        lastContentSeq = seqNow
                        lastContentChangeAt = nowMs
                        // 诊断用途：量化"内容变化"的发生频率。
                        // 若它过于频繁（间隔远小于 CONTENT_CHANGE_FRESH_MS），
                        // 这一票就会几乎恒为真、失去独立性，需据此调整窗口。
                        if (debugTexts) {
                            Log.i(TAG, "内容变化信号: seq=$seqNow 间隔=${gap}ms")
                        }
                    }
                    val pageChangedRecently =
                        nowMs - lastContentChangeAt in 1..CONTENT_CHANGE_FRESH_MS

                    // 权重设计（阈值 2 票）：
                    // - 进度回退 = 2 票（强）：从真实峰值大幅回落，几乎只可能是重播
                    // - 接近播完 = 1 票：连续多次读到 >= NEARLY_DONE
                    // - 内容刚变化 = 1 票（独立通道）
                    // - 内容刚变化 + 中等回退 = 1 票（补充）
                    //
                    // **重要限制**：本页刚换过来时（postSwipeFrames 很小），
                    // 内容变化这一票必须作废。原因（实测踩过）：
                    // 换页闸门确认新视频时本身就会更新 lastContentChangeAt，
                    // 于是"刚换页"这件事被当成了"本页播完"的证据；
                    // 再配上残留帧的假高进度回退，两票凑齐就立刻划走 ——
                    // 表现为"九十几就上滑，下一段开头进度数字跳变"。
                    // 换页后的一小段窗口内，只认"进度回退"这种强信号。
                    val inPostSwipeWindow = postSwipeFrames <= VOTE_COOLDOWN_FRAMES

                    var votes = 0
                    if (loopDetected) votes += 2
                    if (doneHit && !inPostSwipeWindow) votes++
                    if (pageChangedRecently && !inPostSwipeWindow) votes++

                    // 补充票：内容刚变化，且进度出现了"中等幅度"回退
                    // （幅度未达强判据的 LOOP_DROP）。仍要求本页曾达到
                    // CYCLE_MIN_PEAK，避免把起播阶段的低进度噪声算进来。
                    if (pageChangedRecently &&
                        !inPostSwipeWindow &&
                        cycleMax >= CYCLE_MIN_PEAK &&
                        cycleMax - p > LOOP_DROP * 0.5f
                    ) {
                        votes++
                    }

                    val votedDone = votes >= 2 && canSwipe

                    if ((loopDetected || doneHit || stallHit || absoluteHit || votedDone) && canSwipe) {
                        val reason = when {
                            loopDetected -> "播放完·上滑"
                            doneHit -> "即将播完·上滑"
                            absoluteHit -> "到达页面上限·上滑"
                            stallHit -> "进度停滞·上滑"
                            else -> "多信号·上滑"
                        }
                        Log.i(
                            TAG,
                            "触发滑动: $reason (峰值=${"%.0f".format(cycleMax * 100)}% " +
                                "当前=${"%.0f".format(p * 100)}% 停滞=${stallMs}ms 票数=$votes)",
                        )
                        onStateChange(reason)
                        swipeAndReset(service, pageType, reason)
                        continue
                    }

                    delay(POLL_INTERVAL_MS)
                }

                PageDetector.PageType.KUAISHOU_OTHER,
                PageDetector.PageType.UNKNOWN -> {
                    // 曾经是"永远等待首页"的死等逻辑；现在改为主动重新对齐，
                    // 比如用户切到了别的应用，任务能自己回到快手继续跑。
                    // 加节流：避免每轮都强行拉起快手，把用户从别的应用里拽出来。
                    val now = System.currentTimeMillis()
                    if (now - lastAlignAt >= REALIGN_THROTTLE_MS) {
                        lastAlignAt = now
                        onStateChange("重新定位首页")
                        Log.i(TAG, "页面类型=$pageType，尝试重新对齐到首页")
                        if (alignToHomeFeed()) {
                            resetPage(null)
                            continue
                        }
                    }
                    delay(POLL_INTERVAL_MS * 2)
                }
            }
        }

        Log.i(TAG, "任务循环结束")
    }

    /**
     * 等无障碍服务真正连上。
     *
     * 实测：应用进程刚被重启（安装新版本后、被系统回收后）时点开始，
     * `AutoScrollService.instance` 已经是非空对象但 `connected == false`，
     * 此时窗口列表为空，`isKuaishouForeground()` / `isHomeFeed()` 全判 false，
     * 对齐阶段会立刻"等待快手前台超时 → 启动对齐失败，任务结束"。
     * 等一会儿服务就绑好了，因此这里轮询等待而不是直接判失败。
     *
     * @return true 表示服务已连接
     */
    private suspend fun waitForAccessibility(): Boolean {
        val deadline = System.currentTimeMillis() + AX_READY_WAIT_MS
        var connectedSeen = false
        while (job?.isActive == true) {
            val svc = AutoScrollService.instance
            if (svc != null && svc.isConnected()) {
                connectedSeen = true
                // 光"已连接"还不够。实测踩过：应用刚被重装/进程刚重启时，
                // `onServiceConnected` 已经回调、`isConnected()` 为 true，
                // 但**窗口列表暂时还是空的**（`windows=0`），
                // 于是 `isKuaishouForeground()` 判 false →
                // 对齐阶段立刻"等待快手前台超时 → 启动对齐失败，任务结束"。
                // 因此这里要求"至少能读到窗口信息"才算真的就绪。
                if (svc.hasWindowInfo()) {
                    Log.i(TAG, "无障碍服务已就绪（窗口信息可用）")
                    return true
                }
                if (System.currentTimeMillis() >= deadline) {
                    // 一直读不到窗口：仍放行，让对齐阶段自己按"读不到窗口"
                    // 的逻辑等待，而不是在这里直接判失败。
                    Log.w(TAG, "无障碍已连接但窗口信息始终为空，继续尝试对齐")
                    return true
                }
            }
            if (System.currentTimeMillis() >= deadline) break
            onStateChange("等待无障碍服务")
            delay(AX_READY_POLL_MS)
        }
        if (!connectedSeen) return false
        return false
    }

    /**
     * 启动对齐：确保快手在前台且停在首页信息流。
     *
     * 之前版本在非快手页面只是空等（"等待首页"），永远不会主动切过去，
     * 导致用户必须先手动打开快手才能用。这里补上主动拉起与定位。
     *
     * @return true 表示已就位
     */
    private suspend fun alignToHomeFeed(): Boolean {
        val service = AutoScrollService.instance ?: return false

        // 诊断：确认无障碍窗口列表是否可用（实测曾出现列表为空导致对齐失败）
        val winCount = try {
            service.windows.size
        } catch (t: Throwable) {
            -1
        }
        val rootPkg = try {
            service.rootInActiveWindow?.packageName?.toString()
        } catch (t: Throwable) {
            "ERR"
        }
        val ksFg = try {
            service.isKuaishouForeground()
        } catch (t: Throwable) {
            null
        }
        Log.i(
            TAG,
            "align 诊断: windows=$winCount curPkg=${AutoScrollService.currentPackage} " +
                "curCls=${AutoScrollService.currentWindowClass} rootPkg=$rootPkg " +
                "isKsForeground=$ksFg isHomeFeed=${service.isHomeFeed()}"
        )

        // 1) 快手不在前台 → 主动拉起
        if (!service.isKuaishouForeground()) {
            onStateChange("正在打开快手")
            val launched = service.launchKuaishou()
            Log.i(TAG, "拉起快手: launched=$launched")
            if (!launched) return false

            val deadline = System.currentTimeMillis() + LAUNCH_WAIT_MS
            var foreground = false
            while (System.currentTimeMillis() < deadline && job?.isActive == true) {
                delay(400)
                if (service.isKuaishouForeground()) {
                    foreground = true
                    break
                }
            }
            if (!foreground) {
                // 读不到窗口信息时不能断定"快手没起来"——实测服务刚连上时
                // `windows` 会短暂为空，此时误判会直接终止任务。
                // 因此只要窗口信息还不可用，就当作"可能已在前台"继续往下走，
                // 后续的首页判定与每轮重检测会自己收敛。
                if (!service.hasWindowInfo()) {
                    Log.w(TAG, "窗口信息不可用，无法确认快手前台；继续尝试对齐")
                } else {
                    Log.w(TAG, "等待快手前台超时")
                    return false
                }
            }
        }

        // 2) 已在快手但不一定是首页（可能在个人页/其他二级页）→ 返回键退回
        //
        // 关键约束：只有"确实读到了快手窗口、但里面没有首页标志"时才按返回键。
        // 无障碍窗口列表在服务刚连上/进程重启后会短暂为空，这时按返回键会
        // 把用户从首页一路退出去（实测出现过连按 3 次返回仍"无法确认首页"）。
        var backTries = 0
        var waitTries = 0
        while (job?.isActive == true && backTries < MAX_BACK_RETRIES) {
            if (service.isHomeFeed()) {
                Log.i(TAG, "已定位到快手首页（返回键用了 $backTries 次）")
                onStateChange("已进入首页")
                // 首页出现后稍等一下，等首个视频开始播放
                delay(1_000)
                return true
            }
            if (!service.hasWindowInfo()) {
                // 读不到窗口：先等，不要按返回键
                waitTries++
                if (waitTries > HOME_INFO_WAIT_TRIES) {
                    // 窗口信息始终为空（实测服务刚重启时会这样），
                    // 但快手确实在前台：此时按返回键是危险的（会把用户退出快手），
                    // 所以直接接受当前状态进入主循环——主页识别、评论面板、
                    // 直播间判定在每一轮里都会重新尝试，不会因此走偏。
                    Log.w(
                        TAG,
                        "窗口信息始终不可用，但快手在前台；直接进入主循环" +
                            "（页面类型将由每轮检测决定）",
                    )
                    onStateChange("已进入快手")
                    delay(1_000)
                    return true
                }
                Log.i(TAG, "窗口信息尚未就绪，等待中 (第 $waitTries 次)")
                delay(HOME_INFO_WAIT_MS)
                continue
            }
            backTries++
            Log.i(TAG, "当前不在首页，按返回键 (第 $backTries 次)")
            service.pressBack()
            delay(AFTER_BACK_DELAY_MS)
        }

        // 3) 返回键用尽仍未确认首页：再给一次机会（有些机型首个窗口树要稍晚才完整）
        for (i in 0 until HOME_INFO_WAIT_TRIES) {
            if (service.isHomeFeed()) {
                onStateChange("已进入首页")
                delay(1_000)
                return true
            }
            delay(HOME_INFO_WAIT_MS)
        }
        // 仍未确认：只要快手确实在前台就继续，让主循环的逐轮检测去处理
        // （盲按返回键可能把用户退出快手，代价更大）。
        if (service.isKuaishouForeground()) {
            Log.w(TAG, "无法确认首页，但快手在前台；继续进入主循环")
            onStateChange("已进入快手")
            delay(1_000)
            return true
        }
        Log.w(TAG, "无法确认已进入首页")
        return false
    }

    /**
     * 等本应用自己退到后台。
     *
     * 实测：主界面还在前台时抓帧抓到的是本应用的白色界面
     * （进度条行像素恒为 246），会被误判成"强进度条"。
     * 启动任务通常是从主界面点开始的，因此这里必须等一等。
     */
    private suspend fun waitSelfToBackground(): Boolean {
        val deadline = System.currentTimeMillis() + SELF_BACKGROUND_WAIT_MS
        while (System.currentTimeMillis() < deadline && job?.isActive == true) {
            val service = AutoScrollService.instance ?: return false
            if (!service.isSelfForeground()) {
                Log.i(TAG, "本应用已退到后台，开始抓帧")
                delay(600)
                return true
            }
            delay(300)
        }
        return false
    }

    /**
     * 用节点树量到的几何校准像素检测。
     *
     * 实测踩坑：旧实现取 `side_progress_group` 的底边当进度条行
     * （`rect.bottom - 4`），而该容器实测是 `0,1716-1080,2266`，
     * 但它在**某些页面/某些版本**下会返回整屏底部（实测出现 2400），
     * 于是锚点被校准到 y=2396/2400，真实进度条行 y=2262 被 ±40px 容差
     * 永久排除，每帧都报"无进度条"——这就是"进度识别不准"的根因之一。
     *
     * 现在的策略：
     * - 视频区底边用 `player` / `nasa_slide_play_view_pager_layout` 的底边
     *  （实测 2266），它才是"进度条不会越过的下界"，用于收敛搜索范围
     * - 进度条行锚点只做保守自校准（由像素检测连续命中来写回），
     *   节点树几何不再直接写锚点
     */
    private fun calibrateBarAnchor(root: android.view.accessibility.AccessibilityNodeInfo?) {
        if (root == null) return
        val screenH = screenHeightPx()
        if (screenH <= 0) return

        // 1) 视频区底边：优先用 player 容器，其次用 pager 容器
        var logged = false
        for (id in VIDEO_BOTTOM_IDS) {
            val bounds = UiStateProbe.findNodeBounds(root, id)
            val rect = bounds.rect ?: continue
            // 只接受"屏幕下半部、但不是屏幕最底"的值，避免拿到 2400 这种可疑值
            if (rect.bottom > screenH * 0.5f && rect.bottom < screenH) {
                // 该值在同一会话内几乎不变，只在真正变化时才打日志，避免每轮刷屏
                if (rect.bottom != lastVideoBottom) {
                    lastVideoBottom = rect.bottom
                    ProgressDetector.setVideoBottom(rect.bottom, screenH)
                    Log.i(TAG, "视频区底边来自 $id: ${rect.bottom}/$screenH")
                }
                logged = true
                break
            }
        }
        if (!logged) lastVideoBottom = -1
    }

    /** 屏幕高度（像素），取不到时返回 0 */
    private fun screenHeightPx(): Int {
        val service = AutoScrollService.instance ?: return 0
        return try {
            service.resources.displayMetrics.heightPixels
        } catch (_: Throwable) {
            0
        }
    }

    /** 屏幕宽高（像素），取不到时返回 0×0 */
    private fun screenSize(): Pair<Int, Int> {
        val service = AutoScrollService.instance ?: return 0 to 0
        return try {
            val dm = service.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        } catch (_: Throwable) {
            0 to 0
        }
    }

    /**
     * 实时判断页面类型。
     *
     * 优先级：
     * 1) 窗口类名明确指向直播间 → LIVE_ROOM
     * 2) 窗口类名明确指向首页   → HOME_FEED
     * 3) 类名不明确（拿不到 Activity 信息）时，用截图里的进度条反推：
     *    能读到进度条 = 首页信息流；读不到 = 交给调用方按"无进度条"处理。
     */
    private fun detectPageType(service: AutoScrollService): PageDetector.PageType {
        val cls = AutoScrollService.currentWindowClass ?: service.resolveWindowClass()
        val byName = PageDetector.classifyByClassName(
            cls,
            AutoScrollService.currentPackage,
        )

        if (byName == PageDetector.PageType.LIVE_ROOM) return byName

        // 直播预览卡自动跳转的兜底：这类卡片 15 秒后会自己进直播间
        // （`jumpUrl=kwaipreviewlive://enterCurrentLive`），实测进过
        // LiveSlideActivity 而 currentWindowClass 一度还没更新，
        // 于是被判成 HOME_FEED。这里用更可靠的 Activity 类名再查一次。
        if (service.isLiveActivityForeground()) {
            return PageDetector.PageType.LIVE_ROOM
        }

        // 只要前台是快手且非直播间，就按首页信息流处理：
        // 真正的区分交给后续的进度条检测（读得到=视频，读不到=直播间/图文）。
        if (AutoScrollService.currentPackage == PageDetector.PKG_KUAISHOU ||
            byName == PageDetector.PageType.HOME_FEED
        ) {
            return PageDetector.PageType.HOME_FEED
        }

        return byName
    }

    /** 安全获取当前窗口根节点 */
    private fun safeRoot(service: AutoScrollService): android.view.accessibility.AccessibilityNodeInfo? {
        return try {
            service.rootInActiveWindow
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 获取快手窗口的根节点。
     *
     * 实测：rootInActiveWindow 会返回"当前活动窗口"，
     * 若本应用恰在前台，拿到的是自己的节点树（只有几个按钮）。
     * 因此必须遍历 windows 列表，定位目标应用的窗口。
     */
    /**
     * 取快手当前**活动**窗口的根节点。
     *
     * 实测踩坑：`service.windows` 里的顺序不是"活动窗口在前"。
     * 快手会在首页预加载直播视图，于是列表里同时存在
     * 首页窗口与直播窗口，早期实现取"第一个包名匹配的窗口"，
     * 结果在首页上拿到了直播那棵预加载子树
     * （实测首页与直播间的 id 集合完全相同，都是
     * `live_slide_container / live_slide_view_pager / ...`），
     * 使基于 id 的页面判据全部失效。
     *
     * 正确做法：优先用 `rootInActiveWindow`（它就是当前活动窗口），
     * 它不属于目标应用时再退回窗口列表。
     */
    private fun kuaishouRoot(
        service: AutoScrollService,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        // 1) 活动窗口优先——它才是用户正在看的那个
        try {
            val active = service.rootInActiveWindow
            if (active?.packageName?.toString() == PageDetector.PKG_KUAISHOU) {
                return active
            }
        } catch (_: Throwable) {
        }

        // 2) 退回窗口列表，但优先挑"有焦点"或用真实的窗口类型过滤
        try {
            val ws = service.windows
            var fallback: android.view.accessibility.AccessibilityNodeInfo? = null
            for (w in ws) {
                val r = try {
                    w.root
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (r.packageName?.toString() != PageDetector.PKG_KUAISHOU) continue
                if (w.isFocused || w.isActive) return r
                if (fallback == null) fallback = r
            }
            if (fallback != null) return fallback
        } catch (_: Throwable) {
        }
        return null
    }

    /** 执行一次上滑，返回是否成功 */
    private suspend fun swipe(service: AutoScrollService): Boolean {
        return suspendCancellableCoroutine { cont ->
            service.swipeUp { ok ->
                if (cont.isActive) cont.resume(ok)
            }
        }
    }
}
