package com.kuaishou.auto.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 更新流程编排：检查 → 下载 → 校验 → 安装。
 *
 * 状态通过 [Listener] 单向推给界面，界面只负责渲染，不持有流程状态，
 * 避免出现「界面以为在下载、实际已经失败」这类不一致。
 */
object UpdateManager {

    private const val TAG = "UpdateManager"

    /** 状态机 */
    sealed class State {
        object Idle : State()
        object Checking : State()
        data class UpToDate(val note: String) : State()
        data class Available(val info: ReleaseInfo) : State()
        data class Downloading(val done: Long, val total: Long, val mirror: String) : State()
        object Verifying : State()
        data class Ready(val info: ReleaseInfo, val apk: File) : State()
        data class Failed(val message: String) : State()
    }

    fun interface Listener {
        fun onState(state: State)
    }

    @Volatile
    var state: State = State.Idle
        private set

    private var listener: Listener? = null

    fun attach(l: Listener?) { listener = l }

    private fun emit(s: State) {
        state = s
        listener?.onState(s)
    }

    /** 本机已安装的 versionCode。 */
    fun installedVersionCode(context: Context): Long = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.longVersionCode
    } catch (t: Throwable) {
        Log.w(TAG, "读取本机版本失败", t)
        0L
    }

    fun installedVersionName(context: Context): String = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.versionName ?: "-"
    } catch (t: Throwable) {
        "-"
    }

    /** 已下载好的 APK 存放位置（私有目录，无需任何存储权限）。 */
    private fun apkFile(context: Context, code: Long): File {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        return File(dir, "app-$code.apk")
    }

    /** 检查更新。协程内执行，网络操作不占主线程。 */
    suspend fun check(context: Context): State = withContext(Dispatchers.IO) {
        emit(State.Checking)
        val installed = installedVersionCode(context)
        Log.i(TAG, "检查更新：本机 versionCode=$installed")
        val result = GitHubReleases.fetchLatest(installed)
        val next = when (result) {
            is GitHubReleases.Result.Ok -> State.Available(result.info)
            is GitHubReleases.Result.UpToDate -> State.UpToDate(result.reason)
            is GitHubReleases.Result.Failed -> State.Failed(result.message)
        }
        emit(next)
        next
    }

    /**
     * 下载并校验，成功后进入 [State.Ready]。
     *
     * 下载失败不会抛异常，统一落到 [State.Failed]，由界面决定是否重试。
     */
    suspend fun download(context: Context, info: ReleaseInfo): State = withContext(Dispatchers.IO) {
        val target = apkFile(context, info.versionCode)

        // 已经有下好的成品且体积对得上，直接复用，省一次下载
        if (target.exists() && info.asset.size > 0 && target.length() == info.asset.size) {
            if (UpdateInstaller.verify(context, target, info.versionCode) == null) {
                Log.i(TAG, "复用已下载的安装包")
                val s = State.Ready(info, target)
                emit(s)
                return@withContext s
            }
            target.delete()
        }

        val part = File(target.absolutePath + ".part")
        val downloader = ApkDownloader(
            destFile = part,
            expectedSha256 = info.asset.sha256,
        ) { done, total, mirror ->
            emit(State.Downloading(done, total, mirror))
        }

        when (val r = downloader.download(info.asset.browserDownloadUrl)) {
            is ApkDownloader.Result.Ok -> {
                emit(State.Verifying)
                // 下载层已比对过 digest；这里再用系统包管理器验一次三重条件
                val err = UpdateInstaller.verify(context, r.file, info.versionCode)
                if (err != null) {
                    r.file.delete()
                    val s = State.Failed(err)
                    emit(s)
                    return@withContext s
                }
                if (!r.file.renameTo(target)) {
                    // 改名失败（极少数文件系统限制）时退回直接使用 .part 文件
                    Log.w(TAG, "改名失败，直接使用临时文件")
                    val s = State.Ready(info, r.file)
                    emit(s)
                    return@withContext s
                }
                val s = State.Ready(info, target)
                emit(s)
                s
            }

            is ApkDownloader.Result.Failure -> {
                val s = State.Failed("下载失败：${r.reason}")
                emit(s)
                s
            }
        }
    }

    /** 发起安装。成功提交返回 true；失败原因写入 [UpdateInstaller.lastError]。 */
    fun install(context: Context, info: ReleaseInfo, apk: File): Boolean {
        val ok = UpdateInstaller.install(context, apk, info.versionCode)
        if (ok) {
            emit(State.Idle)
        } else {
            emit(State.Failed(UpdateInstaller.lastError ?: "安装失败"))
        }
        return ok
    }

    /** 是否已获得「安装未知应用」权限（Android 8+ 需要）。 */
    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 清理旧的安装包缓存，保留当前这个。 */
    fun cleanup(context: Context, keep: File?) {
        try {
            File(context.filesDir, "updates").listFiles()?.forEach {
                if (it != keep) it.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "清理缓存失败", t)
        }
    }
}
