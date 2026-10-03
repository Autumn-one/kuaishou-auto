package com.kuaishou.auto.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * 用 PackageInstaller 会话安装已下载好的 APK。
 *
 * 关键约束（均来自 AOSP 源码与实测，改动前请先看 UPDATE_PLAN.md）：
 *
 * 1. statusReceiver 用的 PendingIntent 必须是 FLAG_MUTABLE。
 *    AOSP PackageInstaller.java 明确：targetSdk >= 35 时传 immutable 的
 *    PendingIntent 会抛 IllegalArgumentException。本项目 targetSdk 是 36。
 *
 * 2. 必须处理 STATUS_PENDING_USER_ACTION。
 *    AOSP PackageInstallerSession.java：拿不到
 *    UPDATE_PACKAGES_WITHOUT_USER_ACTION 权限时必定回落到「需要用户确认」，
 *    此时回调里带着系统确认框的 Intent，必须把它 startActivity 出来，
 *    否则用户点完「安装」后会永远卡住没有任何反应。
 *
 * 3. 同一镜像 60 秒内重复静默安装会被系统节流并回落到确认框，
 *    所以确认框是常态而不是异常路径。
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"

    /** 安装结果广播的 action，由 UpdateResultReceiver 接收 */
    const val ACTION_INSTALL_RESULT = "com.kuaishou.auto.INSTALL_RESULT"

    /**
     * 发起安装。
     *
     * 会先做三重校验（包名 / 版本号 / 签名同源），任何一项不过就直接拒绝，
     * 避免把系统安装器当成校验器用 —— 那样只会得到一句看不懂的系统报错。
     */
    fun install(context: Context, apk: File, expectedVersionCode: Long): Boolean {
        val pm = context.packageManager
        val check = verify(context, apk, expectedVersionCode)
        if (check != null) {
            Log.w(TAG, "拒绝安装：$check")
            lastError = check
            return false
        }

        return try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                // 尽力静默；拿不到静默权限时系统会回落到确认框
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                setInstallReason(PackageManager.INSTALL_REASON_USER)
            }

            val installer = pm.packageInstaller
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    FileInputStream(apk).use { input ->
                        input.copyTo(out, 64 * 1024)
                    }
                    session.fsync(out)
                }
                session.commit(createResultSender(context))
            }
            Log.i(TAG, "已提交安装会话 #$sessionId")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "发起安装失败", t)
            lastError = "发起安装失败：${t.message ?: t.javaClass.simpleName}"
            false
        }
    }

    /** 最近一次失败原因，供界面展示（放静态是为了让广播接收器也能读到）。 */
    @Volatile
    var lastError: String? = null

    private fun createResultSender(context: Context): android.content.IntentSender {
        val intent = Intent(context, UpdateResultReceiver::class.java).apply {
            action = ACTION_INSTALL_RESULT
        }
        // FLAG_MUTABLE 是硬性要求，见类注释第 1 条
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return pi.intentSender
    }

    /**
     * 安装前校验，返回 null 表示通过，否则返回给用户看的错误原因。
     *
     * 本地签名是运行时读的，不需要在构建期把证书指纹写进代码 ——
     * 这样换密钥时不会出现「代码里的指纹和实际签名对不上」的隐蔽故障。
     */
    fun verify(context: Context, apk: File, expectedVersionCode: Long): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return "无法解析下载的安装包"

        if (archive.packageName != context.packageName) {
            return "安装包不是本应用（包名 ${archive.packageName}）"
        }
        if (archive.longVersionCode != expectedVersionCode) {
            return "安装包版本号不符（${archive.longVersionCode} != $expectedVersionCode）"
        }

        val remote = signers(archive)
        val local = try {
            @Suppress("DEPRECATION")
            signers(pm.getPackageInfo(context.packageName, flags))
        } catch (t: Throwable) {
            return "读取本机签名失败"
        }
        if (remote.isEmpty() || local.isEmpty()) return "无法读取签名信息"
        if (remote.intersect(local).isEmpty()) {
            return "签名不一致：该安装包不是本应用的官方构建"
        }
        return null
    }

    private fun signers(info: android.content.pm.PackageInfo): Set<String> {
        val out = HashSet<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo ?: return out
            val arr = if (si.hasMultipleSigners()) {
                si.apkContentsSigners
            } else {
                si.signingCertificateHistory
            }
            arr?.forEach { out.add(it.toCharsString()) }
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.forEach { out.add(it.toCharsString()) }
        }
        return out
    }
}
