package com.homebox.mobile.api

/** 登录结果 */
data class LoginResult(
    val token: String,
    val expiresAt: String?,
    val attachmentToken: String?
)

/** 当前用户 */
data class User(
    val id: String,
    val name: String,
    val email: String
)

/** 标签（新 API 为 tags，旧 API 为 labels，统一模型） */
data class Tag(
    val id: String,
    val name: String
)

/** 实体类型（isLocation=true 的类型代表位置） */
data class EntityType(
    val id: String,
    val name: String,
    val isLocation: Boolean
)

/** 位置/物品树节点 */
data class TreeNode(
    val id: String,
    val name: String,
    val type: String,          // "location" | "item"
    val children: List<TreeNode>
) {
    /** 展平出所有位置节点（含层级全名） */
    fun flattenLocations(prefix: String = ""): List<Pair<String, String>> {
        val label = if (prefix.isEmpty()) name else "$prefix / $name"
        val self = if (type == "location") listOf(id to label) else emptyList()
        return self + children.flatMap { it.flattenLocations(label) }
    }
}

/** 分页列表结果 */
data class ListResult(
    val items: List<Entity>,
    val total: Int,
    val page: Int,
    val pageSize: Int
) {
    val pageCount: Int get() = if (pageSize <= 0) 1 else ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
}

/** 附件（照片等） */
data class Attachment(
    val id: String,
    val title: String,
    val mimeType: String,
    val type: String,        // "photo" | "attachment"
    val primary: Boolean
) {
    val isPhoto: Boolean get() = type.equals("photo", ignoreCase = true)
}

/** 物品（实体）——新旧 API 统一模型 */
data class Entity(
    val id: String,
    val name: String,
    val description: String,
    val quantity: Double,
    val locationId: String?,
    val locationName: String?,
    val typeName: String?,
    val tags: List<Tag>,
    val assetId: String?,
    val manufacturer: String?,
    val modelNumber: String?,
    val serialNumber: String?,
    val notes: String?,
    val imageId: String?,
    val thumbnailId: String?,
    val entityTypeId: String?,
    val attachments: List<Attachment>,
    val purchasePrice: Double?,
    val purchaseFrom: String?,
    val purchaseDate: String?,
    val warrantyExpires: String?,
    val createdAt: String?,
    val updatedAt: String?
)

/** 新建物品请求 */
data class EntityCreateReq(
    val name: String,
    val description: String = "",
    val quantity: Double = 1.0,
    val locationId: String? = null,
    val entityTypeId: String? = null,
    val manufacturer: String? = null,
    val modelNumber: String? = null,
    val tagIds: List<String> = emptyList()
)

/** 更新物品请求（全量提交，避免 PUT 语义清空未提交字段） */
data class EntityUpdateReq(
    val id: String,
    val name: String,
    val description: String = "",
    val quantity: Double = 1.0,
    val notes: String = "",
    val manufacturer: String? = null,
    val modelNumber: String? = null,
    val serialNumber: String? = null,
    val assetId: String? = null,
    val locationId: String? = null,
    val entityTypeId: String? = null,
    val tagIds: List<String> = emptyList()
)

/** API 调用异常（含 HTTP 状态码） */
class ApiException(val code: Int, message: String) : Exception(message)
