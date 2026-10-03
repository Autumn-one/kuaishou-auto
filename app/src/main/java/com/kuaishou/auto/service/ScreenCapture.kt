package com.kuaishou.auto.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer

/**
 * 屏幕捕获，用于读取进度条像素。
 *
 * 实测：进度条无法通过 UI 树读取（uiautomator dump 在视频页失败），
 * 只能截屏做像素分析，故必须持有 MediaProjection。
 */
class ScreenCapture(private val context: Context) : FrameSource {

    companion object {
        private const val TAG = "ScreenCapture"
        const val REQUEST_CODE = 1001
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    var width = 0
        private set
    var height = 0
        private set
    private var density = 0

    /**
     * 捕获画面中"真实屏幕内容"所在区域。
     *
     * MIUI 的 MediaProjection 输出可能与屏幕分辨率不一致
     * （实测输出 1080x2400，但内容只占 y=40..690，其余为黑边），
     * 因此每次抓帧后检测内容区，坐标需按此区域映射。
     */
    override var contentTop = 0
        private set
    override var contentBottom = 0
        private set

    override val isReady: Boolean get() = projection != null && imageReader != null

    /** 由 MainActivity 在授权回调中调用 */
    fun start(resultCode: Int, data: Intent) {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        val proj = try {
            mgr.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "获取 MediaProjection 失败", t)
            null
        }
        if (proj == null) {
            Log.e(TAG, "MediaProjection 创建失败")
            return
        }

        val dm = context.resources.displayMetrics
        width = dm.widthPixels
        height = dm.heightPixels
        density = dm.densityDpi

        handlerThread = HandlerThread("screen-capture").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        // Android 14+ 要求：createVirtualDisplay 之前必须先注册回调
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection 被系统停止")
                stop()
            }
        }, handler)

        projection = proj

        imageReader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = try {
            proj.createVirtualDisplay(
                "kuaishou-auto-capture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, handler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "创建 VirtualDisplay 失败", t)
            null
        }
        if (virtualDisplay == null) {
            Log.e(TAG, "屏幕捕获启动失败")
            stop()
            return
        }
        Log.i(TAG, "屏幕捕获已启动 ${width}x$height")
    }

    /**
     * 抓取一帧并转换为 Bitmap。
     *
     * 实测要点：
     * - acquireLatestImage 会取到当前最新缓冲，必须 rewind 后再拷贝像素
     * - 不要一次把队列抽干，否则后续会长时间拿不到帧
     */
    override fun capture(): Bitmap? {
        val reader = imageReader ?: return null

        var image: Image? = null
        try {
            // 等待帧可用，最多约 500ms
            for (attempt in 0 until 25) {
                image = reader.acquireLatestImage()
                if (image != null) break
                Thread.sleep(20)
            }
            val img = image ?: return null
            val bmp = imageToBitmap(img, width, height)
            detectContentRegion(bmp)
            return bmp
        } catch (t: Throwable) {
            Log.e(TAG, "抓帧失败", t)
            return null
        } finally {
            try {
                image?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 检测捕获画面里"真实屏幕内容"的上下边界。
     *
     * MIUI 会输出带黑边的画面（内容只占中间一段），
     * 读进度条前必须知道内容区，才能把屏幕坐标映射到画面坐标。
     */
    private fun detectContentRegion(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        var top = 0
        var bottom = h - 1

        // 从顶部找第一行非黑
        var y = 0
        while (y < h) {
            if (!isRowBlack(bmp, w, y)) {
                top = y
                break
            }
            y += 8
        }
        // 从底部找最后一行非黑
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
            // 没检测到黑边，认为整幅就是内容
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

    private fun imageToBitmap(image: Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w

        // 关键：copyPixelsFromBuffer 从 buffer 当前位置开始读，
        // 必须先 rewind，否则会读到错位/空数据。
        buffer.rewind()

        val bmp = Bitmap.createBitmap(
            w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) {
            bmp
        } else {
            val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
            bmp.recycle()
            cropped
        }
    }

    /** 诊断：把一帧存档（覆盖写 capture-dump.png），返回路径 */
    override fun dumpFrame(bmp: Bitmap): String = FrameDump.dump(bmp)

    /**
     * 自检：把捕获帧存档并报告内容区/进度条位置。
     *
     * 与 [AccessibilityCapture.selfTest] 共用 [FrameDump]，保证两种取帧方式的
     * 输出格式一致，便于互相比较。
     */
    override fun selfTest(): String {
        val bmp = capture() ?: return "无法抓帧"
        val path = dumpFrame(bmp)
        return try {
            FrameDump.selfTest(bmp, contentTop, contentBottom) + " 存档=$path"
        } finally {
            bmp.recycle()
        }
    }

    override fun stop() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        imageReader = null
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        Log.i(TAG, "屏幕捕获已停止")
    }
}
