package com.kuaishou.auto.service

import android.graphics.Bitmap

/**
 * 取帧方式的统一抽象。
 *
 * 进度条只能靠像素读取（实测节点树里拿不到进度值），而"怎么拿到这一帧"
 * 有两种可行方式：
 *
 * 1. [ScreenCapture] —— MediaProjection 截屏。
 *    优点是全平台可用；缺点是**每次都要用户点一次系统「录制或投放」授权**，
 *    进程重启/被回收后授权失效、必须重新授权。
 * 2. [AccessibilityCapture] —— 无障碍截图 API（`takeScreenshot`，API 30+）。
 *    优点是不需要任何授权弹窗、进程重启也不用重新授权；
 *    缺点是系统限流约 3 张/秒，且低版本机型不可用。
 *
 * 两者产出的帧尺寸与内容一致（都是整屏），因此 [ProgressDetector] 的
 * 全部几何逻辑（按屏宽/屏高比例）无需改动。
 *
 * 采用哪种方式由用户在设置里选择，见 [CaptureMode]。
 */
interface FrameSource {

    /** 当前是否真的能取帧（不只是"创建过对象"） */
    val isReady: Boolean

    /** 帧内真实内容的上边界（有黑边时不为 0） */
    val contentTop: Int

    /** 帧内真实内容的下边界 */
    val contentBottom: Int

    /**
     * 取一帧。
     *
     * 实现可以阻塞等待（调用方在协程里，不占用主线程）。
     *
     * @return 取到的帧；失败返回 null
     */
    fun capture(): Bitmap?

    /** 停止并释放资源 */
    fun stop()

    /** 诊断：把一帧存档，返回路径描述 */
    fun dumpFrame(bmp: Bitmap): String

    /** 自检：抓一帧并报告内容区与底部强变化行 */
    fun selfTest(): String
}

/**
 * 取帧方式。
 *
 * 默认 [AUTO]：能用无障碍截图就用它（免授权），否则退回 MediaProjection。
 */
enum class CaptureMode {
    /** 自动：优先无障碍截图，不可用时退回 MediaProjection */
    AUTO,

    /** 只用无障碍截图（免授权弹窗；系统限流约 3 张/秒） */
    ACCESSIBILITY,

    /** 只用 MediaProjection（每次启动都要授权，但不受截图频率限制影响） */
    MEDIA_PROJECTION;

    companion object {
        fun fromKey(key: String?): CaptureMode =
            entries.firstOrNull { it.name == key } ?: AUTO
    }
}
