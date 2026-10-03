package com.kuaishou.auto.update

import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新检查与下载的集中配置。
 *
 * 这里所有地址都在真机（小米 13 / Android 16）上实测过，结论写在各自注释里，
 * 不要凭印象改动：换域名、换拼接格式都可能让更新直接失效。
 */
object UpdateConfig {

    /** 仓库坐标，需与 release-config.json 里的 repo 保持一致。 */
    const val GITHUB_OWNER = "Autumn-one"
    const val GITHUB_REPO = "kuaishou-auto"

    /**
     * 检查更新走 api.github.com。
     *
     * 实测（PC 与手机一致）：api.github.com 稳定可达；而 github.com 在
     * 本机网络下极慢且会中断（20 秒仅收到 513KB 后超时）。
     * 因此元数据查询一律走 API，不解析网页。
     */
    const val API_BASE = "https://api.github.com"

    /**
     * APK 下载镜像，按顺序依次尝试，前一个失败才换下一个。
     *
     * 顺序按用户指定：gitwarp → ghfast → gh-proxy → v4 → v6 → cdn。
     *
     * 实测结果（真机下载本仓库真实 APK 3.0MB，SHA-256 与 GitHub 官方值逐一比对）：
     *   proxy.gitwarp.top   200  3.12 MB  SHA 匹配
     *   ghfast.top          200  9.05s  0.34 MB/s  SHA 匹配
     *   gh-proxy.org        200  5.11s  0.61 MB/s  SHA 匹配
     *   v4.gh-proxy.org     206  2.55s  1.22 MB/s  SHA 匹配
     *   v6.gh-proxy.org     200 10.94s  0.28 MB/s  SHA 匹配
     *   cdn.gh-proxy.org    200  2.34s  1.33 MB/s  SHA 匹配
     *
     * 关于 gitwarp 的一个坑（实测，值得记住）：
     *   它对**小文件**会返回一张「下载即将开始」的 HTML 中转页而不是文件本体。
     *   用 1971 字节的 checksums.txt 测会拿到 7559 字节的 HTML；
     *   但换成 3MB 的真实 APK 就正常返回文件。所以不能用小样本判断它是否可用。
     *   本项目的 APK 有 3MB 左右，实测可用，因此按用户要求放在第一位。
     *   ApkDownloader 里的 ZIP 魔数检查（PK 头）会兜住极端情况：
     *   万一它哪天返回 HTML，那一步会识别出来并自动换下一个镜像。
     *
     * 全部镜像都支持 Range 断点续传，且验证过「A 镜像下前半段 + B 镜像续后半段」
     * 拼接后的 SHA-256 与一次性下载完全一致 —— 换源续传是安全的。
     *
     * 为什么含 v6 且排在 v4 之后：v6 域名在只有 IPv4 的网络下会连接被重置，
     * 但那属于快速失败（毫秒级），代价很低；一旦本机具备 IPv6 它就能用。
     */
    val DOWNLOAD_MIRRORS: List<String> = listOf(
        "https://proxy.gitwarp.top/",
        "https://ghfast.top/",
        "https://gh-proxy.org/",
        "https://v4.gh-proxy.org/",
        "https://v6.gh-proxy.org/",
        "https://cdn.gh-proxy.org/",
    )

    /**
     * 兜底直连（不走任何镜像）。
     *
     * 实测本机网络下 github.com 直连 60 秒超时（http=000），所以它排在最后；
     * 但只要用户处于其他网络环境它就可能最快，因此保留而不是直接砍掉。
     */
    const val DIRECT_BASE = "https://github.com"

    /** 连接超时：镜像不可用时要快速失败，不能让用户干等。 */
    const val CONNECT_TIMEOUT_MS = 10_000

    /** 读取超时：实测存在 0.14~2.2 MB/s 的波动，取值要容忍慢速但能中断假死。 */
    const val READ_TIMEOUT_MS = 20_000

    /** 单个镜像的最大重试次数（同一镜像内重试，用尽才换下一个）。 */
    const val RETRY_PER_MIRROR = 2

    /** 重试退避基数（毫秒），第 n 次重试等待 n * 该值。 */
    const val RETRY_BACKOFF_MS = 1_500

    /** 元数据响应体上限，防止异常响应把内存吃满。 */
    const val MAX_METADATA_BYTES = 512 * 1024L

    /**
     * 把 GitHub 的资产地址改写成镜像地址。
     *
     * 实测各镜像的用法都是「前缀 + 完整原始 URL」：
     *   https://gh-proxy.org/ + https://github.com/o/r/releases/download/t/f.apk
     * 且 releases/latest/download/<文件名> 这种形式同样被支持。
     */
    fun mirrorUrl(prefix: String, originalUrl: String): String =
        prefix.trimEnd('/') + "/" + originalUrl.trimStart('/')

    /** 供日志与界面展示的镜像短名（去掉协议与结尾斜杠）。 */
    fun mirrorLabel(prefix: String): String =
        prefix.removePrefix("https://").removePrefix("http://").trimEnd('/')
}
