package com.kuaishou.auto.service

import android.graphics.Bitmap
import android.util.Log

/**
 * 取帧相关的共享诊断工具。
 *
 * 两种取帧方式（MediaProjection / 无障碍截图）的存档与自检逻辑完全一样，
 * 抽到这里避免两边实现漂移——诊断输出不一致会让排查变难。
 */
object FrameDump {

    private const val TAG = "FrameDump"

    /**
     * 把一帧存到 `Android/data/<pkg>/files/capture-dump.png`（覆盖写）。
     *
     * @return 绝对路径；失败返回错误描述
     */
    fun dump(bmp: Bitmap): String {
        return try {
            val dir = Paths.externalFilesDir()
            if (dir == null) {
                "无外部文件目录"
            } else {
                val f = java.io.File(dir, "capture-dump.png")
                java.io.FileOutputStream(f).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                f.absolutePath
            }
        } catch (t: Throwable) {
            "保存失败:${t.message}"
        }
    }

    /**
     * 自检：报告内容区与底部区域里的"横向强变化行"。
     *
     * 底部强变化行是进度条的候选特征，用来快速判断
     * "这一帧里看不看得见进度条"。
     */
    fun selfTest(bmp: Bitmap, contentTop: Int, contentBottom: Int): String {
        val w = bmp.width
        val h = bmp.height
        val hits = StringBuilder()
        var y = (h * 0.85f).toInt()
        while (y < h) {
            var cnt = 0
            var x = 0
            while (x < w) {
                val a = bmp.getPixel(x, y)
                val b = bmp.getPixel(x, (y - 5).coerceAtLeast(0))
                val dr = Math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF))
                val dg = Math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))
                val db = Math.abs((a and 0xFF) - (b and 0xFF))
                if ((dr + dg + db) / 3 > 25) cnt++
                x += 6
            }
            if (cnt > 40) hits.append("y$y:$cnt ")
            y += 10
        }
        return "内容区$contentTop..$contentBottom 底部强变化行[${hits.toString().trim()}]"
    }

    /**
     * 外部私有目录。
     *
     * 走 `AutoScrollService.instance` 拿 context，因为取帧的调用方在服务里，
     * 而 FrameDump 是纯工具对象、不持有 context。
     */
    private object Paths {
        fun externalFilesDir(): java.io.File? = try {
            AutoScrollService.instance?.getExternalFilesDir(null)
        } catch (t: Throwable) {
            Log.w(TAG, "取外部目录失败", t)
            null
        }
    }
}
