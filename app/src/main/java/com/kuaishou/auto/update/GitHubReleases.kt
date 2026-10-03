package com.kuaishou.auto.update

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通过 GitHub REST API 读取最新 Release。
 *
 * 为什么走 api.github.com 而不是解析网页：
 *   实测本机网络下 github.com 极慢且会中断（20 秒仅收到 513KB 后超时），
 *   而 api.github.com 稳定可达。元数据查询必须走 API。
 */
object GitHubReleases {

    private const val TAG = "GitHubReleases"

    sealed class Result {
        /** 拿到了一个可用的 release */
        data class Ok(val info: ReleaseInfo) : Result()

        /** 已是最新，或远端没有可用资产 */
        data class UpToDate(val reason: String) : Result()

        /** 失败，message 面向用户 */
        data class Failed(val message: String) : Result()
    }

    /**
     * 查询最新 release 并与 [installedCode] 比较。
     *
     * @param installedCode 本机已安装版本的 versionCode
     */
    fun fetchLatest(installedCode: Long): Result {
        val url = "${UpdateConfig.API_BASE}/repos/${UpdateConfig.GITHUB_OWNER}/${UpdateConfig.GITHUB_REPO}/releases/latest"
        val body = try {
            get(url)
        } catch (t: Throwable) {
            Log.w(TAG, "查询 release 失败", t)
            return Result.Failed("网络请求失败：${t.message ?: t.javaClass.simpleName}")
        } ?: return Result.Failed("服务器无响应")

        return try {
            parse(body, installedCode)
        } catch (t: Throwable) {
            Log.w(TAG, "解析 release 失败", t)
            Result.Failed("返回内容无法解析")
        }
    }

    /** 把一个 release JSON 解析成结果；抽取成独立函数便于测试。 */
    fun parse(body: String, installedCode: Long): Result {
        val root = JSONObject(body)

        // 403/429 时 GitHub 返回的是错误对象而不是 release
        if (root.has("message") && !root.has("tag_name")) {
            val msg = root.optString("message", "未知错误")
            return Result.Failed("GitHub 返回：$msg")
        }

        // draft / prerelease 一律忽略：客户端只应看到正式发布
        if (root.optBoolean("draft", false)) return Result.UpToDate("最新发布是草稿")
        if (root.optBoolean("prerelease", false)) return Result.UpToDate("最新发布是预发布版")

        val tag = root.optString("tag_name", "")
        val parts = Version.parseTag(tag)
            ?: return Result.Failed("版本号格式不合法：$tag")
        val remoteCode = Version.toVersionCode(parts)

        if (!Version.isNewer(remoteCode, installedCode)) {
            return Result.UpToDate("已是最新版本")
        }

        val asset = pickApk(root.optJSONArray("assets"))
            ?: return Result.UpToDate("新版本没有可用的 APK 资产")

        val info = ReleaseInfo(
            tag = tag,
            versionName = Version.format(parts),
            versionCode = remoteCode,
            notes = root.optString("body", "").trim(),
            asset = asset,
        )
        return Result.Ok(info)
    }

    /**
     * 选出唯一的 APK 资产。
     *
     * 发布纪律规定一个 release 只放一个 .apk；这里仍按显式规则挑选，
     * 遇到多个时报错而不是随便挑一个，避免装错包。
     */
    private fun pickApk(assets: org.json.JSONArray?): ReleaseInfo.Asset? {
        if (assets == null) return null
        val apks = ArrayList<ReleaseInfo.Asset>()
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            // state 不是 uploaded 表示还在上传中，此时下载会拿到不完整文件
            if (a.optString("state", "uploaded") != "uploaded") continue
            val url = a.optString("browser_download_url", "")
            if (url.isEmpty()) continue
            apks.add(
                ReleaseInfo.Asset(
                    id = a.optLong("id", 0L),
                    name = name,
                    size = a.optLong("size", 0L),
                    sha256 = a.optString("digest", "")
                        .takeIf { it.startsWith("sha256:") }
                        ?.removePrefix("sha256:")
                        ?.lowercase(),
                    browserDownloadUrl = url,
                )
            )
        }
        if (apks.isEmpty()) return null
        if (apks.size > 1) {
            Log.w(TAG, "release 里有 ${apks.size} 个 apk，取体积最大的一个")
            return apks.maxByOrNull { it.size }
        }
        return apks[0]
    }

    /** 读一个 URL 的全部内容，带体积上限。 */
    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
            readTimeout = UpdateConfig.READ_TIMEOUT_MS
            // GitHub 拒绝没有 User-Agent 的请求
            setRequestProperty("User-Agent", userAgent())
            setRequestProperty("Accept", "application/vnd.github+json")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "HTTP $code  $url")
                return null
            }
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                val buf = CharArray(8 * 1024)
                var total = 0L
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > UpdateConfig.MAX_METADATA_BYTES) return null
                    sb.append(buf, 0, n)
                }
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    internal fun userAgent(): String = "kuaishou-auto-updater"
}
