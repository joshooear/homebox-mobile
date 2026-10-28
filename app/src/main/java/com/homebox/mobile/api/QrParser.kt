package com.homebox.mobile.api

/**
 * 从扫码结果中识别物品：
 *  - 直接是 UUID（homebox 实体 ID）
 *  - 物品链接（如 https://host/item/<uuid>、/entity/<uuid>、?id=<uuid>）
 *  - 其他文本 → 作为搜索关键字
 */
object QrParser {
    private val UUID_REGEX = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )

    sealed class Result {
        /** 识别为物品 ID */
        data class EntityId(val id: String) : Result()

        /** 未识别出 ID，按关键字搜索 */
        data class Query(val text: String) : Result()
    }

    fun parse(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Query(text)

        // 整串就是 UUID
        if (UUID_REGEX.matches(text)) return Result.EntityId(text.lowercase())

        // 标签格式：/a/<资产号> —— /a/ 前面没有网址链接时，把 /a/ 替换为 # 走资产号搜索
        // （如 "/a/000-350" → 搜索 "#000-350"）
        val ai = text.indexOf("/a/")
        if (ai >= 0 && !text.substring(0, ai).contains("://")) {
            return Result.Query(text.replaceFirst("/a/", "#"))
        }

        // 链接/任意文本中包含 UUID
        UUID_REGEX.find(text)?.let { return Result.EntityId(it.value.lowercase()) }

        // 链接里取最后一段路径作为 ID（仅当内容像 URL 时）
        val looksLikeUrl = text.contains("://") || text.startsWith("/")
        if (looksLikeUrl) {
            val path = try {
                java.net.URI(text).path ?: ""
            } catch (_: Exception) {
                ""
            }
            val seg = path.trimEnd('/').substringAfterLast('/')
            if (seg.isNotEmpty() && seg.length <= 64 && !seg.contains(' ')) {
                return Result.EntityId(seg)
            }
        }
        return Result.Query(text)
    }
}
