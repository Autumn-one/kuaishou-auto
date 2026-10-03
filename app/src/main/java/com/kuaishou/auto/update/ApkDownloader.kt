package com.kuaishou.auto.update

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * APK 下载器：按配置顺序依次尝试镜像，支持断点续传，边下边算 SHA-256。
 *
 * 设计要点（每条都对应真机实测结论）：
 *
 * 1. 镜像依次尝试，每个镜像内先重试若干次再换下一个 ——
 *    实测各镜像速度差异很大（0.47 ~ 2.19 MB/s）且会偶发慢速或中断，
 *    直接失败换源会让本来能成功的下载白费。
 *
 * 2. 只有收到 206 才在已有字节后续写；收到 200 必须清空重下 ——
 *    200 说明服务端忽略了 Range，继续追加会得到「前半段 + 完整文件」的坏包。
 *
 * 3. 换源时沿用当前已落盘字节数作为续传位置，所以换镜像不会从头再来。
 *
 * 4. 摘要不匹配时删除临时文件：坏包留着只会在下次续传时污染结果。
 */
class ApkDownloader(
    private val destFile: File,
    private val expectedSha256: String?,
    private val onProgress: (downloaded: Long, total: Long, mirror: String) -> Unit,
) {

    companion object {
        private const val TAG = "ApkDownloader"
        /** 进度回调节流：UI 不需要每几 KB 刷新一次 */
        private const val PROGRESS_INTERVAL_MS = 200L
    }

    /** 下载结果，带足够信息让调用方决定要不要换源重试。 */
    sealed class Result {
        data class Ok(val file: File, val sha256: String, val mirror: String) : Result()
        data class Failure(val reason: String, val lastMirror: String) : Result()
    }

    private val digest = MessageDigest.getInstance("SHA-256")
    private var lastProgressAt = 0L
    private var lastNotified = -1L

    /**
     * 依次尝试所有镜像，最后兜底直连。
     *
     * 参数 originalUrl 是 GitHub 原始资产地址（browser_download_url）。
     */
    fun download(originalUrl: String): Result {
        val attempts = ArrayList<Pair<String, Boolean>>()
        for (m in UpdateConfig.DOWNLOAD_MIRRORS) attempts.add(m to false)
        attempts.add(UpdateConfig.DIRECT_BASE to true)

        var lastError = "未知错误"
        var lastMirror = ""

        for ((prefix, isDirect) in attempts) {
            val label = if (isDirect) "直连 github.com" else UpdateConfig.mirrorLabel(prefix)
            lastMirror = label
            val url = if (isDirect) originalUrl else UpdateConfig.mirrorUrl(prefix, originalUrl)

            var attempt = 0
            var switchMirror = false
            while (attempt < UpdateConfig.RETRY_PER_MIRROR && !switchMirror) {
                attempt++
                Log.i(TAG, "尝试 $label（第 $attempt 次）")
                when (val r = tryOne(url, label)) {
                    is Result.Ok -> return r
                    is Result.Failure -> {
                        lastError = r.reason
                        Log.w(TAG, "$label 失败：${r.reason}")
                        // 内容级错误（校验不通过 / 不是 APK）重试同一镜像没意义，直接换下一个
                        if (r.reason.contains("校验") || r.reason.contains("不是")) {
                            switchMirror = true
                        } else if (attempt < UpdateConfig.RETRY_PER_MIRROR) {
                            try {
                                Thread.sleep(UpdateConfig.RETRY_BACKOFF_MS.toLong() * attempt)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                switchMirror = true
                            }
                        }
                    }
                }
            }
        }
        return Result.Failure(lastError, lastMirror)
    }

    private fun tryOne(url: String, label: String): Result {
        val existing = if (destFile.exists()) destFile.length() else 0L
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
                readTimeout = UpdateConfig.READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "kuaishou-auto-updater")
                setRequestProperty("Accept", "application/octet-stream")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }

            val code = conn.responseCode
            val append = when (code) {
                206 -> true                 // 服务端接受了 Range，续传
                200 -> false                // 忽略了 Range，必须从头写
                else -> return Result.Failure("HTTP $code", label)
            }

            // 200 表示要重下：先丢弃旧数据，并把摘要一起重置
            if (!append && destFile.exists()) {
                destFile.delete()
                digest.reset()
                lastNotified = -1L
            }

            val contentLength = conn.contentLengthLong.coerceAtLeast(0L)
            val total = if (append) existing + contentLength else contentLength

            // 续传时要把已落盘的部分喂给摘要，才能算出整包摘要
            if (append) feedExistingBytes(destFile)

            var written = if (append) existing else 0L
            conn.inputStream.use { input ->
                RandomAccessFile(destFile, "rw").use { f ->
                    if (!append) f.setLength(0)
                    f.seek(written)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        f.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        notifyProgress(written, total)
                    }
                }
            }

            // 内容不像 APK 说明拿回的是错误页，别当成功
            if (!looksLikeApk(destFile)) {
                destFile.delete()
                return Result.Failure("返回的不是 APK 文件", label)
            }

            if (total > 0 && written < total) {
                return Result.Failure("连接中断（$written/$total）", label)
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) {
                destFile.delete()
                return Result.Failure("摘要校验不通过", label)
            }

            return Result.Ok(destFile, actual, label)
        } catch (t: Throwable) {
            Log.w(TAG, "$label 异常", t)
            return Result.Failure(t.message ?: t.javaClass.simpleName, label)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) { }
        }
    }

    /** 续传时把已落盘字节喂给摘要器，保证最终摘要覆盖整包。 */
    private fun feedExistingBytes(file: File) {
        digest.reset()
        RandomAccessFile(file, "r").use { f ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = f.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
    }

    private fun notifyProgress(done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastProgressAt < PROGRESS_INTERVAL_MS && done != total) return
        if (done == lastNotified) return
        lastProgressAt = now
        lastNotified = done
        onProgress(done, total, "")
    }

    /**
     * ZIP 魔数检查。
     *
     * 某些镜像失败时会返回一段 HTML 错误页且状态码仍是 200
     * （实测 proxy.gitwarp.top 就是这种形态），只靠状态码看不出来，
     * 所以落盘后再按文件头验一次。
     */
    private fun looksLikeApk(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            RandomAccessFile(file, "r").use { f ->
                val head = ByteArray(4)
                f.readFully(head)
                head[0].toInt() == 0x50 && head[1].toInt() == 0x4B
            }
        } catch (_: Throwable) {
            false
        }
    }
}
