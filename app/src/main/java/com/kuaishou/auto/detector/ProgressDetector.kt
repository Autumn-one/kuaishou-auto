package com.kuaishou.auto.detector

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.abs

/**
 * 读取快手极速版首页视频进度条的播放进度。
 *
 * 实测依据（小米 13, 1080x2400, Android 16）：
 * - 进度条位于 y=2261..2264，横贯整屏（实测轨道 x≈30..1049）
 * - 对应控件 com.yxcorp.gifshow.detail.view.SlidePlaySeekBar (id slide_play_progress)
 * - 结构是一个**两电平台阶**：已播放段是叠加在视频上的亮条（实测相对上方
 *   参考行的增量约 +92），未播放段只留淡淡轨道（约 +34）。
 *   边界就是亮条结束、增量掉到轨道电平的位置。
 *
 * 算法分三步（每一步都有实测帧与合成帧的对照验证）：
 *
 * 1) **粗定位**：在轨道区间内找最大的负台阶 d[x]-d[x+K]。
 *    进度条只有一个台阶（进度边界），因此这个位置就在边界附近，
 *    且完全不受"左端暗区/高光/渐变"影响——这一点很关键：
 *    早先版本用"左端一小段的中位数"当已播放亮度，遇到左端有暗区会
 *    严重高估进度（实测在 24 帧序列上稳定偏大 55px）。
 *
 * 2) **精定位**：台阶两侧各取一小段窗口估计已播放电平与轨道电平，
 *    取中点作阈值，在台阶附近找 d 向下穿过阈值的交叉点（带亚像素插值）。
 *    中点阈值天然抗噪声，实测在 ±40 灰度噪声下误差仍 ≤0.5px。
 *
 * 3) **滑窗收尾**：把边界样本交给 25 点的滑窗去修正，
 *    抵消"局部窗口恰好落在轨道凹陷"造成的偏差，作用限制在 ±K 像素内，
 *    避免引入新的系统偏差。
 *
 * 几个刻意避开的坑：
 * - 不能用"峰值 × 0.5"当阈值：亮条后面的视频内容一变亮，峰值就抬高，阈值跟着飘。
 * - 不能用"左端中位数"当已播放亮度：左端常有暗区（轨道起点圆角/空隙），会把亮度估低。
 * - 不能用 Otsu：已播放段占比可能低到 3%，直方图接近单峰，阈值会掉到轨道电平以下。
 * - 不能用"最后一个超过阈值的样本"当边界：视频高光会在轨道里留下孤立亮点。
 * - 不能用绝对像素坐标算进度：进度条轨道两端各留约 30px，
 *   用整屏宽度会把 0~100% 压缩成 2.8%~97.2%（实测固定偏小约 3%）。
 */
object ProgressDetector {

    private const val TAG = "ProgressDetector"

    /** 默认锚点：实测进度条在 y=2262 / 2400。 */
    private const val BAR_Y = 2262
    private const val SCREEN_REF_H = 2400f

    /** 默认视频区底边：实测首页视频区 0,0-1080,2266，底边 2266 / 2400。 */
    private const val VIDEO_BOTTOM_REF_Y = 2266

    /** 轨道相对上方参考行的最小亮度增量。 */
    private const val TRACK_MIN = 6f

    /** 已播放段与未播放轨道的最小可分辨电平差。实测正常视频约 58。 */
    private const val MIN_CONTRAST = 22f

    /** 单模态时判定"整条已播放"的电平下限。 */
    private const val PLAYED_MIN = 55f

    /** 单模态时判定"整条未播放"的电平上限。 */
    private const val TRACK_MAX = 42f

    /** 粗定位认为"这是一个台阶"的最小落差。 */
    private const val STEP_MAG = 15f

    /** 粗定位比较两点所用的间隔（像素）。 */
    private const val STEP_K = 12

    /** 台阶两侧估计电平所用的窗口（像素）。 */
    private const val SIDE_WIN = 24

    /** 轨道至少要占整屏宽度的比例。实测约 0.94，0.55 足够宽松又能挡住局部亮点。 */
    private const val MIN_TRACK_SPAN = 0.55f

    /**
     * 兜底候选（远离锚点的行）被当作有效读数所需的最低横向覆盖率。
     *
     * 真实进度条横贯整屏，实测覆盖率 0.92~0.95。
     * 实测事故：兜底行覆盖率只有 0.67~0.68（是视频画面内容），
     * 却被当成进度条读出一个假的 `94%`，导致"明明读到 95% 却停在那不动、
     * 最后靠停滞超时划走"。取 0.85 把这类行挡住。
     */
    private const val FALLBACK_MIN_COVERAGE = 0.85f

    /** 轨道行与参考行之间的默认偏移（像素）。实测同一帧内 4~14 均可用。 */
    private const val REF_OFFSET = 8

    /** 滑窗收尾的窗口长度与作用上限（像素）。 */
    private const val FINISH_WIN = 25
    private const val FINISH_CAP = 24

    /**
     * 暂停态进度条的"白色已播放段"判据。
     *
     * 实测两种渲染方式：
     * - **播放中**：已播放段是叠加在视频上的半透明亮条，亮度随视频内容浮动
     *   （实测同一帧内 x<280 处亮度 120、x>280 处 62，边界清晰）
     * - **暂停后**：进度条切换成"白色实心已播放段 + 白色圆点播放头"，
     *   已播放段亮度**恒定在 255**，与视频内容无关（实测 3 帧都是 255）
     *
     * 后者是暂停探测时的主路径：白色段末端就是播放位置，不受视频内容干扰。
     */
    private const val WHITE_LEVEL = 200f

    /** 白色已播放段至少要占轨道的这个比例才算数（排除高光噪点） */
    private const val MIN_WHITE_RUN = 0.02f

    /** 白色已播放段内允许的最大断裂长度（像素），用于容忍抗锯齿缝隙 */
    private const val WHITE_GAP_TOLERANCE = 3

    /** 找播放头时向上探测的行数（圆点比进度条本体高） */
    private const val KNOB_PROBE_ABOVE = 10

    /** 一个列要被认定为"圆点列"，其上方的白像素数下限 */
    private const val KNOB_MIN_WHITE_ABOVE = 6

    /** 圆点列判定的高度容差（与最高列的差） */
    private const val KNOB_HEIGHT_TOLERANCE = 1

    /**
     * 进度条轨道的左右端（相对整屏宽度的比例）。
     *
     * 实测轨道 x≈30..1049（两侧各留约 30px）。暂停态的进度值用这一对端点换算，
     * 因为暂停帧里"未播放轨道"叠在视频内容上，横向增量会被视频明暗打断，
     * 用它推端点会得到 632 这种偏小的值，把 60% 算成 100%。
     * 播放路径仍按帧内实测的连续段取端点（那里更准），本参数只服务暂停路径。
     */
    @Volatile
    private var trackLeftRatio: Float = 30f / 1080f

    @Volatile
    private var trackRightRatio: Float = 1049f / 1080f

    /**
     * 用一帧播放态里测到的干净轨道端点校准轨道比例。
     *
     * 只接受"足够长"（≥55% 屏宽）且端点都在屏幕内的测量，避免用噪声写回。
     */
    fun calibrateTrack(left: Int, right: Int, screenWidth: Int, screenHeight: Int) {
        if (screenWidth <= 0 || screenHeight <= 0) return
        if (right - left + 1 < (screenWidth * MIN_TRACK_SPAN).toInt()) return
        if (left < 0 || right >= screenWidth) return
        trackLeftRatio = left.toFloat() / screenWidth
        trackRightRatio = right.toFloat() / screenWidth
    }

    /** 单行要成为进度条候选，平均亮度增量至少要达到这个值（且必须为正）。 */
    private const val MIN_ROW_BRIGHTNESS = 10f

    /** 进度条的最大厚度（像素）。真实进度条 4px；底部导航栏边界为 10px 连续带。 */
    private const val MAX_BAR_THICKNESS = 12

    /** 单像素被计入"横向覆盖"所需的最小正增量。 */
    private const val MIN_PIXEL_DELTA = 12f

    /** 一行要成为进度条候选，至少要有这么大比例的采样点满足 [MIN_PIXEL_DELTA]。 */
    private const val MIN_ROW_COVERAGE = 0.25f

    /** 允许的锚点偏差（像素），由节点树几何校准。 */
    private const val ANCHOR_TOLERANCE = 60

    /**
     * 进度条搜索带相对**视频区底边**的高度（像素比例）。
     *
     * 实测进度条位于视频区底边往上 4~10px，因此搜索带只需要覆盖底边上方一小段。
     */
    private const val SEARCH_BAND_RATIO = 0.09f

    /**
     * 进度条行相对**整屏高度**的比例。
     *
     * 注意必须用整屏高度，不能用"检测到的内容区高度"：
     * 实测 capture 的内容区检测会把底边误判成 2375（真实 2399），
     * 按内容区算锚点会偏 24px，把上方视频内容拉进搜索窗。
     */
    @Volatile
    private var barAnchorRatio: Float = BAR_Y / SCREEN_REF_H

    /**
     * 最近一帧的进度条行是否来自"远离锚点的兜底候选"。
     *
     * 用途：兜底行很可能是视频画面内容（高光、字幕）而非进度条，
     * 因此**不允许它参与锚点自校准**。
     * 实测踩过：锚点被兜底行污染成 y=2139（真实进度条在 2262），
     * 此后每帧都在读视频画面，表现为进度数字乱跳、且一直等不到播完。
     */
    @Volatile
    private var lastRowWasFallback = false

    /** 最近一帧的进度条行是否可信（非兜底候选） */
    fun wasLastRowTrusted(): Boolean = !lastRowWasFallback

    /**
     * 是否输出"没找到进度条"时的逐行剖面诊断。
     *
     * 由 `--es dbg 1` 打开。用于排查"进度条可见却判为无进度条"。
     */
    @Volatile
    var diagnose: Boolean = false

    /**
     * 最近一帧检测到的行横向覆盖率（0~1）。
     *
     * 用途：真实进度条横贯整屏（实测 0.92~0.95），
     * 而视频内容的高光/字幕很少横贯 90% 以上宽度。
     * 调用方据此拒绝把"窄"的行当作锚点候选。
     */
    @Volatile
    private var lastCoverageValue = 0f

    /** 最近一帧命中行的横向覆盖率 */
    fun lastCoverage(): Float = lastCoverageValue

    /**
     * 诊断：当前锚点与轨道的状态摘要。
     *
     * 用于排查"新片段开头进度跳变"：需要知道那一刻锚点是多少、
     * 轨道端点是否已被校准、以及上一帧的行是否可信。
     */
    fun describeAnchor(): String {
        val h = SCREEN_REF_H
        return "锚点=${"%.1f".format(barAnchorRatio * 100)}%(y=${(barAnchorRatio * h).toInt()}) " +
            "轨道=${"%.1f".format(trackLeftRatio * 100)}%..${"%.1f".format(trackRightRatio * 100)}% " +
            "视频底边=${"%.1f".format(videoBottomRatio * 100)}% " +
            "上帧可信=${!lastRowWasFallback}"
    }

    /**
     * 视频区底边相对整屏高度的比例（默认 2266/2400）。
     *
     * 进度条一定在视频区内，底部导航栏（实测 y≥2266）里那条更亮的行
     * 经常被误判成进度条，因此搜索带必须用视频区底边封顶。
     */
    @Volatile
    private var videoBottomRatio: Float = VIDEO_BOTTOM_REF_Y / SCREEN_REF_H

    /**
     * 用节点树量到的进度条行号校准锚点。
     *
     * 注意：锚点只是"优先选哪个候选行"的提示，**不是硬约束**。
     * 实测踩坑：某版本把 side_progress_group 的底边当进度条行
     * （该容器底边实测是屏幕底部 2400），锚点被校准到 y=2396/2400，
     * 而旧实现只在锚点 ±40px 内接受候选，真实进度条行 y=2262 被判为
     * 超差，导致每一帧都报"无进度条"、任务退化成盲滑。
     *
     * @param barRowY 进度条行在屏幕坐标里的 y
     * @param screenHeight 整屏高度（不是内容区高度）
     */
    fun setBarAnchor(barRowY: Int, screenHeight: Int) {
        if (screenHeight <= 0) return
        val ratio = barRowY.toFloat() / screenHeight
        // 只接受视频区内的合理值：必须明显高于视频区底边，否则不是进度条行
        val videoBottom = (videoBottomRatio * screenHeight).toInt()
        if (ratio <= 0.5f || ratio >= 0.999f) return
        if (barRowY > videoBottom - 2) {
            Log.w(TAG, "拒绝锚点 y=$barRowY：落在视频区底边($videoBottom)之下，不是进度条行")
            return
        }
        barAnchorRatio = ratio
        Log.i(TAG, "进度条锚点已校准: y=$barRowY / $screenHeight = $ratio")
    }

    /** 用节点树量到的视频区底边收敛搜索范围（进度条不会出现在它下面）。 */
    fun setVideoBottom(y: Int, screenHeight: Int) {
        if (screenHeight <= 0) return
        val ratio = y.toFloat() / screenHeight
        if (ratio > 0.5f && ratio <= 1f) {
            // 同一会话里该值几乎不变，只在真正变化时打日志，避免每轮刷屏
            if (kotlin.math.abs(ratio - videoBottomRatio) > 0.0005f) {
                Log.i(TAG, "视频区底边已校准: y=$y / $screenHeight = $ratio")
            }
            videoBottomRatio = ratio
        }
    }

    /** 恢复默认锚点与默认视频区底边 */
    fun resetBarAnchor() {
        barAnchorRatio = BAR_Y / SCREEN_REF_H
        videoBottomRatio = VIDEO_BOTTOM_REF_Y / SCREEN_REF_H
    }

    data class Result(
        /** 播放进度 0.0~1.0；null 表示未检测到进度条 */
        val progress: Float?,
        /** 检测强度，用于诊断 */
        val strength: Float,
        /** 高亮像素占比 */
        val litRatio: Float,
        /** 诊断字符串，未检测到进度条时非空 */
        val profile: String? = null,
        /** 动态搜索定位到的进度条行号（诊断用，-1 表示未找到） */
        val barY: Int = -1,
        /** 轨道左右端（像素，诊断用） */
        val trackLeft: Int = -1,
        val trackRight: Int = -1,
    ) {
        val hasBar: Boolean get() = progress != null
    }

    /**
     * @param bitmap 全屏截图
     * @param contentTop 画面中真实内容的上边界（黑边场景下非 0）
     * @param contentBottom 画面中真实内容的下边界
     */
    fun detect(
        bitmap: Bitmap,
        contentTop: Int = 0,
        contentBottom: Int = -1,
        paused: Boolean = false,
    ): Result {
        lastRowWasFallback = false
        val w = bitmap.width
        val h = bitmap.height
        val cTop = contentTop.coerceIn(0, h - 1)
        val cBottom = (if (contentBottom < 0) h - 1 else contentBottom).coerceIn(cTop, h - 1)

        // 锚点必须按【整屏高度】算，不能用内容区高度。
        val anchorY = (barAnchorRatio * h).toInt().coerceIn(cTop, cBottom)

        // 搜索带下沿：视频区底边。进度条不会出现在底部导航栏里，
        // 而导航栏上沿那条硬边界比进度条更亮（实测 2266 起），必须排除。
        val videoBottom = (videoBottomRatio * h).toInt().coerceIn(cTop + 2, cBottom)
        // 搜索带上沿：覆盖视频区底边往上的一段；若锚点更靠上则一并覆盖
        val bandTop = (videoBottom - (h * SEARCH_BAND_RATIO).toInt()).coerceAtLeast(cTop + 1)
        val y0 = minOf(bandTop, anchorY - ANCHOR_TOLERANCE).coerceAtLeast(cTop + 1)
        val y1 = (videoBottom - 1).coerceAtMost(cBottom - 1)
        if (y1 <= y0) return Result(null, 0f, 0f, "搜索带无效($y0..$y1)", -1)

        val bestY = findBarRow(bitmap, cTop, y0, y1, anchorY)
        if (bestY < 0) {
            lastRowWasFallback = false
            // 诊断（开启时）：没找到进度条时，把搜索带内每行的亮度阶跃打出来。
            //
            // 目的：用户反馈"进度条明明可见，却仍去点暂停"。
            // 要判断到底是"条没画"还是"画了但我们的判据没认出"，
            // 唯一可靠办法就是看到那一刻每行的真实数值。
            if (diagnose) {
                val rows = StringBuilder()
                var yy = y0
                while (yy <= y1) {
                    val refY = (yy - 6).coerceAtLeast(cTop)
                    val ra = IntArray(w)
                    val rb = IntArray(w)
                    bitmap.getPixels(ra, 0, w, 0, yy, w, 1)
                    bitmap.getPixels(rb, 0, w, 0, refY, w, 1)
                    var sum = 0f
                    var hits = 0
                    var n = 0
                    var x = 0
                    while (x < w) {
                        val dv = (((ra[x] shr 16 and 0xFF) - (rb[x] shr 16 and 0xFF)) +
                            ((ra[x] shr 8 and 0xFF) - (rb[x] shr 8 and 0xFF)) +
                            ((ra[x] and 0xFF) - (rb[x] and 0xFF))) / 3f
                        sum += dv
                        if (dv > MIN_PIXEL_DELTA) hits++
                        n++
                        x += 8
                    }
                    val mean = if (n > 0) sum / n else 0f
                    val cov = if (n > 0) hits.toFloat() / n else 0f
                    rows.append("y$yy:${"%.0f".format(mean)}/${"%.2f".format(cov)} ")
                    yy += 4
                }
                Log.w(TAG, "无进度条·行剖面(均值/覆盖率): $rows")
                Log.w(
                    TAG,
                    "无进度条·参数: 搜索带$y0..$y1 锚点=$anchorY " +
                        "视频底边=$videoBottom 内容区$cTop..$cBottom",
                )
            }
            return Result(null, 0f, 0f, null, -1)
        }

        val refY = (bestY - REF_OFFSET).coerceIn(cTop, cBottom)
        val d = rowDelta(bitmap, bestY, refY, w)

        // 轨道 = 最长的连续 "有增量" 段
        val run = longestRun(d, TRACK_MIN)
        var runLeft = run.first
        var runRight = run.second

        // 轨道端点修正：单帧最长连续段会被视频内容截断。
        //
        // 实测踩坑：`loop-00` 这一帧里轨道右端被暗内容截断成 x=942
        // （真实轨道到 x≈1049），于是进度 = (边界-左端)/(右端-左端)
        // 的分母偏小，算出 85.4%；把同一帧缩放到别的分辨率后右端测到满宽，
        // 同一画面变成 76~77%。**即同一画面在不同帧/分辨率下能差 8~9%**。
        //
        // 轨道是屏幕的固定几何（同一机型上恒定），比单帧的"连续段"可靠得多，
        // 因此当已校准的轨道比例比本帧测到的段**更宽**时，以校准值为准；
        // 若本帧测到更宽（说明校准值偏保守），则用本帧值并回写校准。
        val calLeft = (trackLeftRatio * w).toInt()
        val calRight = (trackRightRatio * w).toInt()
        val calSpan = calRight - calLeft
        val runSpan = if (runLeft >= 0) runRight - runLeft + 1 else 0
        if (calSpan > runSpan && calSpan >= (w * MIN_TRACK_SPAN).toInt()) {
            runLeft = calLeft
            runRight = calRight
        }
        val runOk = runLeft >= 0 && runRight - runLeft + 1 >= (w * MIN_TRACK_SPAN).toInt()

        // ---- 0) 暂停态专用：白色实心已播放段 ----
        //
        // 实测：暂停后进度条会换成"白色已播放段 + 白色播放头"，已播放段亮度
        // 恒为 255、与视频内容无关；此时段末端即播放位置，比两电平台阶判据更稳
        // （视频明暗变化不会影响它）。播放中出现不了 255（实测最高 192），
        // 因此这一步只对暂停帧生效。
        //
        // 这条路径**只在调用方明确告知"这是暂停帧"时**才走（[paused]=true）。
        // 实测踩过：不加这个门会误判——视频里出现大片白色内容时，
        // 轨道行左侧恰好有连续亮像素，于是白色段判据成立、返回值 100%，
        // 而强度是 WHITE_LEVEL(200)，与真实读数的 90 明显不同。
        // 播放路径保持用两电平台阶判据。
        //
        // 这条路径**不依赖** delta 轨道：暂停帧的"未播放轨道"叠在视频画面上，
        // 横向增量会被视频明暗打断（实测最长连续段只有 411px，够不到 55% 门槛），
        // 用它推端点会把 60% 误算成 100%。因此用已校准的轨道比例（默认 30..1049）
        // 换算进度，并且直接从左侧轨道起点开始找白色段。
        if (paused) {
            val tLeft = (trackLeftRatio * w)
            val tRight = (trackRightRatio * w)
            val tSpan = (tRight - tLeft).coerceAtLeast(1f)
            val whiteEnd = whiteRunEnd(bitmap, bestY, tLeft.toInt(), (tRight.toInt() - 1), w)
            if (whiteEnd > tLeft.toInt()) {
                // 优先用播放头（圆点）圆心：它是精确播放位置；
                // 白色段末端是圆点右边缘，会稳定偏大半个圆点宽度（实测约 1%）。
                val knob = findPlayhead(bitmap, bestY, tLeft.toInt(), whiteEnd, w)
                val mark = if (knob > 0) knob else whiteEnd
                val p = ((mark - tLeft) / tSpan).coerceIn(0f, 1f)
                return Result(p, WHITE_LEVEL, p, null, bestY, runLeft, runRight)
            }
        }

        if (!runOk) {
            return Result(
                null, 0f, 0f,
                "无轨道(最长连续段=" + (runRight - runLeft + 1) + ")",
                bestY, runLeft, runRight,
            )
        }
        val left = runLeft
        val right = runRight
        val span = (right - left).coerceAtLeast(1)

        // ---- 1) 粗定位：选"对比度最大"的台阶，而不是"落差最大"的 ----
        //
        // 实测踩坑：轨道左端（x≈30）有一小段明显更亮的圆角/起播高光，
        // 用它算出的"落差"比真实边界还大，但它的"已播放电平"取到的是
        // 轨道左侧的暗区（≈0），于是得到负对比度，直接把整帧判成
        // "台阶对比不足"从而报"无进度条"——这是"进度识别不准"的第二个根因。
        //
        // 改为：枚举所有台阶候选，按"左侧电平 - 右侧电平"（即真实对比度）
        // 择优；只有正对比度足够大的候选才算数。这样左端高光会被自然排除，
        // 而整条轨道电平一致时不会误判出一个假台阶，能正确落到
        // "全已播放 / 全未播放"的判定分支。
        var stepX = -1
        var stepMag = 0f
        // 初值取 0 而不是 -Float.MAX_VALUE：没有任何候选通过筛选时，
        // 这个值会被直接写进日志，用 -MAX_VALUE 会打出
        // "-3.4e38" 这种明显是初值泄漏的噪声（实测日志里出现过）。
        var bestContrast = 0f
        var x = left
        while (x + STEP_K <= right) {
            val m = d[x] - d[x + STEP_K]
            if (m > STEP_MAG) {
                // 两侧窗口都必须完整落在轨道内：
                // 轨道两端的外侧是 0 电平（实测 x<30 与 x>1049 处增量为 0），
                // 若窗口越界就会拿到假的"已播放电平"，把 0% 误判成接近满进度。
                val pFrom = x - SIDE_WIN
                val tTo = x + 2 + SIDE_WIN
                if (pFrom >= left && tTo <= right) {
                    val lvP = medianInRange(d, pFrom, x)
                    val lvT = medianInRange(d, x + 2, tTo)
                    val contrast = lvP - lvT
                    if (contrast > bestContrast) {
                        bestContrast = contrast
                        stepX = x
                        stepMag = m
                    }
                }
            }
            x++
        }

        if (stepX < 0 || stepMag < STEP_MAG || bestContrast < MIN_CONTRAST) {
            // 没有台阶（或唯一候选是左端高光那种假台阶）：
            // 整条轨道电平一致 → 要么全已播放，要么全未播放
            val lvLeft = medianInRange(d, left, left + SIDE_WIN)
            val lvRight = medianInRange(d, right - SIDE_WIN + 1, right + 1)
            val lvAll = medianInRange(d, left, right + 1)
            if (abs(lvLeft - lvRight) < MIN_CONTRAST) {
                if (lvLeft >= PLAYED_MIN && lvAll >= PLAYED_MIN) {
                    return Result(1f, lvAll, 1f, null, bestY, left, right)
                }
                if (lvLeft <= TRACK_MAX && lvAll <= TRACK_MAX) {
                    return Result(0f, lvAll, 0f, null, bestY, left, right)
                }
            }
            return Result(
                null, lvAll, 0f,
                "无台阶(落差=" + fmt(stepMag) + " 对比=" + fmt(bestContrast) +
                    " 左=" + fmt(lvLeft) + " 右=" + fmt(lvRight) + ")",
                bestY, left, right,
            )
        }

        // ---- 2) 精定位：台阶两侧电平 → 中点阈值 → 交叉点 ----
        val played = medianInRange(d, stepX - SIDE_WIN, stepX - 1)
        val track = medianInRange(d, stepX + 2, stepX + 2 + SIDE_WIN)
        if (played - track < MIN_CONTRAST) {
            return Result(
                null, played, 0f,
                "台阶对比不足(" + fmt(played - track) + " @x=" + stepX + ")",
                bestY, left, right,
            )
        }
        val threshold = (played + track) / 2f
        val lo = (stepX - SIDE_WIN - 4).coerceAtLeast(left)
        val hi = (stepX + SIDE_WIN + 4).coerceAtMost(right)
        var cross = -1
        var y = lo
        while (y <= hi) {
            if (d[y] <= threshold) {
                cross = y
                break
            }
            y++
        }
        if (cross < 0) {
            return Result(1f, played, 1f, null, bestY, left, right)
        }
        var boundary = cross.toFloat()
        if (cross - 1 >= lo && d[cross] != d[cross - 1]) {
            val frac = ((d[cross - 1] - threshold) / (d[cross - 1] - d[cross])).coerceIn(0f, 1f)
            boundary = cross - 1 + frac
        }

        // ---- 3) 滑窗收尾：抵消"两侧窗口恰好落在轨道凹陷"的偏差 ----
        val refined = refineWithWindow(d, left, right, boundary, threshold)
        val progress = ((refined - left) / span).coerceIn(0f, 1f)

        return Result(progress, played, progress, null, bestY, left, right)
    }

    private fun fmt(v: Float): String = "%.1f".format(v)

    /**
     * 检测"白色实心已播放段"的末端（暂停态进度条）。
     *
     * 判据：从轨道左端起，连续满足"该像素亮度 ≥ [WHITE_LEVEL]"的最长段。
     * 实测暂停态的已播放段是纯白（R=G=B=255），且紧贴轨道左端开始；
     * 播放态的已播放段亮度最高只有 192，永远进不了这个分支。
     *
     * 允许段内出现极短的暗点（1~2px 的抗锯齿/刻度缝隙），
     * 但不允许长于 [WHITE_GAP_TOLERANCE] 的断裂——否则就是真的未播放区。
     *
     * @return 白色段的末端 x；未检测到返回 -1
     */
    private fun whiteRunEnd(
        bitmap: Bitmap,
        y: Int,
        left: Int,
        right: Int,
        w: Int,
    ): Int {
        if (left < 0 || right <= left) return -1
        val rowA = IntArray(w)
        bitmap.getPixels(rowA, 0, w, 0, y, w, 1)

        fun isWhite(x: Int): Boolean {
            val p = rowA[x]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            return (r + g + b) / 3f >= WHITE_LEVEL
        }

        if (!isWhite(left)) return -1

        var end = left
        var x = left + 1
        var gap = 0
        while (x <= right) {
            if (isWhite(x)) {
                end = x
                gap = 0
            } else {
                gap++
                if (gap > WHITE_GAP_TOLERANCE) break
            }
            x++
        }
        if (end <= left) return -1
        if (end - left + 1 < (w * MIN_WHITE_RUN).toInt()) return -1
        return end
    }

    /**
     * 定位暂停态进度条的播放头（圆点）中心。
     *
     * 实测：暂停态在已播放段末端画一个白色圆点，它比进度条本体更高——
     * 圆点在进度条所在行的上方几像素处仍然是白的（实测圆心处白高 21px，
     * 而进度条本体只有 8px）。利用这个高度差就能把圆点从已播放段里分离出来，
     * 圆心即真实播放位置。
     *
     * 与直接用白色段末端相比：段末端是圆点的右边缘，会稳定偏大半个圆点宽度
     * （实测 1080 宽屏上约 1%）。取圆心可消掉这个系统偏差。
     *
     * @return 圆心 x；未检测到圆点返回 -1
     */
    private fun findPlayhead(
        bitmap: Bitmap,
        barY: Int,
        fromX: Int,
        toX: Int,
        w: Int,
    ): Int {
        val top = (barY - KNOB_PROBE_ABOVE).coerceAtLeast(0)
        val probeH = (barY - top) + 1
        if (probeH <= 0) return -1
        val left = fromX.coerceIn(0, w - 1)
        val right = toX.coerceIn(left, w - 1)
        val count = right - left + 1
        if (count <= 0) return -1

        val pixels = IntArray(count * probeH)
        try {
            bitmap.getPixels(pixels, 0, count, left, top, count, probeH)
        } catch (_: Throwable) {
            return -1
        }

        // 每一列在"进度条行上方"的白像素数；圆点列会明显多
        val whiteAbove = IntArray(count)
        for (c in 0 until count) {
            var white = 0
            for (r in 0 until probeH) {
                val p = pixels[r * count + c]
                val lum = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                if (lum >= WHITE_LEVEL) white++
            }
            whiteAbove[c] = white
        }
        val maxWhite = whiteAbove.maxOrNull() ?: return -1
        // 进度条本体在上方只有很少（或无）白像素；圆点必须明显更高
        if (maxWhite < KNOB_MIN_WHITE_ABOVE) return -1

        val threshold = maxWhite - KNOB_HEIGHT_TOLERANCE
        var sum = 0L
        var n = 0
        for (c in 0 until count) {
            if (whiteAbove[c] >= threshold) {
                sum += (left + c)
                n++
            }
        }
        return if (n > 0) (sum / n).toInt() else -1
    }

    /**
     * 在边界附近用滑动窗口中位数重新确认一次边界。
     *
     * 精定位依赖台阶两侧各 [SIDE_WIN] 像素的窗口，若窗口恰好落在视频高光
     * 或轨道凹陷上，电平估计会有偏差。这里用 25 点滑窗（以窗口内中位数为判据）
     * 寻找最后一个"仍在已播放电平"的位置，并把修正量限制在 [FINISH_CAP] 像素内，
     * 只做微调、不引入新的系统偏差。
     */
    private fun refineWithWindow(
        d: FloatArray,
        left: Int,
        right: Int,
        boundary: Float,
        threshold: Float,
    ): Float {
        val r = boundary.toInt()
        val from = (r - FINISH_CAP).coerceAtLeast(left)
        val to = (r + FINISH_CAP).coerceAtMost(right)
        if (to - from < FINISH_WIN) return boundary

        var last = -1
        var s = from
        while (s + FINISH_WIN <= to + 1) {
            if (medianInRange(d, s, s + FINISH_WIN) > threshold) last = s + FINISH_WIN - 1
            s++
        }
        if (last < 0) return boundary
        return last.toFloat().coerceIn(from.toFloat(), to.toFloat())
    }

    /** 取一整行相对参考行的带符号平均亮度增量 */
    private fun rowDelta(bitmap: Bitmap, y: Int, refY: Int, w: Int): FloatArray {
        val rowA = IntArray(w)
        val rowB = IntArray(w)
        bitmap.getPixels(rowA, 0, w, 0, y, w, 1)
        bitmap.getPixels(rowB, 0, w, 0, refY, w, 1)
        val out = FloatArray(w)
        for (x in 0 until w) {
            val a = rowA[x]
            val b = rowB[x]
            out[x] = (((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
                ((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) +
                ((a and 0xFF) - (b and 0xFF))) / 3f
        }
        return out
    }

    /** 最长的连续 "值 > min" 区间，返回 (start, end)；找不到返回 (-1, -1) */
    private fun longestRun(values: FloatArray, min: Float): Pair<Int, Int> {
        var bestStart = -1
        var bestEnd = -1
        var start = -1
        for (i in values.indices) {
            if (values[i] > min) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if (i - start > bestEnd - bestStart + 1) {
                    bestStart = start
                    bestEnd = i - 1
                }
                start = -1
            }
        }
        if (start >= 0 && values.size - start > bestEnd - bestStart + 1) {
            bestStart = start
            bestEnd = values.size - 1
        }
        return bestStart to bestEnd
    }

    /** 区间中位数（区间越界时自动收敛） */
    private fun medianInRange(a: FloatArray, from: Int, to: Int): Float {
        val n = a.size
        if (n == 0) return 0f
        val lo = from.coerceIn(0, n - 1)
        val hi = to.coerceIn(lo + 1, n)
        val cnt = hi - lo
        if (cnt <= 0) return a[lo]
        val arr = FloatArray(cnt)
        System.arraycopy(a, lo, arr, 0, cnt)
        arr.sort()
        return arr[cnt / 2]
    }

    /**
     * 在 [y0, y1] 搜索带内定位进度条行。
     *
     * 进度条特征：一条 4px 厚、比背景更亮的水平半透明条。
     * 候选行必须满足「带符号平均亮度增量为正且足够强」，
     * 且所在连通行组足够细窄（排除底部导航栏那种 10px 高的硬边界）。
     *
     * 多个候选同时命中时的选择顺序（实测踩坑后修正）：
     * 1. 先在贴近锚点的候选里选横向覆盖最好的（覆盖度最可靠地代表"横贯整屏"）
     * 2. 锚点附近没有候选时，退回全带最优覆盖的候选（不因锚点漂移而整体失效）
     *
     * 早先版本只接受 |mid - anchorY| <= 40 的候选，锚点一旦被错误的节点
     * 几何校准到 y=2396，真实进度条行 y=2262 就永久被判超差，
     * 表现为"每一帧都无进度条"。
     */
    private fun findBarRow(
        bitmap: Bitmap,
        cTop: Int,
        y0: Int,
        y1: Int,
        anchorY: Int,
    ): Int {
        val w = bitmap.width
        if (y1 <= y0) return -1

        val step = 8
        val refOffset = 6
        val rowA = IntArray(w)
        val rowB = IntArray(w)
        val rows = y1 - y0 + 1

        val bright = BooleanArray(rows)
        val coverage = FloatArray(rows)
        for (y in y0..y1) {
            val refY = (y - refOffset).coerceAtLeast(cTop)
            bitmap.getPixels(rowA, 0, w, 0, y, w, 1)
            bitmap.getPixels(rowB, 0, w, 0, refY, w, 1)
            var sum = 0f
            var n = 0
            var hits = 0
            var x = 0
            while (x < w) {
                val a = rowA[x]
                val b = rowB[x]
                val dr = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
                val dg = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
                val db = (a and 0xFF) - (b and 0xFF)
                val dv = (dr + dg + db) / 3f
                sum += dv
                if (dv > MIN_PIXEL_DELTA) hits++
                n++
                x += step
            }
            val mean = if (n > 0) sum / n else 0f
            coverage[y - y0] = if (n > 0) hits.toFloat() / n else 0f
            bright[y - y0] =
                mean >= MIN_ROW_BRIGHTNESS && hits >= (n * MIN_ROW_COVERAGE).toInt()
        }

        // 先收集所有细窄的亮行组
        var bestMid = -1
        var bestCoverage = -1f
        var fallbackMid = -1
        var fallbackCoverage = -1f
        var i = 0
        while (i < rows) {
            if (!bright[i]) {
                i++
                continue
            }
            var j = i
            // 允许中间漏掉一行，但不允许更长断裂
            while (j + 1 < rows && (bright[j + 1] || (j + 2 < rows && bright[j + 2]))) j++
            val thickness = j - i + 1
            if (thickness <= MAX_BAR_THICKNESS) {
                var cov = 0f
                for (k in i..j) if (coverage[k] > cov) cov = coverage[k]
                val mid = y0 + (i + j) / 2
                if (cov > fallbackCoverage) {
                    fallbackCoverage = cov
                    fallbackMid = mid
                }
                if (abs(mid - anchorY) <= ANCHOR_TOLERANCE && cov > bestCoverage) {
                    bestCoverage = cov
                    bestMid = mid
                }
            }
            i = j + 1
        }
        if (bestMid >= 0) {
            lastCoverageValue = bestCoverage
            return bestMid
        }
        // 锚点附近没有候选：**不再接受远处的兜底候选作为有效读数**。
        //
        // 实测事故：锚点附近读不到时，检测器回退到"覆盖度最高的行"，
        // 而那行是视频画面内容（覆盖度仅 0.67~0.68，远低于真实进度条的
        // 0.92+）。结果读出一个假的 `94%`，让判定以为"快播完了"，
        // 实际靠停滞超时才划走 —— 用户看到的就是"明明读到 95% 却停在那不动"。
        //
        // 真实进度条横贯整屏（覆盖率 0.92~0.95），因此这里用**覆盖率下限**
        // 过滤：达不到就不算读到，让上层走"无进度条"的正常分支
        //（上层会随机等待一段时间后划走），而不是拿假读数骗自己。
        if (fallbackCoverage >= FALLBACK_MIN_COVERAGE) {
            Log.w(
                TAG,
                "锚点 y=$anchorY 附近无候选，远处行 y=$fallbackMid " +
                    "覆盖率=${"%.2f".format(fallbackCoverage)} 达标，接受为读数",
            )
            lastCoverageValue = fallbackCoverage
            // 标记本行不可信：不参与锚点校准（但读数可用）
            lastRowWasFallback = true
            return fallbackMid
        }
        if (fallbackCoverage >= 0f) {
            Log.w(
                TAG,
                "锚点 y=$anchorY 附近无候选，远处行 y=$fallbackMid " +
                    "覆盖率=${"%.2f".format(fallbackCoverage)} 低于下限 " +
                    "$FALLBACK_MIN_COVERAGE（疑似视频画面内容），判为无进度条",
            )
        }
        return -1
    }

    /** 两次进度读数是否构成"播放回退"（即视频播完重播） */
    fun isLooped(prev: Float, curr: Float): Boolean {
        return prev - curr > 0.5f
    }

    /** 是否已接近播完 */
    fun isNearlyDone(progress: Float): Boolean = progress >= 0.95f

    /** 数值有效性检查 */
    fun isValid(progress: Float): Boolean = progress in 0f..1f && !progress.isNaN()

    /** 计算进度差的绝对值 */
    fun delta(a: Float, b: Float): Float = abs(a - b)
}
