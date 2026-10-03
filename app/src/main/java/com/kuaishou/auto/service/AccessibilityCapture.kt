package com.kuaishou.auto.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 用「无障碍截图」取帧（`AccessibilityService.takeScreenshot`，API 30+）。
 *
 * 相比 MediaProjection 的好处：
 * - **不需要任何授权弹窗**：不用点系统那个「录制或投放」对话框，
 *   也就没有"每次进程重启都要重新授权"的脆弱点
 * - 不需要前台服务持有 mediaProjection 类型
 * - 不会带来任何"正在录屏"的系统提示
 *
 * 约束：
 * - 系统限流：两次请求之间至少约 333ms（AOSP
 *   `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS`），
 *   比本项目 350ms 的轮询间隔略宽裕，但**不能连续猛抓**——因此这里
 *   在两次取帧之间主动等待，避免拿不到帧
 * - 需要 `android:canTakeScreenshot="true"`（已加进 service 配置）
 * - 需要设备 API 30+，且部分厂商 ROM 可能未开放该能力；
 *   因此 [A11yCaptureSupport.isAvailable] 会做运行时确认，
 *   上层据此决定是否退回 MediaProjection
 *
 * 取帧是异步回调式的，这里用 [CountDownLatch] 转成阻塞调用，
 * 由调用方（协程，不在主线程）承担等待。
 */
class AccessibilityCapture : FrameSource {

    companion object {
        private const val TAG = "AccessibilityCapture"

        /**
         * 两次请求之间的最小间隔。
         *
         * AOSP 的限流是 333ms；这里取 360ms 留一点余量，
         * 避免因"太频繁"而拿到 null。
         */
        private const val MIN_REQUEST_INTERVAL_MS = 360L

        /** 单次取帧的等待上限 */
        private const val CAPTURE_TIMEOUT_MS = 1_500L
    }

    /** 运行时的可用性判定结果（由 [A11yCaptureSupport] 填写） */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "a11y-capture").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 上次成功发起请求的时间，用于限流 */
    @Volatile
    private var lastRequestAt = 0L

    override var contentTop: Int = 0
        private set

    override var contentBottom: Int = 0
        private set
    /** 是否曾经成功取到过帧 */
    @Volatile
    private var everSucceeded = false

    /**
     * 是否可以取帧。
     *
     * 判据是 [A11yCaptureSupport.isAvailable]（系统能力已实测确认），
     * 而不是"本实例自己成功过"——能力确认是用另一个探针实例做的，
     * 若按实例判断，新建的实例会一直"未就绪"，导致任务永远起不来。
     */
    override val isReady: Boolean
        get() = A11yCaptureSupport.isAvailable

    override fun capture(): Bitmap? {
        // 能力已确认可用才走这条路。
        // 注意：**确认能力本身**必须走 [captureRaw]，否则会形成循环依赖
        // （本检查要求 confirmed=true，而 confirmed 正是确认过程要写的结果，
        // 于是探针永远拿不到帧、能力永远确认不了——实测踩过）。
        if (!A11yCaptureSupport.isAvailable) return null
        return captureRaw()
    }

    /**
     * 真正取一帧，**不做能力检查**。
     *
     * 供能力确认探针使用：那时 `confirmed` 还没被置上，
     * 若走 [capture] 会因能力检查而立刻返回 null。
     */
    fun captureRaw(): Bitmap? {
        val service = AutoScrollService.instance ?: return null

        // 限流：距上次请求太近就等一会儿，否则系统直接不给帧
        val since = System.currentTimeMillis() - lastRequestAt
        if (since < MIN_REQUEST_INTERVAL_MS) {
            try {
                Thread.sleep(MIN_REQUEST_INTERVAL_MS - since)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        lastRequestAt = System.currentTimeMillis()

        val latch = CountDownLatch(1)
        val out = AtomicReference<Bitmap?>(null)
        val err = AtomicReference<Int?>(null)

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {

                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            out.set(toBitmap(result))
                        } catch (t: Throwable) {
                            Log.e(TAG, "HardwareBuffer 转 Bitmap 失败", t)
                            out.set(null)
                        } finally {
                            // 必须在用完之后 close，否则会耗尽系统缓冲区
                            try {
                                result.hardwareBuffer.close()
                            } catch (_: Throwable) {
                            }
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        // 常见 errorCode：1=ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR、
                        // 2=..._NO_ACCESSIBILITY_ACCESS、3=..._INTERVAL_TIME_SHORT、
                        // 4=..._INVALID_DISPLAY、5=..._ERROR_INVALID_WINDOW
                        Log.w(TAG, "takeScreenshot onFailure, errorCode=$errorCode")
                        err.set(errorCode)
                        latch.countDown()
                    }
                },
            )
        } catch (t: Throwable) {
            Log.e(TAG, "takeScreenshot 调用异常", t)
            return null
        }

        val got = try {
            latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!got) {
            Log.w(TAG, "取帧超时（${CAPTURE_TIMEOUT_MS}ms）")
            return null
        }

        val bmp = out.get() ?: run {
            Log.w(TAG, "取帧失败, errorCode=${err.get()}")
            return null
        }

        everSucceeded = true
        detectContentRegion(bmp)
        return bmp
    }
    /**
     * 把系统返回的 HardwareBuffer 包成 **可读像素** 的 Bitmap。
     *
     * 关键：`Bitmap.wrapHardwareBuffer()` 返回的是 `Config#HARDWARE` 位图，
     * 它**不支持 `getPixel()`/`getPixels()`**——实测直接抛
     * `IllegalStateException: unable to getPixel(), pixel access is not
     * supported on Config#HARDWARE bitmaps`。
     * 而 [ProgressDetector] 全靠逐像素读取（轨道增量、白色段、播放头都依赖它），
     * 因此这里必须再复制一份软件位图（`ARGB_8888`）才能用。
     *
     * 代价是一次内存拷贝（1080x2400 约 10MB）；换帧率约 3 张/秒，
     * 这个开销可以接受，且复制后就能立刻释放 HardwareBuffer。
     */
    private fun toBitmap(result: AccessibilityService.ScreenshotResult): Bitmap? {
        val hb = result.hardwareBuffer
        var hw: Bitmap? = null
        return try {
            val cs = result.colorSpace
                ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
            hw = Bitmap.wrapHardwareBuffer(hb, cs)
            if (hw == null) {
                Log.w(TAG, "wrapHardwareBuffer 返回 null")
                null
            } else {
                // 复制成软件位图，否则后续 getPixel 会抛异常
                hw.copy(Bitmap.Config.ARGB_8888, false)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "wrapHardwareBuffer 失败", t)
            null
        } finally {
            // 硬件位图持有 buffer，复制完就该释放引用
            try {
                hw?.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 检测画面里"真实屏幕内容"的上下边界。
     *
     * 与 MediaProjection 路径同样的目的：某些机型输出会带黑边，
     * 读进度条前必须知道内容区才能把屏幕坐标映射到画面坐标。
     */
    private fun detectContentRegion(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        var top = 0
        var bottom = h - 1

        var y = 0
        while (y < h) {
            if (!isRowBlack(bmp, w, y)) {
                top = y
                break
            }
            y += 8
        }
        y = h - 1
        while (y >= 0) {
            if (!isRowBlack(bmp, w, y)) {
                bottom = y
                break
            }
            y -= 8
        }

        if (bottom - top > h / 4) {
            contentTop = top
            contentBottom = bottom
        } else {
            contentTop = 0
            contentBottom = h - 1
        }
    }

    private fun isRowBlack(bmp: Bitmap, w: Int, y: Int): Boolean {
        var x = 0
        while (x < w) {
            val p = bmp.getPixel(x, y)
            val lum = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
            if (lum > 12) return false
            x += 24
        }
        return true
    }

    override fun dumpFrame(bmp: Bitmap): String = FrameDump.dump(bmp)

    override fun selfTest(): String {
        val bmp = capture() ?: return "无法抓帧"
        return try {
            FrameDump.selfTest(bmp, contentTop, contentBottom)
        } finally {
            bmp.recycle()
        }
    }

    override fun stop() {
        try {
            executor.shutdownNow()
        } catch (_: Throwable) {
        }
        Log.i(TAG, "无障碍截图已停止")
    }
}

/**
 * 运行时确认「无障碍截图」是否真的可用。
 *
 * 只看 `Build.VERSION.SDK_INT >= 30` 不够：部分厂商 ROM 没有开放这个能力，
 * 或用户在设置里关掉了。因此这里要求**真正成功取到过一帧**才算可用，
 * 避免"看起来支持、实际全是 null"这种静默失败。
 */
object A11yCaptureSupport {

    private const val TAG = "A11yCaptureSupport"

    /** 设备/系统是否具备该 API */
    val apiAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * 是否可用。
     *
     * [probe] 为真时会真的抓一帧来确认；确认过一次就记为可用。
     * 抓帧本身有耗时，因此只在需要时调用（用户切到该模式、或启动任务前）。
     */
    @Volatile
    private var confirmed = false

    @Volatile
    private var confirmedFailed = false

    val isAvailable: Boolean
        get() = apiAvailable && confirmed

    /** 曾经尝试确认但失败（用于给用户提示） */
    val isConfirmedUnsupported: Boolean
        get() = apiAvailable && confirmedFailed

    /** 是否曾经尝试确认（无论成功失败） */
    @Volatile
    private var probeAttempted = false

    /** 是否已经确认过（成功或失败都算），供"要不要弹授权框"的判断参考 */
    val isProbeDone: Boolean
        get() = probeAttempted

    /** 真正抓一帧确认；返回是否可用 */
    fun confirm(): Boolean {
        if (!apiAvailable) {
            Log.w(TAG, "系统版本过低（${Build.VERSION.SDK_INT}），不支持无障碍截图")
            probeAttempted = true
            return false
        }
        if (confirmed) return true

        val svc = AutoScrollService.instance
        if (svc == null) {
            // **不能**把这次失败记成"确认过且不可用"：
            // 服务只是还没连上，稍后会连上。若记成失败，
            // 能力就永远判为不可用、任务再也起不来（实测踩过）。
            Log.w(TAG, "无障碍服务未连接，本次确认跳过（稍后重试）")
            pendingWarmUp = true
            return false
        }

        probing = true
        try {
            val probe = AccessibilityCapture()
            val bmp = try {
                // 用 captureRaw：此时能力尚未确认，不能走带检查的 capture
                probe.captureRaw()
            } catch (t: Throwable) {
                Log.e(TAG, "确认取帧时异常", t)
                null
            } finally {
                probeAttempted = true
            }
            return if (bmp != null) {
                val w = bmp.width
                val h = bmp.height
                bmp.recycle()
                probe.stop()
                confirmed = true
                confirmedFailed = false
                Log.i(TAG, "无障碍截图可用（实测取帧 ${w}x$h）")
                true
            } else {
                probe.stop()
                confirmedFailed = true
                Log.w(TAG, "无障碍截图不可用（取帧返回 null）")
                false
            }
        } finally {
            probing = false
        }
    }

    /**
     * 在后台线程做一次确认。
     *
     * 用途：无障碍服务连上后马上预热，这样用户点「开始」时结果已经就绪，
     * 不会在"还没确认"的窗口里误弹一次 MediaProjection 授权框
     * （AUTO 模式下会白弹，因为它最终用的是无障碍截图）。
     *
     * 若此刻无障碍服务还没连上，**记下待确认**，等它连上后由
     * [AutoScrollService.onServiceConnected] 再触发一次——
     * 否则会出现"能力永远确认不了 → 任务起不来"（实测踩过：
     * 日志里只有一句 `无障碍服务未连接，无法确认截图能力`，然后再无下文）。
     */
    fun warmUpAsync() {
        if (!apiAvailable || confirmed) return
        if (AutoScrollService.instance == null) {
            // 服务还没连上：标记待确认，交给 onServiceConnected 处理
            pendingWarmUp = true
            Log.i(TAG, "无障碍服务尚未连接，稍后自动重试确认截图能力")
            return
        }
        Thread {
            try {
                confirm()
            } catch (t: Throwable) {
                Log.w(TAG, "预热确认失败", t)
            }
        }.start()
    }

    /** 是否有"待确认"请求（服务连上后需要重试） */
    @Volatile
    private var pendingWarmUp = false

    /** 确认是否正在进行中 */
    @Volatile
    private var probing = false

    /**
     * 等待后台确认完成（最多 [timeoutMs]）。
     *
     * 用于"启动任务"这类**必须知道结果才能决定用哪种取帧方式**的场景：
     * 确认在后台线程跑，主流程若不等就会在结果出来前误判为"不可用"，
     * 于是退回 MediaProjection 并弹一次授权框——违背了无障碍截图"免授权"的初衷。
     *
     * @return 等待后能力是否可用
     */
    fun awaitConfirmation(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (probing && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return isAvailable
    }

    /** 供服务连上后调用：若有待确认请求则真正执行 */
    fun retryPendingWarmUp() {
        if (!pendingWarmUp) return
        pendingWarmUp = false
        if (confirmed) return
        Thread {
            try {
                confirm()
            } catch (t: Throwable) {
                Log.w(TAG, "重试确认失败", t)
            }
        }.start()
    }

    /** 无障碍服务重连后需要重新确认 */
    fun reset() {
        confirmed = false
        confirmedFailed = false
        probeAttempted = false
    }
}
