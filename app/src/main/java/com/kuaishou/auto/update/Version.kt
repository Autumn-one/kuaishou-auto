package com.kuaishou.auto.update

/**
 * 版本号解析与比较。
 *
 * 约定（与发布脚本 tools/publish-release.ps1 必须一致）：
 *   tag 形如 v1.2.3  →  versionName = "1.2.3"
 *                        versionCode = 1_000_000 + 2_000 + 3 = 1002003
 *
 * 两端用同一套算法，客户端才能正确判断「远端比本地新」。
 * 这里是纯函数，没有 Android 依赖，可以直接写单元测试。
 */
object Version {

    /** 每段允许的最大值，超过即判为非法，避免拼装 versionCode 时溢出。 */
    private const val MAX_SEGMENT = 999

    private val PATTERN = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

    /**
     * 解析 "v1.2.3" / "1.2.3" → intArrayOf(1, 2, 3)。
     * 格式不符返回 null（调用方应忽略该 release，而不是当成 0.0.0）。
     */
    fun parseTag(tag: String?): IntArray? {
        val m = PATTERN.matchEntire(tag?.trim() ?: return null) ?: return null
        val parts = IntArray(3)
        for (i in 0..2) {
            val v = m.groupValues[i + 1].toIntOrNull() ?: return null
            if (v < 0 || v > MAX_SEGMENT) return null
            parts[i] = v
        }
        return parts
    }

    /** [1,2,3] → 1002003。与发布脚本的 To-VersionCode 一致。 */
    fun toVersionCode(parts: IntArray): Long =
        parts[0] * 1_000_000L + parts[1] * 1_000L + parts[2]

    /** 便捷入口：tag → versionCode，格式非法返回 null。 */
    fun tagToVersionCode(tag: String?): Long? = parseTag(tag)?.let { toVersionCode(it) }

    /** [1,2,3] → "1.2.3"，用于界面展示。 */
    fun format(parts: IntArray): String = "${parts[0]}.${parts[1]}.${parts[2]}"

    /**
     * 远端是否值得更新。
     *
     * 必须严格大于：等于时视为「已是最新」，否则会出现「tag 变了但 code 没变」
     * 时反复重装的死循环；小于时更不能装（本地 debug 包版本更高时不倒灌）。
     */
    fun isNewer(remoteCode: Long, installedCode: Long): Boolean = remoteCode > installedCode
}
