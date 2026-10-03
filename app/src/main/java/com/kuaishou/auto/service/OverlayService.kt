package com.kuaishou.auto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kuaishou.auto.AppSettings
import com.kuaishou.auto.MainActivity
import com.kuaishou.auto.R
import com.kuaishou.auto.task.ScrollTask
import com.kuaishou.auto.ui.FloatingWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 前台服务：托管悬浮窗与自动上滑任务，保证后台不被回收。
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "kuaishou_auto"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var instance: OverlayService? = null
            private set

        const val ACTION_SHOW = "com.kuaishou.auto.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.kuaishou.auto.HIDE_OVERLAY"
        const val ACTION_TOGGLE_OVERLAY = "com.kuaishou.auto.TOGGLE_OVERLAY"
        const val ACTION_STOP_TASK = "com.kuaishou.auto.STOP_TASK"
        const val ACTION_TOGGLE = "com.kuaishou.auto.TOGGLE_TASK"
        const val ACTION_EXIT = "com.kuaishou.auto.EXIT"

        /**
         * 悬浮窗当前是否显示（供主界面切换按钮文案）。
         *
         * 由 [OverlayService] 在每次增删窗口后写入，是唯一的状态来源：
         * 主界面进程与前台服务可能不是同一个进程实例，
         * 因此主界面的按钮不能只依赖 [instance]（可能为 null）。
         */
        @Volatile
        var overlayShown: Boolean = false
            private set

        /** 服务写入悬浮窗真实状态 */
        private fun publishOverlayShown(shown: Boolean) {
            overlayShown = shown
            publishState()
        }

        /**
         * 状态变化监听（主界面用）。
         *
         * 主界面与前台服务之间没有绑定关系，靠这个回调把"显示/隐藏、
         * 运行中、当前进度"这些真实状态推给已打开的界面，
         * 按钮文案才能总是与实际窗口一致。
         */
        private val listeners =
            java.util.concurrent.CopyOnWriteArrayList<(Boolean, Boolean, Float?) -> Unit>()

        /** 注册监听并立即回调一次当前状态 */
        fun addListener(l: (Boolean, Boolean, Float?) -> Unit) {
            listeners.add(l)
            l(overlayShown, instance?.isTaskRunning == true, lastProgress)
        }

        fun removeListener(l: (Boolean, Boolean, Float?) -> Unit) {
            listeners.remove(l)
        }

        /** 最近一次读到的进度（0~1），null 表示当前没有读数 */
        @Volatile
        private var lastProgress: Float? = null

        private fun publishState() {
            val shown = overlayShown
            val running = instance?.isTaskRunning == true
            val p = lastProgress
            for (l in listeners) {
                try {
                    l(shown, running, p)
                } catch (_: Throwable) {
                }
            }
        }

        private fun publishProgress(p: Float?) {
            lastProgress = p
            publishState()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var floatingWindow: FloatingWindow? = null

    /**
     * 当前使用的取帧方式。
     *
     * 两种实现见 [FrameSource]：MediaProjection 与无障碍截图。
     * 由设置里的 [CaptureMode] 决定，默认自动选择。
     */
    private var frameSource: FrameSource? = null
    private var scrollTask: ScrollTask? = null

    /** 当前 ScrollTask 绑定的取帧源；换了源就要重建任务（见 startTask） */
    private var boundCapture: FrameSource? = null

    /** 当前 ScrollTask 绑定的停留上限（分钟）；设置变了也要重建任务 */
    private var boundWatchMinutes = -1
    private var taskRunning = false
    private var currentStateText = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        // **不要**在这里声明 mediaProjection 类型。
        // onServiceConnected 之前用户还没授权屏幕捕获，而 Android 14+ 规定：
        // 以 mediaProjection 类型调用 startForeground() 必须已持有捕获授权，
        // 否则抛 SecurityException → 服务崩溃 → 进程被杀。
        // 现象就是"点显示悬浮窗程序自动退出、悬浮窗不显示"，
        // 且反复崩溃会让系统停用本应用的无障碍服务（实测踩过）。
        startForegroundCompat(includeProjection = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> ensureOverlay()
            ACTION_HIDE -> hideOverlay()
            ACTION_TOGGLE_OVERLAY -> {
                // 悬浮窗存在就隐藏、不存在就显示。
                // 判据用窗口真实状态（floatingWindow 是否挂着 view），
                // 不用静态布尔值，避免与服务实例不同步。
                if (isOverlayShown) hideOverlay() else ensureOverlay()
            }

            ACTION_STOP_TASK -> stopTask()
            ACTION_EXIT -> {
                exitApp()
                // 服务已自行结束，不需要系统再重启
                return START_NOT_STICKY
            }

            ACTION_TOGGLE -> {
                ensureOverlay()
                toggleTask()
            }

            else -> ensureOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTask()
        floatingWindow?.hide()
        floatingWindow = null
        publishOverlayShown(false)
        frameSource?.stop()
        frameSource = null
        scrollTask = null
        boundCapture = null
        boundWatchMinutes = -1
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    // ---------------- 悬浮窗 ----------------

    fun ensureOverlay() {
        if (isOverlayShown) {
            floatingWindow?.setRunning(taskRunning)
            publishOverlayShown(true)
            updateNotification(if (taskRunning) "运行中" else "悬浮窗已显示")
            return
        }
        // 权限被撤销时请求显示只会留下一个"看不见的窗口"，
        // 直接把状态刷成未显示，并提示用户去授权。
        if (!canDrawOverlay()) {
            Log.w(TAG, "ensureOverlay: 缺少悬浮窗权限")
            floatingWindow?.hide()
            publishOverlayShown(false)
            updateNotification("缺少悬浮窗权限，悬浮窗无法显示")
            return
        }
        val fw = floatingWindow ?: FloatingWindow(this) { running ->
            if (running) startTask() else stopTask()
        }.also { floatingWindow = it }

        fw.show()
        fw.setRunning(taskRunning)
        // 刚 addView 完时视图还没 attach 完成，isShown 可能瞬间为 false；
        // 因此这里先按"已请求显示"发布，等窗口真正挂上后再校正一次真实状态。
        publishOverlayShown(true)
        Log.i(TAG, "ensureOverlay: 已请求显示")
        Handler(Looper.getMainLooper()).postDelayed({
            val f = floatingWindow ?: return@postDelayed
            val shown = f.isShown
            val visible = f.isActuallyVisible()
            publishOverlayShown(shown)
            Log.i(TAG, "ensureOverlay 复核: shown=$shown actuallyVisible=$visible")
            if (shown && !visible) {
                Log.w(TAG, "悬浮窗未能显示：疑似缺少悬浮窗权限")
                updateNotification("悬浮窗未显示，请检查悬浮窗权限")
            }
        }, 800)
    }

    /**
     * 只隐藏悬浮窗：任务与前台服务继续运行。
     * 需要结束任务请用「退出」，或点通知栏的停止。
     */
    fun hideOverlay() {
        floatingWindow?.hide()
        publishOverlayShown(isOverlayShown)
        updateNotification(
            if (taskRunning) "运行中（悬浮窗已隐藏）" else "悬浮窗已隐藏"
        )
        Log.i(TAG, "悬浮窗已隐藏, taskRunning=$taskRunning")
    }

    /** 悬浮窗权限是否仍在（用户可能在系统设置里撤销） */
    private fun canDrawOverlay(): Boolean {
        return try {
            android.provider.Settings.canDrawOverlays(this)
        } catch (_: Throwable) {
            false
        }
    }


    /**
     * 退出：停止任务、移除悬浮窗、撤掉前台通知并结束服务。
     * 这是用户要求的"退出软件"入口。
     */
    fun exitApp() {
        Log.i(TAG, "退出：停止任务并结束前台服务")
        stopTask()
        floatingWindow?.hide()
        floatingWindow = null
        publishOverlayShown(false)
        frameSource?.stop()
        frameSource = null
        scrollTask = null
        boundCapture = null
        boundWatchMinutes = -1
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    fun toggleTask() {
        if (taskRunning) stopTask() else startTask()
    }

    /**
     * 把主界面带到前台并请求屏幕捕获授权。
     *
     * 主界面收到 [MainActivity.CMD_NEED_CAPTURE] 后会立即弹系统授权框，
     * 授权成功后自动开始任务，用户不需要再点一次悬浮窗。
     */
    private fun requestCaptureFromUi() {
        try {
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("cmd", "need_capture")
            }
            startActivity(i)
        } catch (t: Throwable) {
            Log.e(TAG, "拉起主界面失败", t)
        }
    }

    /** 悬浮窗是否真的显示（同时要求权限仍在，避免读到"看不见的窗口"） */
    val isOverlayShown: Boolean
        get() = floatingWindow?.isShown == true && canDrawOverlay()

    // ---------------- 任务控制 ----------------

    private fun startTask() {
        if (taskRunning) return

        if (AutoScrollService.instance == null) {
            Log.w(TAG, "无障碍服务未开启，无法开始")
            currentStateText = "请先开启无障碍服务"
            updateNotification(currentStateText)
            floatingWindow?.setRunning(false)
            // 关键：必须让用户看见"为什么没开始"。
            // 只把按钮颜色打回去，用户看到的就是"点了没反应"（实测反馈）。
            floatingWindow?.showMessage("请先开启无障碍服务")
            return
        }

        val capture = resolveFrameSource()
        if (!capture.isReady) {
            // 当前选定的取帧方式拿不到帧。
            //
            // 分两种情况：
            // - 选的是 MediaProjection：授权是"一次性"的，进程重启/被回收后
            //   必须重新授权，否则拿到的永远是空帧、进度条一个也读不到。
            //   从悬浮窗点「开始」时用户看不到任何提示，所以主动把主界面
            //   拉到前台并直接弹授权框，授权完成后 MainActivity 会自动开始任务。
            // - 选的是无障碍截图：不需要授权；若机型/ROM 不支持，
            //   明确提示用户改设置（AUTO 模式会自动退回 MediaProjection）。
            val mode = AppSettings.getCaptureMode(this)
            Log.w(TAG, "取帧未就绪, mode=$mode，进入授权/回退流程")
            currentStateText = if (mode == CaptureMode.ACCESSIBILITY) {
                "无障碍截图不可用，请在设置里改用其它方式"
            } else {
                "需要屏幕捕获授权"
            }
            updateNotification(currentStateText)
            floatingWindow?.setRunning(false)
            floatingWindow?.showMessage(currentStateText)
            requestCaptureFromUi()
            return
        }

        // 任务实例必须绑定"当前这一次"的取帧源。
        //
        // 实测踩过的坑：切换取帧方式后，旧的 ScrollTask 仍持有上一种方式的
        // 取帧源（那个源此时已经 not ready），于是任务每轮都报
        // "屏幕捕获未就绪，任务中止"，永远不会滑动。
        // 因此只要取帧源换了实例，就把任务实例一起重建。
        //
        // 同理：停留上限是任务启动时读入的，改了设置也必须重建任务，
        // 否则用户改了分钟数却"看起来没生效"。
        val watchMin = AppSettings.getMaxWatchMinutes(this)
        val existing = scrollTask
        val reusable = existing != null &&
            boundCapture === capture &&
            boundWatchMinutes == watchMin
        val task = if (reusable) {
            existing
        } else {
            existing?.stop()
            ScrollTask(capture, { state ->
                currentStateText = state
                updateNotification(state)
            }, { p ->
                floatingWindow?.setProgress(p)
                publishProgress(p)
            }, watchMin).also {
                scrollTask = it
                boundCapture = capture
                boundWatchMinutes = watchMin
            }
        }

        task.start(scope)
        taskRunning = true
        floatingWindow?.setRunning(true)
        // 启动时清空读数：避免沿用上一次运行残留的旧进度
        floatingWindow?.setProgress(null)
        publishProgress(null)
        updateNotification("运行中")
        Log.i(TAG, "任务已启动（取帧方式=$currentCaptureModeLabel）")
    }

    /**
     * 确保"无障碍截图能力"已被确认过。
     *
     * 首次使用前必须真的抓一帧确认：只看版本号不够，
     * 部分 ROM 没开放该能力，会一直返回 null。
     *
     * 这里在**后台线程**做，最多等 [A11Y_PROBE_WAIT_MS]：
     * 本方法会被主线程调用（启动任务时），抓帧最长要 1.5 秒，
     * 直接同步做会让界面卡顿。
     */
    private fun ensureA11yProbed() {
        if (A11yCaptureSupport.isAvailable) return
        A11yCaptureSupport.warmUpAsync()
        // 等待后台确认完成（有上限，不会无限等）
        A11yCaptureSupport.awaitConfirmation(A11Y_PROBE_WAIT_MS)
    }

    /**
     * 无障碍截图能力仍待确认时的等待上限。
     *
     * 确认本身是抓一帧（通常几百毫秒），给足余量但不让界面卡太久。
     */
    private val A11Y_PROBE_WAIT_MS = 2_000L

    /**
     * 按设置选出取帧方式，并返回它当前的实例。
     *
     * 选择逻辑：
     * - [CaptureMode.ACCESSIBILITY]：只用无障碍截图；不可用就返回一个"未就绪"的
     *   实例，让上层提示用户去设置里换（不静默改用另一种，避免"我明明选了它"）
     * - [CaptureMode.MEDIA_PROJECTION]：只用 MediaProjection
     * - [CaptureMode.AUTO]（默认）：优先无障碍截图（免授权弹窗），
     *   它不可用时退回 MediaProjection
     *
     * 已经选定的实例会被复用，避免每次开始任务都重建（MediaProjection 重建
     * 会导致授权失效）。
     */
    private fun resolveFrameSource(): FrameSource {
        val mode = AppSettings.getCaptureMode(this)

        return when (mode) {
            CaptureMode.ACCESSIBILITY -> {
                val a11y = frameSource as? AccessibilityCapture
                    ?: AccessibilityCapture().also { frameSource = it }
                ensureA11yProbed()
                Log.i(TAG, "取帧方式=无障碍截图, isReady=${a11y.isReady}")
                a11y
            }

            CaptureMode.MEDIA_PROJECTION -> {
                val sc = frameSource as? ScreenCapture
                    ?: ScreenCapture(this).also { frameSource = it }
                Log.i(TAG, "取帧方式=MediaProjection, isReady=${sc.isReady}")
                sc
            }

            CaptureMode.AUTO -> {
                // 无障碍截图优先：它不需要授权弹窗，进程重启也不用重新授权。
                val a11y = frameSource as? AccessibilityCapture
                    ?: AccessibilityCapture().also { frameSource = it }
                ensureA11yProbed()
                if (A11yCaptureSupport.isAvailable) {
                    Log.i(TAG, "取帧方式=自动→无障碍截图")
                    a11y
                } else {
                    // 不可用就退回 MediaProjection（用户不必改设置）
                    val sc = ScreenCapture(this).also { frameSource = it }
                    Log.i(
                        TAG,
                        "取帧方式=自动→MediaProjection（无障碍截图不可用）, " +
                            "isReady=${sc.isReady}",
                    )
                    sc
                }
            }
        }
    }

    /** 当前实际在用的取帧方式（供界面显示） */
    val currentCaptureModeLabel: String
        get() = when (frameSource) {
            is AccessibilityCapture -> "无障碍截图"
            is ScreenCapture -> "MediaProjection"
            else -> "未选择"
        }

    fun stopTask() {
        scrollTask?.stop()
        taskRunning = false
        floatingWindow?.setRunning(false)
        publishProgress(null)
        updateNotification("已停止")
        Log.i(TAG, "任务已停止")
    }

    /** 由 MainActivity 授权后注入屏幕捕获 */
    fun attachCapture(resultCode: Int, data: Intent) {
        Log.i(TAG, "attachCapture: resultCode=$resultCode")
        // 现在已持有授权，才可以把前台服务类型升级为含 mediaProjection。
        // Android 14+ 要求 MediaProjection.start() 必须在
        // 已以 mediaProjection 类型运行的前台服务中进行，否则抛 SecurityException。
        startForegroundCompat(includeProjection = true)
        // 无论设置选了什么，授权回调只可能来自 MediaProjection，
        // 因此这里固定使用 ScreenCapture，并把它设为当前取帧方式。
        val capture = frameSource as? ScreenCapture ?: ScreenCapture(this).also { frameSource = it }
        capture.start(resultCode, data)
        Log.i(TAG, "attachCapture 完成, isReady=${capture.isReady}")

        // 自检：确认真的能抓到画面（而非全黑）
        if (capture.isReady) {
            val test = capture.selfTest()
            Log.i(TAG, "捕获自检: $test")
            updateNotification("捕获自检: $test")
            // 3 秒后再检一次（此时用户可能已切到目标应用）
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val t2 = capture.selfTest()
                Log.i(TAG, "捕获自检(3秒后): $t2")
            }, 3000)
        } else {
            updateNotification("屏幕捕获失败")
        }
    }

    val isTaskRunning: Boolean get() = taskRunning

    /**
     * 取帧是否真正可用。
     *
     * **必须按当前设置的模式判定**，不能只看"有没有 MediaProjection 授权"：
     * 选了无障碍截图时那条路根本不需要授权，若仍按授权判定，
     * 任务会被错误地要求去点「录制或投放」弹窗（实测踩过：
     * 设为 ACCESSIBILITY 后启动任务，弹出的却是 MediaProjection 授权框）。
     *
     * 这里不主动做能力确认（那要抓一帧、可能阻塞 1.5s），
     * 能力确认由界面在后台线程做，这里只读已确认的结果。
     */
    val isCaptureReady: Boolean
        get() {
            val mode = AppSettings.getCaptureMode(this)
            if (mode != CaptureMode.MEDIA_PROJECTION) {
                if (A11yCaptureSupport.isAvailable) return true
                // AUTO 模式在无障碍截图不可用时允许退回 MediaProjection
                if (mode == CaptureMode.ACCESSIBILITY) return false
            }
            return frameSource?.isReady == true
        }

    /**
     * 当前模式是否必须走 MediaProjection 授权流程。
     *
     * 界面据此决定"要不要弹授权框"：无障碍截图模式不该弹。
     * 返回 true 表示确实需要用户授权。
     *
     * AUTO 模式下也**不会**在这里同步抓帧去确认能力——本方法会被主线程调用，
     * 而确认最长要等 1.5s，会造成界面卡顿。能力确认由界面在后台线程
     * （`MainActivity.probeA11yIfNeeded`）提前做；这里只读已确认的结果。
     * 尚未确认时保守地按"需要授权"返回，等后台确认完成后界面重新查询即可。
     */
    fun needsProjectionPermission(): Boolean {
        val mode = AppSettings.getCaptureMode(this)
        return when (mode) {
            CaptureMode.MEDIA_PROJECTION -> true
            CaptureMode.ACCESSIBILITY -> false
            CaptureMode.AUTO -> !A11yCaptureSupport.isAvailable
        }
    }

    // ---------------- 前台通知 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "自动上滑运行状态"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggleLabel = if (taskRunning) "停止" else "开始"
        val togglePi = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val exitPi = PendingIntent.getService(
            this, 2,
            Intent(this, OverlayService::class.java).apply { action = ACTION_EXIT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val showPi = PendingIntent.getService(
            this, 3,
            Intent(this, OverlayService::class.java).apply { action = ACTION_SHOW },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pi)
            .addAction(0, toggleLabel, togglePi)
            .addAction(0, "显示悬浮窗", showPi)
            .addAction(0, "退出", exitPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 按系统版本算出可用的前台服务类型。
     *
     * **必须按版本挑位**，不能把新类型的常量直接用在老系统上：
     * `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`(0x40000000) 是 API 34 才有的，
     * 在 Android 13 及更低版本上把这一位传给 `startForeground()` 会抛
     * `IllegalArgumentException: foregroundServiceType ... is not a subset ...`。
     *
     * @return 类型掩码；返回 0 表示应当使用不带类型的旧式调用
     */
    private fun foregroundTypeFor(includeProjection: Boolean): Int {
        var t = 0
        // MEDIA_PROJECTION 从 API 29 起可用；且必须在已授权后声明
        if (includeProjection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        // SPECIAL_USE 从 API 34 起才有
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return t
    }

    /**
     * 启动前台服务。
     *
     * [includeProjection] 只有在**已经拿到屏幕捕获授权**时才可为 true：
     * Android 14+ 规定以 `mediaProjection` 类型调用 `startForeground()`
     * 必须已持有授权，否则抛 `SecurityException`。
     *
     * 因此分两步：
     * - 服务刚创建时（还没授权）只用本机支持的"安全类型"
     * - 用户在 [attachCapture] 里授权成功后，再以含 mediaProjection 的类型重申一次
     *
     * 另加兜底：即便某种 ROM 仍拒绝，也只降级为"通知降级"，
     * 绝不让异常冒出去把服务（进而把进程）打崩——那会导致
     * 悬浮窗点不出来、无障碍服务被系统停用。
     */
    private fun startForegroundCompat(includeProjection: Boolean) {
        val notification = buildNotification(currentStateText.ifEmpty { "待命" })

        val type = foregroundTypeFor(includeProjection)
        if (type == 0) {
            // 老系统：没有可用的类型位，用旧式调用
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
            return
        }

        try {
            startForeground(NOTIFICATION_ID, notification, type)
        } catch (t: Throwable) {
            // 常见原因：声明了 mediaProjection 但还没授权（SecurityException）、
            // 或该 ROM 拒绝某个类型位。
            Log.e(TAG, "startForeground 失败（类型=$type），尝试降级", t)
            val fallback = foregroundTypeFor(includeProjection = false)
            try {
                if (fallback != 0) {
                    startForeground(NOTIFICATION_ID, notification, fallback)
                } else {
                    @Suppress("DEPRECATION")
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (t2: Throwable) {
                // 连降级都不行：退到不带类型的旧式调用，保证服务能起来。
                // 前台通知可能不显示，但悬浮窗与任务仍可用——可用性优先。
                Log.e(TAG, "startForeground 降级仍失败，改用无类型调用", t2)
                try {
                    @Suppress("DEPRECATION")
                    startForeground(NOTIFICATION_ID, notification)
                } catch (t3: Throwable) {
                    Log.e(TAG, "startForeground 彻底失败", t3)
                }
            }
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
