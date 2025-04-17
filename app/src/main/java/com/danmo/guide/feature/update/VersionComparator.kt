object VersionComparator {
    fun compareVersions(versionA: String?, versionB: String?): Int {
        if (versionA == null || versionB == null) return -2
        // 扩展正则支持1-3位版本号
        val regex = """^(\d+)(\.\d+){0,2}$""".toRegex()
        if (!versionA.matches(regex))return -2
        if (!versionB.matches(regex)) return -2
        // 分割并标准化为3位版本号
        fun normalize(ver: String): List<Int> {
            return ver.split(".").map { it.toInt() } + listOf(0, 0, 0)
        }
        val aParts = normalize(versionA).take(3)
        val bParts = normalize(versionB).take(3)
        // 逐级比较
        return aParts.zip(bParts).fold(0) { acc, (a, b) ->
            if (acc != 0) acc else a.compareTo(b)
        }
    }
}