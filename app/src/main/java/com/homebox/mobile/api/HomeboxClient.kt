package com.homebox.mobile.api

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Homebox REST 客户端（官方 OpenAPI：basePath=/api，路径 /v1/...）。
 *
 * 兼容说明：homebox v0.26 起 /v1/items、/v1/locations 合并为 /v1/entities；
 * 本客户端优先使用新接口，遇到 404/405 自动回退到旧接口（items/labels/locations）。
 */
class HomeboxClient(serverUrl: String, token0: String? = null) {

    /** 当前 token（始终存规范格式，不含 Bearer 前缀；发请求时统一拼接） */
    var token: String? = normalizeToken(token0)
        set(value) {
            field = normalizeToken(value)
        }

    /** 规范化服务器地址（末尾不含 /） */
    val server: String = normalizeServer(serverUrl)

    /** API 基址，如 https://host/api/v1 */
    val apiBase: String
        get() = "$server/api/v1"

    companion object {
        /** 规范化 token：服务器可能返回带 "Bearer " 前缀的 token（如 homebox v0.26+），去掉避免重复拼接 */
        fun normalizeToken(raw: String?): String? {
            if (raw == null) return null
            val t = raw.trim()
            if (t.isEmpty()) return null
            return if (t.startsWith("Bearer ", ignoreCase = true)) t.substring(7).trim() else t
        }

        fun normalizeServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return s
            if (!s.startsWith("http://") && !s.startsWith("https://")) {
                s = "http://$s"
            }
            // 用户可能直接填了 /api 或 /api/v1 结尾的地址
            s = s.removeSuffix("/api/v1").removeSuffix("/api").trimEnd('/')
            return s
        }
    }

    // ------------------------------------------------------------------ HTTP

    private sealed class HttpResult {
        data class Ok(val code: Int, val body: String) : HttpResult()
        data class Fail(val code: Int, val message: String) : HttpResult()
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
        query: String = ""
    ): HttpResult {
        val url = URL(apiBase + path + query)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            if (auth) token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            if (body != null) {
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { ins ->
                BufferedReader(InputStreamReader(ins, StandardCharsets.UTF_8)).use { it.readText() }
            } ?: ""
            return if (code in 200..299) HttpResult.Ok(code, text)
            else HttpResult.Fail(code, extractError(text) ?: "HTTP $code")
        } finally {
            conn.disconnect()
        }
    }

    private fun extractError(body: String): String? = try {
        val o = JSONObject(body)
        when {
            o.has("error") -> o.getString("error")
            o.has("message") -> o.getString("message")
            else -> null
        }
    } catch (_: Exception) {
        body.takeIf { it.isNotBlank() }?.take(200)
    }

    /** 直接请求；失败抛 ApiException */
    private fun must(method: String, path: String, body: JSONObject? = null, query: String = ""): String =
        when (val r = request(method, path, body, query = query)) {
            is HttpResult.Ok -> r.body
            is HttpResult.Fail -> throw ApiException(r.code, r.message)
        }

    /** 请求并在 404/405 时回退到备用路径 */
    private fun withFallback(primary: () -> HttpResult, fallback: () -> HttpResult): String {
        return when (val r = primary()) {
            is HttpResult.Ok -> r.body
            is HttpResult.Fail ->
                if (r.code == 404 || r.code == 405) {
                    when (val f = fallback()) {
                        is HttpResult.Ok -> f.body
                        is HttpResult.Fail -> throw ApiException(f.code, f.message)
                    }
                } else throw ApiException(r.code, r.message)
        }
    }

    // ------------------------------------------------------------------ API

    /** 登录并保存 token */
    fun login(username: String, password: String, stayLoggedIn: Boolean = true): LoginResult {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("stayLoggedIn", stayLoggedIn)
        val text = must("POST", "/users/login", body)
        val o = JSONObject(text)
        val t = o.optString("token", "")
        if (t.isEmpty()) throw ApiException(200, "登录响应中没有 token")
        token = normalizeToken(t)
        return LoginResult(token!!, o.optString("expiresAt"), o.optString("attachmentToken"))
    }

    /** 当前用户信息 */
    fun me(): User {
        val o = JSONObject(must("GET", "/users/self"))
        val u = if (o.has("item")) o.getJSONObject("item") else o
        return User(
            id = u.optString("id"),
            name = u.optString("name").ifEmpty { u.optString("email") },
            email = u.optString("email")
        )
    }

    /** 服务器状态（连通性检查） */
    fun status(): String = must("GET", "/status")

    /** 读取单个物品（新 entities / 旧 items 自动回退） */
    fun getEntity(id: String): Entity {
        val text = withFallback(
            primary = { request("GET", "/entities/$id") },
            fallback = { request("GET", "/items/$id") }
        )
        return parseEntity(JSONObject(text))
    }

    /** 新建物品 */
    fun createEntity(req: EntityCreateReq): Entity {
        val body = JSONObject()
            .put("name", req.name)
            .put("description", req.description)
            .put("quantity", req.quantity)
        req.locationId?.let { body.put("locationId", it) }
        req.entityTypeId?.let { body.put("entityTypeId", it) }
        req.manufacturer?.let { body.put("manufacturer", it) }
        req.modelNumber?.let { body.put("modelNumber", it) }
        if (req.tagIds.isNotEmpty()) body.put("tagIds", JSONArray(req.tagIds))

        val text = withFallback(
            primary = { request("POST", "/entities", body) },
            fallback = {
                // 旧 API：tags 用 labelIds，且不支持 entityTypeId
                val legacy = JSONObject(body.toString())
                legacy.remove("entityTypeId")
                if (req.tagIds.isNotEmpty()) {
                    legacy.remove("tagIds")
                    legacy.put("labelIds", JSONArray(req.tagIds))
                }
                request("POST", "/items", legacy)
            }
        )
        return parseEntity(JSONObject(text))
    }

    /** 搜索物品（旧签名兼容） */
    fun search(query: String, pageSize: Int = 50): List<Entity> =
        listEntities(q = query, pageSize = pageSize).items

    /**
     * 分页列表/搜索：q 关键字、tagIds 标签筛选、locationId 位置筛选（新 API 用 parentIds）。
     * 新 entities / 旧 items 自动回退。
     */
    fun listEntities(
        q: String? = null,
        tagIds: List<String> = emptyList(),
        locationId: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
        orderBy: String = "name"
    ): ListResult {
        val params = mutableListOf(
            "page=$page", "pageSize=$pageSize",
            "orderBy=$orderBy", "orderDirection=asc"
        )
        if (!q.isNullOrBlank()) params += "q=" + URLEncoder.encode(q, "UTF-8")
        // 新 API：tags=ID&tags=ID、位置 = parentIds（合并实体模型中位置是父实体）
        tagIds.forEach { params += "tags=$it" }
        if (!locationId.isNullOrBlank()) params += "parentIds=$locationId"
        val query = "?" + params.joinToString("&")

        val text = withFallback(
            primary = { request("GET", "/entities", query = query) },
            fallback = {
                // 旧 API：标签 labels、位置 locations
                val legacy = query.replace("tags=", "labels=")
                    .replace("parentIds=", "locations=")
                request("GET", "/items", query = legacy)
            }
        )
        val o = JSONObject(text)
        val arr = o.optJSONArray("items") ?: JSONArray()
        return ListResult(
            items = (0 until arr.length()).map { parseEntity(arr.getJSONObject(it)) },
            total = o.optInt("total", arr.length()),
            page = o.optInt("page", page),
            pageSize = o.optInt("pageSize", pageSize)
        )
    }

    /** 删除物品（新 entities / 旧 items 自动回退） */
    fun deleteEntity(id: String) {
        val r = request("DELETE", "/entities/$id")
        when (r) {
            is HttpResult.Ok -> return
            is HttpResult.Fail -> {
                if (r.code != 404) throw ApiException(r.code, r.message)
            }
        }
        val legacy = request("DELETE", "/items/$id")
        when (legacy) {
            is HttpResult.Ok -> return
            is HttpResult.Fail -> throw ApiException(legacy.code, legacy.message)
        }
    }

    /** 更新物品（PUT 全量提交；新 entities / 旧 items 自动回退） */
    fun updateEntity(req: EntityUpdateReq): Entity {
        val body = JSONObject()
            .put("id", req.id)
            .put("name", req.name)
            .put("description", req.description)
            .put("quantity", req.quantity)
            .put("notes", req.notes)
        req.manufacturer?.let { body.put("manufacturer", it) }
        req.modelNumber?.let { body.put("modelNumber", it) }
        req.serialNumber?.let { body.put("serialNumber", it) }
        req.assetId?.let { body.put("assetId", it) }
        req.locationId?.let { body.put("locationId", it) }
        req.entityTypeId?.let { body.put("entityTypeId", it) }
        if (req.tagIds.isNotEmpty()) body.put("tagIds", JSONArray(req.tagIds))

        val text = withFallback(
            primary = { request("PUT", "/entities/${req.id}", body) },
            fallback = {
                val legacy = JSONObject(body.toString())
                legacy.remove("entityTypeId")
                if (req.tagIds.isNotEmpty()) {
                    legacy.remove("tagIds")
                    legacy.put("labelIds", JSONArray(req.tagIds))
                }
                request("PUT", "/items/${req.id}", legacy)
            }
        )
        return parseEntity(JSONObject(text))
    }

    /** 下载附件字节（图片等）；attachmentId 为空返回 null */
    fun downloadAttachment(entityId: String, attachmentId: String): ByteArray? {
        if (attachmentId.isEmpty()) return null
        for (base in listOf("/entities/", "/items/")) {
            val url = URL(apiBase + base + entityId + "/attachments/" + attachmentId)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
            try {
                val code = conn.responseCode
                if (code in 200..299) {
                    return conn.inputStream.use { it.readBytes() }
                }
                if (code != 404) throw ApiException(code, "HTTP $code")
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /** 附件下载地址（供 DownloadManager 使用；鉴权头另行附加） */
    fun attachmentUrl(entityId: String, attachmentId: String): String =
        "$apiBase/entities/$entityId/attachments/$attachmentId"

    /** 上传照片（multipart/form-data；type=photo）；追加语义：primary=false 不顶替现有主图 */
    fun uploadPhoto(
        entityId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        primary: Boolean = false
    ): Entity {
        val boundary = "----homeboxBoundary" + System.currentTimeMillis()
        for (base in listOf("/entities/", "/items/")) {
            val url = URL(apiBase + base + entityId + "/attachments")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30000
                readTimeout = 60000
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            try {
                conn.outputStream.use { out ->
                    fun field(name: String, value: String) {
                        out.write("--$boundary\r\n".toByteArray())
                        out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                        out.write(value.toByteArray(Charsets.UTF_8))
                        out.write("\r\n".toByteArray())
                    }
                    field("name", fileName)
                    field("type", "photo")
                    field("primary", primary.toString())
                    out.write("--$boundary\r\n".toByteArray())
                    out.write(
                        ("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
                                "Content-Type: $mimeType\r\n\r\n").toByteArray()
                    )
                    out.write(bytes)
                    out.write("\r\n".toByteArray())
                    out.write("--$boundary--\r\n".toByteArray())
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                if (code in 200..299) return parseEntity(JSONObject(text))
                if (code != 404) throw ApiException(code, extractError(text) ?: "HTTP $code")
            } finally {
                conn.disconnect()
            }
        }
        throw ApiException(404, "上传接口不存在")
    }

    /** 删除附件（新 entities / 旧 items 自动回退） */
    fun deleteAttachment(entityId: String, attachmentId: String) {
        for (base in listOf("/entities/", "/items/")) {
            val r = request("DELETE", "$base$entityId/attachments/$attachmentId")
            when (r) {
                is HttpResult.Ok -> return
                is HttpResult.Fail -> {
                    if (r.code == 404 && base == "/entities/") continue
                    throw ApiException(r.code, r.message)
                }
            }
        }
        throw ApiException(404, "删除接口不存在")
    }

    /** 标签列表 */
    fun tags(): List<Tag> {
        val text = withFallback(
            primary = { request("GET", "/tags") },
            fallback = { request("GET", "/labels") }
        )
        val arr = JSONArray(text)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Tag(o.optString("id"), o.optString("name"))
        }
    }

    /** 实体类型（旧 API 无此概念，返回空） */
    fun entityTypes(): List<EntityType> {
        val r = request("GET", "/entity-types")
        val text = (r as? HttpResult.Ok)?.body ?: return emptyList()
        val arr = JSONArray(text)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            EntityType(o.optString("id"), o.optString("name"), o.optBoolean("isLocation"))
        }
    }

    /** 位置列表（层级树展平） */
    fun locations(): List<Pair<String, String>> {
        val text = withFallback(
            primary = { request("GET", "/entities/tree", query = "?withItems=false") },
            fallback = { request("GET", "/locations") }
        )
        val arr = JSONArray(text)
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            // 新 API：TreeItem{ id,name,type,children }；旧 API：LocationWithId{ id,name }
            if (o.has("type")) {
                out += parseTree(o).flattenLocations()
            } else {
                out += o.optString("id") to o.optString("name")
            }
        }
        // 去重（树中同名位置）
        return out.distinctBy { it.first }
    }

    // ---------------------------------------------------------------- JSON

    private fun parseTree(o: JSONObject): TreeNode {
        val kids = o.optJSONArray("children") ?: JSONArray()
        return TreeNode(
            id = o.optString("id"),
            name = o.optString("name"),
            type = o.optString("type", "location"),
            children = (0 until kids.length()).map { parseTree(kids.getJSONObject(it)) }
        )
    }

    private fun parseAttachments(arr: org.json.JSONArray?): List<Attachment> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Attachment(
                id = o.optString("id"),
                title = o.optString("title"),
                mimeType = o.optString("mimeType"),
                type = o.optString("type", "photo"),
                primary = o.optBoolean("primary", false)
            )
        }.filter { it.id.isNotEmpty() }
    }

    private fun parseEntity(o: JSONObject): Entity {
        // tags（新）/ labels（旧）
        val tagsArr = when {
            o.has("tags") -> o.optJSONArray("tags")
            o.has("labels") -> o.optJSONArray("labels")
            else -> null
        }
        val tags = mutableListOf<Tag>()
        tagsArr?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                tags += Tag(t.optString("id"), t.optString("name"))
            }
        }
        val locObj = o.optJSONObject("location")
        val parentObj = o.optJSONObject("parent")
        val typeObj = o.optJSONObject("entityType") ?: o.optJSONObject("type")
        return Entity(
            id = o.optString("id"),
            name = o.optString("name"),
            description = o.optString("description"),
            quantity = o.optDouble("quantity", 1.0),
            locationId = o.optString("locationId").ifEmpty {
                locObj?.optString("id").orEmpty().ifEmpty { parentObj?.optString("id") }
            },
            locationName = locObj?.optString("name").orEmpty().ifEmpty { parentObj?.optString("name") },
            typeName = typeObj?.optString("name"),
            tags = tags,
            assetId = o.optString("assetId").ifEmpty { null },
            manufacturer = o.optString("manufacturer").ifEmpty { null },
            modelNumber = o.optString("modelNumber").ifEmpty { null },
            serialNumber = o.optString("serialNumber").ifEmpty { null },
            notes = o.optString("notes").ifEmpty { null },
            imageId = o.optString("imageId").ifEmpty { null },
            thumbnailId = o.optString("thumbnailId").ifEmpty { null },
            entityTypeId = typeObj?.optString("id").orEmpty().ifEmpty { null },
            attachments = parseAttachments(o.optJSONArray("attachments")),
            purchasePrice = if (o.has("purchasePrice")) o.optDouble("purchasePrice") else null,
            purchaseFrom = o.optString("purchaseFrom").ifEmpty { null },
            purchaseDate = o.optString("purchaseDate").ifEmpty { null },
            warrantyExpires = o.optString("warrantyExpires").ifEmpty { null },
            createdAt = o.optString("createdAt").ifEmpty { null },
            updatedAt = o.optString("updatedAt").ifEmpty { null }
        )
    }
}
