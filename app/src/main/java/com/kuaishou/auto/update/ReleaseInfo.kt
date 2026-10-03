package com.kuaishou.auto.update

/**
 * 一次 GitHub Release 的解析结果。
 *
 * 只保留更新流程真正要用的字段，不把整份 JSON 映射成对象，
 * 这样 GitHub 以后新增字段不会影响解析。
 */
data class ReleaseInfo(
    /** 原始 tag，例如 "v1.2.3" */
    val tag: String,
    /** 版本名，例如 "1.2.3" */
    val versionName: String,
    /** 由 tag 算出的 versionCode，与本地 installedCode 直接比较 */
    val versionCode: Long,
    /** 更新说明（markdown 原文） */
    val notes: String,
    /** APK 资产 */
    val asset: Asset,
) {
    data class Asset(
        /** 资产在 API 中的数字 id，下载时用它拼地址 */
        val id: Long,
        val name: String,
        val size: Long,
        /**
         * 发布脚本算出的 SHA-256，形如 "sha256:abcd..."。
         * GitHub 只对部分上传方式提供该字段，可能为 null；
         * 为 null 时下载后仍会计算摘要，只是无法与官方值比对。
         */
        val sha256: String?,
        /** GitHub 原始下载地址，形如 https://github.com/o/r/releases/download/t/f.apk */
        val browserDownloadUrl: String,
    )
}
