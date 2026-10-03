package com.kuaishou.auto.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * 接收 PackageInstaller 的安装结果，并在系统要求用户确认时把确认框弹出来。
 *
 * 处理 STATUS_PENDING_USER_ACTION 是不可省略的一步：
 * 没有静默安装权限时系统必定走这条路，不弹确认框用户就会觉得「点了没反应」。
 */
class UpdateResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "安装成功")
                // 装完系统会重启本应用进程，这里的代码通常来不及执行完
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.i(TAG, "需要用户确认安装，弹出系统确认框")
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    try {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirm)
                    } catch (t: Throwable) {
                        Log.e(TAG, "无法弹出安装确认框", t)
                    }
                } else {
                    Log.w(TAG, "回调里没有确认 Intent")
                }
            }

            else -> {
                Log.w(TAG, "安装失败 status=$status message=$message")
                UpdateInstaller.lastError = message ?: "安装失败（status=$status）"
            }
        }
    }

    private companion object {
        const val TAG = "UpdateResultReceiver"
    }
}
