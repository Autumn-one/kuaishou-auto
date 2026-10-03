package com.kuaishou.auto

import android.content.Context
import com.kuaishou.auto.service.CaptureMode

/**
 * 应用设置（用 SharedPreferences 持久化）。
 *
 * 目前只有一项：取帧方式。做成可选项而不是直接替换，是因为两种方式
 * 各有明确的优劣场景（见 [CaptureMode]），用户应当能自己选：
 *
 * - 不想每次启动都点一次「录制或投放」授权 → 选无障碍截图
 * - 机型不支持无障碍截图、或想避开系统 3 张/秒的限流 → 用 MediaProjection
 *
 * 默认 [CaptureMode.AUTO]：能用无障碍截图就用它，否则自动退回，
 * 这样"不想要弹窗"的默认体验成立，同时不会在旧机型上直接不可用。
 */
object AppSettings {

    private const val PREF = "kuaishou_auto_prefs"
    private const val KEY_CAPTURE_MODE = "capture_mode"
    private const val KEY_MAX_WATCH_MIN = "max_watch_minutes"

    /** 单条视频停留上限的默认值（分钟）。用户要求默认 5 分钟。 */
    const val DEFAULT_MAX_WATCH_MIN = 5

    /** 设置页允许的取值区间（分钟） */
    const val MIN_MAX_WATCH_MIN = 1
    const val MAX_MAX_WATCH_MIN = 60

    /**
     * 单条视频的停留上限（分钟）。
     *
     * 到达上限后上滑，避免"进度一直缓慢推进但永不结束"的页面把任务卡住。
     * **实际生效值带 ±[WATCH_JITTER_RATIO] 抖动**（见 ScrollTask），
     * 而不是精确等于这里设的分钟数——固定的"整 5 分钟"边界太规律。
     */
    fun getMaxWatchMinutes(context: Context): Int {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val v = sp.getInt(KEY_MAX_WATCH_MIN, DEFAULT_MAX_WATCH_MIN)
        return v.coerceIn(MIN_MAX_WATCH_MIN, MAX_MAX_WATCH_MIN)
    }

    fun setMaxWatchMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MAX_WATCH_MIN, minutes.coerceIn(MIN_MAX_WATCH_MIN, MAX_MAX_WATCH_MIN))
            .apply()
    }

    fun getCaptureMode(context: Context): CaptureMode {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return CaptureMode.fromKey(sp.getString(KEY_CAPTURE_MODE, null))
    }

    fun setCaptureMode(context: Context, mode: CaptureMode) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CAPTURE_MODE, mode.name)
            .apply()
    }

    /**
     * 页面上限的抖动比例（±20%）。
     *
     * 用户要求："这个五分钟也要有上下时间抖动，不要刚刚好5分钟"。
     * 取 20%：5 分钟上限的实际区间为 4~6 分钟，
     * 既能避免固定边界，又不会让用户设的值失去意义。
     */
    const val WATCH_JITTER_RATIO = 0.20f
}
