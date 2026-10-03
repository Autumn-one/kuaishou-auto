package com.kuaishou.auto

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kuaishou.auto.service.A11yCaptureSupport
import com.kuaishou.auto.service.AutoScrollService
import com.kuaishou.auto.service.CaptureMode
import com.kuaishou.auto.service.OverlayService
import com.kuaishou.auto.service.ScreenCapture
import com.kuaishou.auto.task.ScrollTask
import com.kuaishou.auto.update.ReleaseInfo
import com.kuaishou.auto.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 主界面：检查三项必需权限，并提供开启入口。
 *
 * 1. 悬浮窗权限（SYSTEM_ALERT_WINDOW）
 * 2. 无障碍服务（AutoScrollService）
 * 3. 屏幕捕获授权（MediaProjection，用于读取进度条像素）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var overlayStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var captureStatus: TextView
    private lateinit var toggleOverlayButton: Button
    private lateinit var overlayHint: TextView
    private lateinit var captureModeGroup: android.widget.RadioGroup
    private lateinit var captureModeHint: TextView
    private lateinit var watchEdit: android.widget.EditText
    private lateinit var watchHint: TextView
    private lateinit var captureContent: View
    private lateinit var captureExpandIcon: TextView
    private lateinit var captureSummary: TextView
    private lateinit var captureHeader: View

    // ---------------- 更新 ----------------
    private lateinit var updateStateText: android.widget.TextView
    private lateinit var updateProgress: android.widget.ProgressBar
    private lateinit var checkUpdateButton: Button
    private lateinit var doUpdateButton: Button
    private lateinit var currentVersionText: android.widget.TextView
    private lateinit var updateNotesText: android.widget.TextView

    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingRelease: ReleaseInfo? = null
    private var readyApk: File? = null

    /** 服务状态回调；null 表示当前未注册 */
    private var stateListener: ((Boolean, Boolean, Float?) -> Unit)? = null

    /** 由 "cmd=start" 触发的授权流程，授权成功后在 onResume 里自动开始任务 */
    private var autoStartAfterCapture = false

    private val captureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        android.util.Log.i("MainActivity", "捕获授权回调: resultCode=${result.resultCode}, data=${data != null}")
        if (result.resultCode == RESULT_OK && data != null) {
            val svc = OverlayService.instance
            if (svc != null) {
                svc.attachCapture(result.resultCode, data)
            } else {
                // 服务未起来时先缓存，服务启动后注入
                pendingCapture = result.resultCode to data
            }
            Toast.makeText(this, "屏幕捕获已授权", Toast.LENGTH_SHORT).show()
        } else {
            autoStartAfterCapture = false
            Toast.makeText(this, "屏幕捕获授权被拒绝，进度检测无法工作", Toast.LENGTH_LONG).show()
        }
        refreshStatus()
    }

    /**
     * 挖孔屏/状态栏兼容。
     *
     * 根因：`targetSdk = 36`（Android 15+）起系统**强制 edge-to-edge**，
     * 应用内容会绘制到状态栏与挖孔（display cutout）下方，
     * 旧机型上通过 `fitsSystemWindows` 生效的行为不再起作用。
     *
     * 实测证据（本机 1080x2400，截图像素分析）：标题文字从 **y=47** 开始，
     * 而状态栏高度约 66px —— 标题上半部分被状态栏压住，
     * 在挖孔屏上还会被摄像头孔进一步遮挡。
     *
     * 修法：把系统栏与挖孔的安全区作为 padding 加到根布局上，
     * 而不是去关掉 edge-to-edge（后者在 targetSdk 36 上已无法完全生效，
     * 且会与系统的全面屏手势区冲突）。这样内容始终落在安全区内，
     * 同时保留 edge-to-edge 的视觉（状态栏区域仍由背景色填充）。
     */
    private fun applyCutoutInsets() {
        val root = findViewById<View>(R.id.root_scroll) ?: return
        val base = intArrayOf(
            root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom,
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            applyInsetsOnce(v, insets, base)
            insets
        }

        // 主动同步取一次并立刻应用。
        //
        // 实测踩过：只注册监听器 + requestApplyInsets 时，targetSdk 36 上
        // 首次分发可能早于监听器注册（dumpsys 显示 insetsChanged=false），
        // 结果 padding 一直是 0、标题仍被状态栏压住。
        val now = ViewCompat.getRootWindowInsets(root)
        if (now != null) {
            applyInsetsOnce(root, now, base)
        } else {
            ViewCompat.requestApplyInsets(root)
        }

        // 首次 layout 后再校正一次：部分 ROM 要等窗口 attach 完成才有 insets。
        root.post {
            val again = ViewCompat.getRootWindowInsets(root)
            if (again != null) applyInsetsOnce(root, again, base)
        }
    }

    /** 把系统栏 + 挖孔的安全区叠加到基础 padding 上（幂等） */
    private fun applyInsetsOnce(
        v: View,
        insets: WindowInsetsCompat,
        base: IntArray,
    ) {
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout(),
        )
        val left = base[0] + bars.left
        val top = base[1] + bars.top
        val right = base[2] + bars.right
        val bottom = base[3] + bars.bottom
        if (v.paddingLeft != left || v.paddingTop != top ||
            v.paddingRight != right || v.paddingBottom != bottom
        ) {
            v.setPadding(left, top, right, bottom)
            Log.i(
                "MainActivity",
                "已应用安全区 padding: top=${bars.top} bottom=${bars.bottom} " +
                    "left=${bars.left} right=${bars.right}",
            )
        }
    }

    private var pendingCapture: Pair<Int, Intent>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyCutoutInsets()

        overlayStatus = findViewById(R.id.status_overlay)
        accessibilityStatus = findViewById(R.id.status_accessibility)
        captureStatus = findViewById(R.id.status_capture)
        toggleOverlayButton = findViewById(R.id.btn_show_overlay)
        overlayHint = findViewById(R.id.hint_overlay_state)
        captureModeGroup = findViewById(R.id.group_capture_mode)
        captureModeHint = findViewById(R.id.hint_capture_current)
        watchEdit = findViewById(R.id.edit_watch_minutes)
        watchHint = findViewById(R.id.hint_watch_current)
        captureContent = findViewById(R.id.content_capture_mode)
        captureExpandIcon = findViewById(R.id.icon_capture_expand)
        captureSummary = findViewById(R.id.hint_capture_summary)
        captureHeader = findViewById(R.id.header_capture_mode)

        setupCaptureModeSetting()
        setupWatchLimitSetting()

        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                openOverlaySettings()
            } else {
                requestOverlay()
            }
        }

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            openAccessibilitySettings()
        }

        findViewById<Button>(R.id.btn_capture).setOnClickListener {
            requestCapture()
        }

        toggleOverlayButton.setOnClickListener {
            toggleOverlay()
        }

        findViewById<Button>(R.id.btn_exit_app).setOnClickListener {
            exitApp()
        }

        setupUpdateSection()

        handleControlIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleControlIntent(intent)
    }

    /**
     * 支持从外部（adb / 其它应用）用命令行控制任务：
     * adb shell am start -n com.kuaishou.auto/.MainActivity --es cmd start
     * adb shell am start -n com.kuaishou.auto/.MainActivity --es cmd stop
     */
    private fun handleControlIntent(intent: Intent?) {
        // 诊断开关：--es dbg 1 让任务循环把节点树文本打进日志。
        // 用途：`uiautomator dump` 在快手视频页会因画面持续播放而报
        // "could not get idle state"，确认页面特征只能靠应用自己的节点树。
        ScrollTask.debugTexts = intent?.getStringExtra("dbg") == "1"
        // 同一开关同时打开检测器的"无进度条逐行剖面"诊断，
        // 用于排查"进度条可见却判为无进度条"。
        com.kuaishou.auto.detector.ProgressDetector.diagnose = ScrollTask.debugTexts
        // 故障注入：跳过任务循环的前台检查，用于验证服务层闸门独立有效
        ScrollTask.skipForegroundCheckForTest =
            intent?.getStringExtra("skipfg") == "1"
        when (intent?.getStringExtra("cmd")) {
            "start" -> {
                showOverlay()
                // 等服务起来后：先注入捕获授权，再启动任务
                window.decorView.postDelayed({
                    val svc = OverlayService.instance
                    if (svc == null) return@postDelayed
                    pendingCapture?.let {
                        svc.attachCapture(it.first, it.second)
                        pendingCapture = null
                    }
                    if (!svc.isCaptureReady) {
                        // 还没有有效授权/能力，按当前模式决定是否弹授权框：
                        // 选了无障碍截图时**不该**弹 MediaProjection 的弹窗。
                        autoStartAfterCapture = true
                        if (svc.needsProjectionPermission()) {
                            requestCapture()
                        } else {
                            // 不需要授权：直接开始（能力确认已在设置里做过）
                            autoStartAfterCapture = false
                            svc.toggleTask()
                        }
                        return@postDelayed
                    }
                    if (!svc.isTaskRunning) svc.toggleTask()
                }, 1000)
            }

            "stop" -> {
                OverlayService.instance?.stopTask()
                    ?: launchOverlayService(OverlayService.ACTION_STOP_TASK)
            }

            // 悬浮窗点「开始」时若还没有屏幕捕获授权，服务会把界面带到这里，
            // 由界面弹系统授权框；授权成功后 onResume 里自动开始任务。
            CMD_NEED_CAPTURE -> {
                autoStartAfterCapture = true
                applyPendingCapture()
                if (OverlayService.instance?.isCaptureReady == true) {
                    autoStartAfterCapture = false
                    launchOverlayService(OverlayService.ACTION_TOGGLE)
                } else {
                    window.decorView.postDelayed({ requestCaptureIfNeeded() }, 600)
                }
            }
        }
    }

    /** 还没拿到有效授权才弹框，避免重复弹出 */
    private fun requestCaptureIfNeeded() {
        val svc = OverlayService.instance
        if (svc?.isCaptureReady == true) {
            autoStartAfterCapture = false
            launchOverlayService(OverlayService.ACTION_TOGGLE)
            return
        }
        // 无障碍截图模式不需要 MediaProjection 授权，别弹那个框
        if (svc != null && !svc.needsProjectionPermission()) {
            autoStartAfterCapture = false
            launchOverlayService(OverlayService.ACTION_TOGGLE)
            return
        }
        requestCapture()
    }

    override fun onResume() {
        super.onResume()
        registerStateListener()
        refreshStatus()
        applyPendingCapture()
        // 若刚完成"启动任务"的授权流程，这里把任务真正跑起来
        if (autoStartAfterCapture) {
            val svc = OverlayService.instance
            if (svc != null && svc.isCaptureReady) {
                autoStartAfterCapture = false
                if (!svc.isTaskRunning) svc.toggleTask()
            }
        }
    }

    override fun onPause() {
        unregisterStateListener()
        super.onPause()
    }

    override fun onDestroy() {
        updateScope.cancel()
        UpdateManager.attach(null)
        super.onDestroy()
    }

    /**
     * 订阅前台服务的真实状态。
     *
     * 悬浮窗按钮的文案必须反映"窗口是否真的存在"：
     * 只读 [OverlayService.overlayShown] 会在服务被系统回收（主界面停在后台太久）
     * 时读到过期值，用户点一下就变成隐藏一个不存在的窗口。
     * 服务每次增删窗口都会回调这里，保证文案与窗口同步。
     */
    private fun registerStateListener() {
        if (stateListener != null) return
        val l: (Boolean, Boolean, Float?) -> Unit = { shown, running, progress ->
            runOnUiThread { applyServiceState(shown, running, progress) }
        }
        stateListener = l
        OverlayService.addListener(l)
    }

    private fun unregisterStateListener() {
        stateListener?.let { OverlayService.removeListener(it) }
        stateListener = null
    }

    /** 用服务状态刷新按钮文案与提示 */
    private fun applyServiceState(shown: Boolean, running: Boolean, progress: Float?) {
        toggleOverlayButton.setText(
            if (shown) R.string.btn_hide_overlay else R.string.btn_show_overlay
        )
        val text = when {
            !shown && running -> getString(R.string.overlay_state_hidden_running)
            !shown -> getString(R.string.overlay_state_hidden)
            running && progress != null ->
                getString(R.string.overlay_state_running_progress, (progress * 100f).toInt())
            running -> getString(R.string.overlay_state_running)
            else -> getString(R.string.overlay_state_shown)
        }
        overlayHint.text = text
    }

    private fun applyPendingCapture() {
        val pending = pendingCapture ?: return
        val svc = OverlayService.instance ?: return  // 服务未就绪则保留，等下次再试
        svc.attachCapture(pending.first, pending.second)
        pendingCapture = null
    }

    // ---------------- 设置：取帧方式 ----------------

    /**
     * 取帧方式做成用户可选项。
     *
     * 为什么不直接换成无障碍截图：两种方式各有明确的优劣场景，
     * 且部分机型/ROM 没开放无障碍截图能力。可选项 + 自动回退，
     * 比强行二选一更稳。
     */
    private fun setupCaptureModeSetting() {
        val current = AppSettings.getCaptureMode(this)
        val id = when (current) {
            CaptureMode.AUTO -> R.id.mode_auto
            CaptureMode.ACCESSIBILITY -> R.id.mode_accessibility
            CaptureMode.MEDIA_PROJECTION -> R.id.mode_projection
        }
        captureModeGroup.check(id)
        showCaptureModeLabel(current)
        setupCaptureModeCollapse()

        captureModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.mode_accessibility -> CaptureMode.ACCESSIBILITY
                R.id.mode_projection -> CaptureMode.MEDIA_PROJECTION
                else -> CaptureMode.AUTO
            }
            AppSettings.setCaptureMode(this, mode)
            showCaptureModeLabel(mode)

            // 无障碍截图这条路要重新确认能力（换了设置就该重判一次）
            if (mode != CaptureMode.MEDIA_PROJECTION) {
                A11yCaptureSupport.reset()
                window.decorView.postDelayed({ probeA11yIfNeeded(mode) }, 300)
            }
            Toast.makeText(
                this,
                getString(R.string.toast_capture_mode_saved, captureModeLabel(mode)),
                Toast.LENGTH_SHORT,
            ).show()
        }

        // 进去时也确认一次，让"当前实际使用"显示真实结果
        window.decorView.postDelayed({ probeA11yIfNeeded(current) }, 400)
    }

    /**
     * 取帧方式默认折叠（用户要求：默认收起、放在页面最下方）。
     *
     * 折叠状态下仍显示"当前用的是什么"，因此不展开也能确认状态；
     * 展开状态**不持久化**——每次进设置页都从收起开始，
     * 避免上次展开后下次进来内容突然很长。
     */
    private fun setupCaptureModeCollapse() {
        applyCaptureCollapsed(true)
        captureHeader.setOnClickListener {
            applyCaptureCollapsed(captureContent.visibility == View.VISIBLE)
        }
    }

    private fun applyCaptureCollapsed(collapsed: Boolean) {
        captureContent.visibility = if (collapsed) View.GONE else View.VISIBLE
        captureExpandIcon.text = getString(
            if (collapsed) R.string.expand_arrow_collapsed
            else R.string.expand_arrow_expanded,
        )
    }

    /**
     * 单条视频的停留上限（分钟）做成可设置项。
     *
     * 用户要求：默认 5 分钟，可在设置页更改，且**不要刚好 5 分钟**——
     * 实际生效值带 ±[AppSettings.WATCH_JITTER_RATIO] 抖动，由 ScrollTask
     * 在每页开始时抽取。这里只负责读写设置值并显示实际区间。
     */
    private fun setupWatchLimitSetting() {
        watchEdit.setText(AppSettings.getMaxWatchMinutes(this).toString())
        showWatchLimitHint(AppSettings.getMaxWatchMinutes(this))

        findViewById<Button>(R.id.btn_watch_minus).setOnClickListener {
            applyWatchMinutes(AppSettings.getMaxWatchMinutes(this) - 1)
        }
        findViewById<Button>(R.id.btn_watch_plus).setOnClickListener {
            applyWatchMinutes(AppSettings.getMaxWatchMinutes(this) + 1)
        }

        // 手动输入：失焦或回车时落地。
        // 只在输入完整时才保存，避免"删空再输入"的中间态被当成 0。
        watchEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyWatchMinutes(parseWatchInput())
        }
        watchEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                applyWatchMinutes(parseWatchInput())
                watchEdit.clearFocus()
                true
            } else {
                false
            }
        }
    }

    /** 读取输入框里的分钟数；为空或非法时退回当前设置值 */
    private fun parseWatchInput(): Int {
        val v = watchEdit.text?.toString()?.trim()?.toIntOrNull()
            ?: AppSettings.getMaxWatchMinutes(this)
        return v
    }

    /** 保存分钟数并同步界面（含把越界输入夹回合法区间） */
    private fun applyWatchMinutes(raw: Int) {
        val v = raw.coerceIn(AppSettings.MIN_MAX_WATCH_MIN, AppSettings.MAX_MAX_WATCH_MIN)
        AppSettings.setMaxWatchMinutes(this, v)
        val shown = v.toString()
        if (watchEdit.text?.toString() != shown) watchEdit.setText(shown)
        showWatchLimitHint(v)
        Toast.makeText(
            this,
            getString(R.string.toast_max_watch_saved, v),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** 显示当前设置值，以及叠加抖动后的**实际**区间 */
    private fun showWatchLimitHint(minutes: Int) {
        val base = minutes.coerceAtLeast(1) * 60_000L
        val jitter = (base * AppSettings.WATCH_JITTER_RATIO).toLong()
        // 四舍五入到分钟，避免 4.8 被截断显示成 4（比实际范围偏大）
        val lo = Math.round((base - jitter).toDouble() / 60_000).coerceAtLeast(0)
        val hi = Math.round((base + jitter).toDouble() / 60_000)
        watchHint.text = getString(R.string.setting_max_watch_current, minutes, lo, hi)
    }

    /** 只显示设置项本身，不去探测能力（探测要抓帧，较慢） */
    private fun showCaptureModeLabel(mode: CaptureMode) {
        val label = getString(
            when (mode) {
                CaptureMode.AUTO -> R.string.capture_mode_auto
                CaptureMode.ACCESSIBILITY -> R.string.capture_mode_accessibility
                CaptureMode.MEDIA_PROJECTION -> R.string.capture_mode_projection
            },
        )
        // 展开后看到的详细状态
        captureModeHint.text = getString(R.string.setting_capture_current, label)
        // 折叠状态下也能看到当前生效值（不必展开）
        captureSummary.text = getString(R.string.setting_capture_summary, label)
    }

    private fun captureModeLabel(mode: CaptureMode): String = getString(
        when (mode) {
            CaptureMode.AUTO -> R.string.capture_mode_auto
            CaptureMode.ACCESSIBILITY -> R.string.capture_mode_accessibility
            CaptureMode.MEDIA_PROJECTION -> R.string.capture_mode_projection
        },
    )

    /**
     * 确认无障碍截图是否真的可用，并把结果写进提示。
     *
     * 只看系统版本不够：部分厂商 ROM 未开放该能力，或用户关掉了；
     * 这里真的抓一帧来确认，避免"设置里选了、实际一直取不到帧"。
     * 抓帧要占一点时间，因此在后台线程做。
     */
    private fun probeA11yIfNeeded(mode: CaptureMode) {
        if (mode == CaptureMode.MEDIA_PROJECTION) {
            captureModeHint.text = getString(
                R.string.setting_capture_current,
                getString(R.string.capture_mode_projection),
            )
            return
        }
        if (!A11yCaptureSupport.apiAvailable) {
            captureModeHint.text = getString(
                R.string.setting_capture_current,
                "系统版本不支持，将用 MediaProjection",
            )
            return
        }
        captureModeHint.text = "正在确认无障碍截图是否可用…"
        Thread {
            val ok = A11yCaptureSupport.confirm()
            runOnUiThread {
                val text = when {
                    ok && mode == CaptureMode.AUTO ->
                        "无障碍截图可用（当前优先使用它）"
                    ok -> "无障碍截图可用"
                    mode == CaptureMode.AUTO ->
                        "无障碍截图不可用，已自动改用 MediaProjection"
                    else -> "无障碍截图不可用，请改选其它方式"
                }
                captureModeHint.text = getString(R.string.setting_capture_current, text)
            }
        }.start()
    }

    // ---------------- 版本更新 ----------------

    /**
     * 更新区块。
     *
     * 状态全部由 [UpdateManager] 单向推送，这里只负责渲染，
     * 不在界面里维护"正在下载"之类的状态，避免与服务端真实进度不一致。
     */
    private fun setupUpdateSection() {
        updateStateText = findViewById(R.id.text_update_state)
        updateProgress = findViewById(R.id.progress_update)
        checkUpdateButton = findViewById(R.id.btn_check_update)
        doUpdateButton = findViewById(R.id.btn_do_update)
        currentVersionText = findViewById(R.id.text_current_version)
        updateNotesText = findViewById(R.id.text_update_notes)

        currentVersionText.text = getString(
            R.string.update_current,
            "${UpdateManager.installedVersionName(this)} (${UpdateManager.installedVersionCode(this)})",
        )

        UpdateManager.attach { state -> runOnUiThread { renderUpdateState(state) } }
        renderUpdateState(UpdateManager.state)

        checkUpdateButton.setOnClickListener { startCheck() }

        doUpdateButton.setOnClickListener {
            val info = pendingRelease ?: return@setOnClickListener
            val apk = readyApk
            if (apk != null) {
                doInstall(info, apk)
            } else {
                doDownload(info)
            }
        }
    }

    private fun startCheck() {
        checkUpdateButton.isEnabled = false
        updateScope.launch {
            UpdateManager.check(this@MainActivity)
            checkUpdateButton.isEnabled = true
        }
    }

    private fun doDownload(info: ReleaseInfo) {
        doUpdateButton.isEnabled = false
        updateScope.launch {
            UpdateManager.download(this@MainActivity, info)
            doUpdateButton.isEnabled = true
        }
    }

    private fun doInstall(info: ReleaseInfo, apk: File) {
        // Android 8+ 需要「安装未知应用」权限；没有就先把用户带到设置页
        if (!UpdateManager.canInstallPackages(this)) {
            updateStateText.text = getString(R.string.update_need_permission)
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    )
                )
            } catch (t: Throwable) {
                Toast.makeText(this, "无法打开安装权限设置", Toast.LENGTH_SHORT).show()
            }
            return
        }
        updateStateText.text = getString(R.string.update_installing)
        UpdateManager.install(this, info, apk)
    }

    private fun renderUpdateState(state: UpdateManager.State) {
        when (state) {
            is UpdateManager.State.Idle -> {
                updateProgress.visibility = android.view.View.GONE
                doUpdateButton.visibility = android.view.View.GONE
                updateNotesText.visibility = android.view.View.GONE
                checkUpdateButton.isEnabled = true
            }

            is UpdateManager.State.Checking -> {
                updateProgress.visibility = android.view.View.GONE
                updateStateText.text = getString(R.string.update_checking)
            }

            is UpdateManager.State.UpToDate -> {
                updateProgress.visibility = android.view.View.GONE
                doUpdateButton.visibility = android.view.View.GONE
                updateNotesText.visibility = android.view.View.GONE
                updateStateText.text = state.note
            }

            is UpdateManager.State.Available -> {
                pendingRelease = state.info
                readyApk = null
                updateProgress.visibility = android.view.View.GONE
                updateStateText.text = getString(R.string.update_available, state.info.versionName)
                doUpdateButton.setText(R.string.btn_do_update)
                doUpdateButton.visibility = android.view.View.VISIBLE
                doUpdateButton.isEnabled = true
                if (state.info.notes.isNotEmpty()) {
                    updateNotesText.text =
                        getString(R.string.update_notes_title) + "\n" + state.info.notes
                    updateNotesText.visibility = android.view.View.VISIBLE
                } else {
                    updateNotesText.visibility = android.view.View.GONE
                }
            }

            is UpdateManager.State.Downloading -> {
                updateProgress.visibility = android.view.View.VISIBLE
                updateStateText.text = if (state.total > 0) {
                    val pct = (state.done * 100 / state.total).toInt().coerceIn(0, 100)
                    updateProgress.progress = pct
                    if (state.mirror.isNotEmpty()) {
                        getString(R.string.update_downloading_mirror, pct, state.mirror)
                    } else {
                        getString(R.string.update_downloading, pct)
                    }
                } else {
                    getString(R.string.update_downloading, 0)
                }
            }

            is UpdateManager.State.Verifying -> {
                updateProgress.visibility = android.view.View.GONE
                updateStateText.text = getString(R.string.update_verifying)
            }

            is UpdateManager.State.Ready -> {
                updateProgress.visibility = android.view.View.GONE
                pendingRelease = state.info
                readyApk = state.apk
                updateStateText.text = getString(R.string.update_ready)
                doUpdateButton.setText(R.string.btn_do_update)
                doUpdateButton.visibility = android.view.View.VISIBLE
                doUpdateButton.isEnabled = true
            }

            is UpdateManager.State.Failed -> {
                updateProgress.visibility = android.view.View.GONE
                updateStateText.text = state.message
                doUpdateButton.isEnabled = true
            }
        }
    }

    // ---------------- 权限状态 ----------------

    private fun refreshStatus() {
        val canOverlay = Settings.canDrawOverlays(this)
        overlayStatus.text = getString(
            if (canOverlay) R.string.status_granted else R.string.status_missing
        )
        overlayStatus.setTextColor(if (canOverlay) COLOR_OK else COLOR_MISSING)

        val accOn = isAccessibilityEnabled()
        accessibilityStatus.text = getString(
            if (accOn) R.string.status_granted else R.string.status_missing
        )
        accessibilityStatus.setTextColor(if (accOn) COLOR_OK else COLOR_MISSING)

        // 捕获是否真正就绪：以 ScreenCapture 实例状态为准，而不是"授权过一次"
        val capReady = OverlayService.instance?.isCaptureReady == true || pendingCapture != null
        captureStatus.text = getString(
            if (capReady) R.string.status_granted else R.string.status_missing
        )
        captureStatus.setTextColor(if (capReady) COLOR_OK else COLOR_MISSING)

        // 悬浮窗按钮文案随实际显示状态切换。
        // 以服务实例为准；实例不在时退回服务维护的静态状态
        // （主界面重新打开时实例可能已被回收，但窗口仍在）。
        val overlayShown = OverlayService.instance?.isOverlayShown ?: OverlayService.overlayShown
        applyServiceState(
            overlayShown,
            OverlayService.instance?.isTaskRunning == true,
            null,
        )
    }

    private companion object StatusColors {
        const val COLOR_OK = 0xFF2E7D32.toInt()
        const val COLOR_MISSING = 0xFFE53935.toInt()

        /** 悬浮窗请求主界面弹屏幕捕获授权 */
        const val CMD_NEED_CAPTURE = "need_capture"
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (AutoScrollService.instance != null) return true
        val expected = "$packageName/${AutoScrollService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    // ---------------- 权限请求 ----------------

    private fun openOverlaySettings() {
        launchOverlayService(OverlayService.ACTION_SHOW)
        Toast.makeText(this, "悬浮窗已请求显示", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlay() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "请在列表中找到「${getString(R.string.app_name)}」并开启",
                Toast.LENGTH_LONG,
            ).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    /** 显示/隐藏悬浮窗切换 */
    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            // 没有悬浮窗权限时 addView 会成功但永远画不出来，
            // 用户会以为"点了没反应"，这里直接引导去授权。
            Toast.makeText(this, getString(R.string.toast_need_overlay_perm), Toast.LENGTH_LONG).show()
            requestOverlay()
            return
        }
        // 交给服务自己判断：窗口在就隐藏、不在就显示。
        // 主界面不复刻这个判断，避免"本地状态过期"导致点了没反应。
        val wasShown = OverlayService.instance?.isOverlayShown ?: OverlayService.overlayShown
        launchOverlayService(OverlayService.ACTION_TOGGLE_OVERLAY)
        Toast.makeText(
            this,
            getString(if (wasShown) R.string.toast_overlay_hidden else R.string.toast_overlay_shown),
            Toast.LENGTH_SHORT,
        ).show()
        window.decorView.postDelayed({ refreshStatus() }, 400)
    }

    /** 退出：停任务、收悬浮窗、结束后台服务 */
    private fun exitApp() {
        launchOverlayService(OverlayService.ACTION_EXIT)
        Toast.makeText(this, getString(R.string.toast_exited), Toast.LENGTH_SHORT).show()
        window.decorView.postDelayed({ refreshStatus() }, 400)
    }

    private fun showOverlay() {
        launchOverlayService(OverlayService.ACTION_SHOW)
    }

    private fun launchOverlayService(action: String) {
        val intent = Intent(this, OverlayService::class.java).apply { this.action = action }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}