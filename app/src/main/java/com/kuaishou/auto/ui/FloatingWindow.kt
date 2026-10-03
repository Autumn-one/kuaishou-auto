package com.kuaishou.auto.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮窗：单个按钮，点击切换"开始/停止"。
 *
 * 只保留这一个按钮：关闭悬浮窗、退出应用等操作放在 App 主界面，
 * 避免悬浮窗占用视频画面。
 *
 * 附加：可拖动，避免遮挡视频内容；运行时在按钮下方显示当前读到的进度，
 * 便于现场核对进度条识别是否准确。
 */
class FloatingWindow(
    private val context: Context,
    private val onToggle: (isRunning: Boolean) -> Unit,
) {

    companion object {
        private const val TAG = "FloatingWindow"
        private const val BTN_SIZE_DP = 56
        private const val TEXT_SIZE_SP = 15f

        /** 判定为"点击"而非"拖动"的位移阈值 */
        private const val CLICK_SLOP_DP = 12

        /** 提示条显示时长 */
        private const val MESSAGE_SHOW_MS = 4_000L

        private const val IDLE_COLOR = 0xE63C6EFF.toInt()
        private const val RUNNING_COLOR = 0xE6E53935.toInt()
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * 主线程 Handler。
     *
     * 实测踩坑：任务循环跑在 Dispatchers.Default 的 worker 线程上，
     * 直接改 View 会抛 CalledFromWrongThreadException 并把整个进程干掉
     * （现象就是"点了开始，悬浮窗和应用一起消失"）。
     * 所有触碰 View 的操作都必须切回主线程。
     */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var rootView: View? = null
    private var buttonView: TextView? = null
    private var progressView: TextView? = null
    private var messageView: TextView? = null
    private val params = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        format = android.graphics.PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        gravity = Gravity.TOP or Gravity.START
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        x = 0
        y = 400
        // 允许窗口使用挖孔/状态栏所在区域，位置由下面的拖拽夹紧逻辑保证安全。
        // 用 SHORT_EDGES 而不是 NEVER：后者会让系统强制把窗口整体挪出挖孔带，
        // 在部分 ROM 上表现为"悬浮窗位置自己跳"，而我们要的是可控的初始位置。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /**
     * 屏幕上沿的安全边距（像素）。
     *
     * 挖孔屏兼容：窗口不能被拖到状态栏/挖孔带里面去——那会让按钮
     * 被裁掉一部分或点不到。这里取"状态栏高度"与"挖孔 inset"的较大者，
     * 再留一点余量。
     *
     * 取不到 insets 时退回状态栏高度的经验值（约 24dp），
     * 保证任何机型上都至少有一点上边距。
     */
    private fun topSafeInsetPx(): Int {
        val dm = context.resources.displayMetrics
        val fallback = (24 * dm.density).roundToInt()
        val root = rootView ?: return fallback
        return try {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root)
            if (insets == null) {
                fallback
            } else {
                val bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
                )
                bars.top.coerceAtLeast(fallback)
            }
        } catch (_: Throwable) {
            fallback
        }
    }

    private var isRunning = false
    private var shown = false

    /**
     * 悬浮窗是否**真的**在屏幕上。
     *
     * 不能用内部的 shown 标志：没有 SYSTEM_ALERT_WINDOW 权限时
     * `WindowManager.addView` 不会抛异常，窗口会挂在 READY_TO_SHOW 状态，
     * 表面永远不绘制。此时 shown=true 会让主界面按钮显示"隐藏悬浮窗"，
     * 而屏幕上什么都没有——用户点一下就像没反应。
     * 因此这里以"视图已挂上窗口 + 权限仍在"为判据。
     */
    val isShown: Boolean
        get() {
            if (rootView == null) return false
            if (!Settings.canDrawOverlays(context)) return false
            return shown
        }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShown) return
        // 没有悬浮窗权限时 addView 不会报错，但窗口永远画不出来。
        // 这里提前拦掉，避免留下一个"看不见却存在"的窗口把状态搞脏。
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "缺少悬浮窗权限，本次不创建窗口")
            return
        }
        val dp = context.resources.displayMetrics.density

        val sizePx = (BTN_SIZE_DP * dp).roundToInt()

        val button = TextView(context).apply {
            text = context.getString(com.kuaishou.auto.R.string.overlay_start)
            setTextColor(Color.WHITE)
            textSize = TEXT_SIZE_SP
            gravity = Gravity.CENTER
            background = circleDrawable(IDLE_COLOR)
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        }

        // 进度读数：贴着按钮下沿显示，不占额外触摸区域
        val progress = TextView(context).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                topMargin = sizePx
            }
        }

        // 提示条：**点击没生效时必须让用户看见原因**。
        //
        // 实测踩过：无障碍未开启时点悬浮窗，按钮先乐观变红、随后立刻被
        // startTask() 打回"开始"，视觉上只是闪一下，用户完全不知道
        // 发生了什么，反馈就是"点了没反应"。这里把服务侧的原因显示出来。
        val message = TextView(context).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
            val pad = (6 * dp).roundToInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // 按钮上沿再往上一点，避免盖住按钮本身
                bottomMargin = sizePx
            }
        }

        val container = FrameLayout(context).apply {
            addView(button)
            addView(progress)
            addView(message)
        }

        attachDragHandler(container, button)

        rootView = container
        buttonView = button
        progressView = progress
        messageView = message

        // 初始位置也要落在安全区内（挖孔屏上 y=400 通常没问题，
        // 但小屏/横屏或大挖孔机型上仍需按实际 inset 校正）。
        params.y = params.y.coerceAtLeast(topSafeInsetPx())

        try {
            windowManager.addView(container, params)
            shown = true
        } catch (t: Throwable) {
            Log.e(TAG, "悬浮窗添加失败", t)
            rootView = null
            buttonView = null
            progressView = null
            messageView = null
        }
    }

    /**
     * 悬浮窗是否真的画在屏幕上了。
     *
     * `addView` 成功不代表用户看得到：没有 SYSTEM_ALERT_WINDOW 权限时，
     * WindowManager 里的窗口会停在 READY_TO_SHOW，surface 始终不显示。
     * 本方法用于让上层能识别这种"以为显示了其实没有"的情况。
     */
    fun isActuallyVisible(): Boolean {
        val v = rootView ?: return false
        return try {
            shown && v.isAttachedToWindow && v.isShown && v.windowVisibility == View.VISIBLE
        } catch (_: Throwable) {
            false
        }
    }

    fun hide() {
        val v = rootView ?: return
        try {
            windowManager.removeViewImmediate(v)
        } catch (_: Throwable) {
            try {
                windowManager.removeView(v)
            } catch (_: Throwable) {
            }
        }
        rootView = null
        buttonView = null
        progressView = null
        messageView = null
        shown = false
    }

    /**
     * 在悬浮窗上方显示一条提示（几秒后自动消失）。
     *
     * 用途：点击没真正开始任务时告诉用户**为什么**。
     * 没有它时，按钮只是把颜色打回去，用户只会觉得"点了没反应"。
     *
     * @param text 提示内容；空字符串表示清除
     */
    fun showMessage(text: String) {
        runOnMain {
            val tv = messageView ?: return@runOnMain
            if (text.isEmpty()) {
                tv.visibility = View.GONE
                return@runOnMain
            }
            tv.text = text
            tv.visibility = View.VISIBLE
            // 自动消失，避免长期遮挡视频画面
            mainHandler.removeCallbacks(clearMessageRunnable)
            mainHandler.postDelayed(clearMessageRunnable, MESSAGE_SHOW_MS)
        }
    }

    private val clearMessageRunnable = Runnable {
        messageView?.visibility = View.GONE
    }

    /** 由任务状态驱动更新按钮外观（任意线程可调用） */
    fun setRunning(running: Boolean) {
        isRunning = running
        val text = context.getString(
            if (running) com.kuaishou.auto.R.string.overlay_stop
            else com.kuaishou.auto.R.string.overlay_start
        )
        val bg = circleDrawable(if (running) RUNNING_COLOR else IDLE_COLOR)
        runOnMain {
            buttonView?.let {
                it.text = text
                it.background = bg
            }
            if (!running) applyProgress(null)
        }
    }

    /**
     * 显示当前读到的进度；传 null 表示无读数（清空）。
     *
     * 这里直接反映 ProgressDetector 的输出，方便在真机上对照画面确认识别是否准确。
     */
    fun setProgress(progress: Float?) {
        runOnMain { applyProgress(progress) }
    }

    private fun applyProgress(progress: Float?) {
        val tv = progressView ?: return
        if (progress == null) {
            tv.visibility = View.GONE
            tv.text = ""
            return
        }
        val pct = (progress * 100f).roundToInt().coerceIn(0, 100)
        tv.text = "$pct%"
        tv.visibility = View.VISIBLE
    }

    /** 已在主线程就直接执行，否则投递过去 */
    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    private fun circleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((2 * context.resources.displayMetrics.density).roundToInt(), 0x55FFFFFF)
        }
    }

    private fun attachDragHandler(container: View, button: TextView) {
        val dp = context.resources.displayMetrics.density
        val slop = CLICK_SLOP_DP * dp

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false

        // 触摸在容器上（含按钮与进度文本），因此按钮本身不需要单独的点击监听，
        // 由这里区分「拖动」与「点击」，避免两处都回调导致状态翻两次。
        container.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) dragged = true
                    if (dragged) {
                        // 挖孔屏兼容：夹紧到安全区内，别让按钮被状态栏/挖孔裁掉。
                        // 实测：不夹紧时按钮能被拖到 y=0，在挖孔屏上后半截被摄像头孔盖住。
                        val dm = context.resources.displayMetrics
                        val w = container.width
                        val h = container.height
                        val topLimit = topSafeInsetPx()
                        val maxX = (dm.widthPixels - w).coerceAtLeast(0)
                        val maxY = (dm.heightPixels - h).coerceAtLeast(0)
                        params.x = (startX + dx.toInt()).coerceIn(0, maxX)
                        params.y = (startY + dy.toInt()).coerceIn(topLimit, maxY)
                        try {
                            windowManager.updateViewLayout(container, params)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged) onToggleRequested()
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 用户点了一次悬浮窗按钮：先在本地立即给出视觉反馈，
     * 再把请求交给上层。服务侧真实状态回来时会用 [setRunning] 覆盖，
     * 因此不存在"点了不变"的窗口期。
     */
    private fun onToggleRequested() {
        val next = !isRunning
        isRunning = next
        setRunning(next)
        onToggle(next)
    }
}
