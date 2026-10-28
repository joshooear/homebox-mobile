package com.homebox.mobile.api

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 用纯 JDK（java.base）实现的迷你 HTTP 桩服务器模拟 Homebox API，
 * 验证请求格式、鉴权头、JSON 解析与新旧 API 回退。
 * （不用 com.sun.net.httpserver——它不在 java.se，Android 单测编译不可见）
 */
class HomeboxClientTest {

    private class StubServer {
        val routes = mutableMapOf<String, Pair<Int, String>>()
        val seen = mutableListOf<Triple<String, String, String>>() // method, path, auth
        @Volatile var lastBody: ByteArray = ByteArray(0)
        @Volatile var lastContentType: String = ""
        private var server: ServerSocket? = null
        var port: Int = 0

        fun start() {
            val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            server = ss
            port = ss.localPort
            val t = Thread {
                while (!ss.isClosed) {
                    val sock = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    val worker = Thread { handle(sock) }
                    worker.isDaemon = true
                    worker.start()
                }
            }
            t.isDaemon = true
            t.start()
        }

        private fun handle(sock: Socket) {
            try {
                val ins = sock.getInputStream()
                // 字节级读请求头（避免字符流对 UTF-8 中文体的长度误判）
                fun readLineBytes(): String {
                    val sb = StringBuilder()
                    while (true) {
                        val b = ins.read()
                        if (b < 0 || b == '\n'.code) break
                        if (b != '\r'.code) sb.append(b.toChar())
                    }
                    return sb.toString()
                }
                val requestLine = readLineBytes()
                if (requestLine.isEmpty()) return
                val parts = requestLine.split(" ")
                val method = parts[0]
                val fullTarget = parts[1]                       // 含查询串，用于断言
                val path = fullTarget.substringBefore("?")      // 路由用
                var auth = ""
                var contentLength = 0
                while (true) {
                    val line = readLineBytes()
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("authorization:")) {
                        auth = line.substringAfter(":").trim()
                    } else if (lower.startsWith("content-length:")) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    } else if (lower.startsWith("content-type:")) {
                        lastContentType = line.substringAfter(":").trim()
                    }
                }
                // 按字节读掉请求体
                if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = ins.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    lastBody = buf
                }
                seen.add(Triple(method, fullTarget, auth))
                val route: Pair<Int, String> =
                    routes["$method $path"] ?: (404 to "{\"error\":\"not found\"}")
                val body: ByteArray = route.second.toByteArray(Charsets.UTF_8)
                val out: OutputStream = sock.getOutputStream()
                out.write(("HTTP/1.1 ${route.first} X\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
                out.write(body)
                out.flush()
            } catch (ignored: Exception) {
                // 测试中忽略单个请求异常
            } finally {
                try {
                    sock.close()
                } catch (_: Exception) {
                }
            }
        }

        fun stop() {
            try {
                server?.close()
            } catch (_: Exception) {
            }
        }
    }

    private lateinit var stub: StubServer

    @Before
    fun setUp() {
        stub = StubServer()
        stub.start()
    }

    @After
    fun tearDown() {
        stub.stop()
    }

    private fun client(): HomeboxClient =
        HomeboxClient("http://127.0.0.1:${stub.port}")

    @Test
    fun loginSendsCredentialsAndStoresToken() {
        stub.routes["POST /api/v1/users/login"] =
            200 to """{"token":"tok-123","expiresAt":"2030-01-01"}"""
        val c = client()
        val r = c.login("me@example.com", "pw", true)
        assertEquals("tok-123", r.token)
        assertEquals("tok-123", c.token)
        val last = stub.seen.last()
        assertEquals("POST", last.first)
        assertTrue("登录请求应发往 /api/v1/users/login", last.second.endsWith("/users/login"))
    }

    /** 实测真实服务器发现：homebox 返回的 token 自带 "Bearer " 前缀，必须去重 */
    @Test
    fun tokenWithBearerPrefixNormalized() {
        stub.routes["POST /api/v1/users/login"] =
            200 to """{"token":"Bearer NYIGNBR73","expiresAt":"2030-01-01"}"""
        stub.routes["GET /api/v1/users/self"] =
            200 to """{"item":{"id":"u1","name":"n","email":"a@b.c"}}"""
        val c = client()
        c.login("me@example.com", "pw")
        assertEquals("NYIGNBR73", c.token)   // 存储时已去掉前缀
        c.me()
        assertEquals("Bearer NYIGNBR73", stub.seen.last().third)  // 只拼一次
        // 构造时传入带前缀的 token 同样要规范化
        val c2 = HomeboxClient("http://127.0.0.1:${stub.port}", "Bearer ABC")
        assertEquals("ABC", c2.token)
    }

    @Test
    fun updateEntityPutsFullBody() {
        stub.routes["PUT /api/v1/entities/e1"] = 200 to
            """{"id":"e1","name":"改后","description":"d","quantity":3.0,"notes":"新备注"}"""
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        val e = c.updateEntity(
            EntityUpdateReq(
                id = "e1", name = "改后", description = "d", quantity = 3.0,
                notes = "新备注", entityTypeId = "ty1", tagIds = listOf("g1")
            )
        )
        assertEquals("新备注", e.notes)
        val body = String(stub.lastBody, Charsets.UTF_8)
        assertTrue("PUT 体应含 notes", body.contains("新备注"))
        assertTrue("PUT 体应含 entityTypeId", body.contains("ty1"))
        assertEquals("PUT /api/v1/entities/e1", stub.seen.last().let { "${it.first} ${it.second}" })
    }

    @Test
    fun updateFallsBackToLegacyItems() {
        stub.routes["PUT /api/v1/entities/e1"] = 404 to """{"error":"nope"}"""
        stub.routes["PUT /api/v1/items/e1"] = 200 to
            """{"id":"e1","name":"旧","quantity":1.0}"""
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        val e = c.updateEntity(EntityUpdateReq(id = "e1", name = "旧", tagIds = listOf("g1")))
        assertEquals("旧", e.name)
        assertTrue(stub.seen.any { it.second.endsWith("/items/e1") })
        assertTrue("旧接口应转 labelIds", String(stub.lastBody).contains("labelIds"))
    }

    @Test
    fun downloadAttachmentReturnsBytes() {
        stub.routes["GET /api/v1/entities/e1/attachments/a1"] = 200 to "IMGDATA"
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        val bytes = c.downloadAttachment("e1", "a1")
        assertEquals("IMGDATA", String(bytes!!, Charsets.UTF_8))
    }

    @Test
    fun uploadPhotoSendsMultipart() {
        stub.routes["POST /api/v1/entities/e1/attachments"] = 201 to
            """{"id":"e1","name":"n","quantity":1.0,"imageId":"im1"}"""
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        val e = c.uploadPhoto("e1", "a.jpg", "image/jpeg", byteArrayOf(1, 2, 3, 4), primary = false)
        assertEquals("im1", e.imageId)
        val body = String(stub.lastBody, Charsets.ISO_8859_1)
        assertTrue("应是 multipart/form-data", stub.lastContentType.startsWith("multipart/form-data"))
        assertTrue("应含文件字段", body.contains("name=\"file\""))
        assertTrue("应含 type=photo", body.contains("photo"))
        assertTrue("追加语义：primary=false", body.contains("name=\"primary\"") && body.contains("false"))
    }

    @Test
    fun deleteAttachmentWorks() {
        stub.routes["DELETE /api/v1/entities/e1/attachments/a1"] = 200 to ""
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        c.deleteAttachment("e1", "a1")
        assertEquals("DELETE /api/v1/entities/e1/attachments/a1", stub.seen.last().let { "${it.first} ${it.second}" })
    }

    @Test
    fun deleteAttachmentFallsBackToLegacy() {
        stub.routes["DELETE /api/v1/entities/e1/attachments/a1"] = 404 to """{"error":"nope"}"""
        stub.routes["DELETE /api/v1/items/e1/attachments/a1"] = 200 to ""
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        c.deleteAttachment("e1", "a1")
        assertTrue(stub.seen.any { it.second.endsWith("/items/e1/attachments/a1") })
    }

    @Test
    fun deleteEntityWorks() {
        stub.routes["DELETE /api/v1/entities/e1"] = 204 to ""
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        c.deleteEntity("e1")
        assertEquals("DELETE /api/v1/entities/e1", stub.seen.last().let { "${it.first} ${it.second}" })
    }

    @Test
    fun deleteEntityFallsBackToLegacy() {
        stub.routes["DELETE /api/v1/entities/e1"] = 404 to """{"error":"nope"}"""
        stub.routes["DELETE /api/v1/items/e1"] = 204 to ""
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        c.deleteEntity("e1")
        assertTrue(stub.seen.any { it.second.endsWith("/items/e1") })
    }

    @Test
    fun listEntitiesPaginatesAndFilters() {
        stub.routes["GET /api/v1/entities"] = 200 to
            """{"items":[{"id":"a","name":"甲","quantity":1},{"id":"b","name":"乙","quantity":2}],"total":42,"page":2,"pageSize":20}"""
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        val r = c.listEntities(q = "关键字", tagIds = listOf("g1", "g2"), locationId = "loc9", page = 2, pageSize = 20)
        assertEquals(2, r.items.size)
        assertEquals(42, r.total)
        assertEquals(3, r.pageCount)   // ceil(42/20)
        assertEquals(2, r.page)
        val last = stub.seen.last()
        assertEquals("GET", last.first)
        val path = last.second
        assertTrue("分页参数: $path", path.contains("page=2") && path.contains("pageSize=20"))
        assertTrue("关键字: $path", path.contains("q="))
        assertTrue("标签筛选: $path", path.contains("tags=g1") && path.contains("tags=g2"))
        assertTrue("位置筛选(parentIds): $path", path.contains("parentIds=loc9"))
    }

    @Test
    fun listEntitiesFallsBackToLegacyParams() {
        stub.routes["GET /api/v1/entities"] = 404 to """{"error":"nope"}"""
        stub.routes["GET /api/v1/items"] = 200 to
            """{"items":[{"id":"a","name":"甲","quantity":1}],"total":1,"page":1,"pageSize":20}"""
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        val r = c.listEntities(tagIds = listOf("g1"), locationId = "loc9")
        assertEquals(1, r.items.size)
        val path = stub.seen.last().second
        assertTrue("旧接口标签参数: $path", path.contains("labels=g1"))
        assertTrue("旧接口位置参数: $path", path.contains("locations=loc9"))
    }

    @Test
    fun entityLocationFallsBackToParent() {
        stub.routes["GET /api/v1/entities/e1"] = 200 to """
            {"id":"e1","name":"n","quantity":1.0,
             "parent":{"id":"p9","name":"东方明珠"}}
        """.trimIndent()
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        val e = c.getEntity("e1")
        assertEquals("东方明珠", e.locationName)
        assertEquals("p9", e.locationId)
    }

    @Test
    fun attachmentUrlBuilt() {
        val c = HomeboxClient("https://example.com", "t")
        assertEquals(
            "https://example.com/api/v1/entities/e1/attachments/a1",
            c.attachmentUrl("e1", "a1")
        )
    }

    @Test
    fun entityParsesAttachments() {
        stub.routes["GET /api/v1/entities/e1"] = 200 to """
            {"id":"e1","name":"n","quantity":1.0,
             "attachments":[
               {"id":"a1","title":"p1.jpg","mimeType":"image/jpeg","type":"photo","primary":true},
               {"id":"a2","title":"doc.pdf","mimeType":"application/pdf","type":"attachment","primary":false},
               {"id":"a3","title":"p2.jpg","mimeType":"image/jpeg","type":"photo","primary":false}]}
        """.trimIndent()
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        val e = c.getEntity("e1")
        assertEquals(3, e.attachments.size)
        val photos = e.attachments.filter { it.isPhoto }
        assertEquals(2, photos.size)
        assertEquals("a1", photos[0].id)
        assertTrue(photos[0].primary)
    }

    @Test
    fun bearerHeaderAttached() {
        stub.routes["GET /api/v1/users/self"] =
            200 to """{"item":{"id":"u1","name":"张三","email":"a@b.c"}}"""
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "tok-xyz")
        val u = c.me()
        assertEquals("张三", u.name)
        assertEquals("Bearer tok-xyz", stub.seen.last().third)
    }

    @Test
    fun createEntityPostsEntityCreate() {
        stub.routes["POST /api/v1/entities"] = 201 to
            """{"id":"e1","name":"测试物品","description":"d","quantity":2.0}"""
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        val e = c.createEntity(EntityCreateReq(name = "测试物品", quantity = 2.0))
        assertEquals("e1", e.id)
        val last = stub.seen.last()
        assertEquals("POST /api/v1/entities", "${last.first} ${last.second}")
    }

    @Test
    fun createFallsBackToLegacyItems() {
        stub.routes["POST /api/v1/entities"] = 404 to """{"error":"nope"}"""
        stub.routes["POST /api/v1/items"] = 201 to """{"id":"old1","name":"旧物品","quantity":1.0}"""
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        val e = c.createEntity(EntityCreateReq(name = "旧物品"))
        assertEquals("old1", e.id)
        assertTrue(stub.seen.any { it.second.endsWith("/items") })
    }

    @Test
    fun getEntityParsesTagsAndLocation() {
        stub.routes["GET /api/v1/entities/e9"] = 200 to """
            {"id":"e9","name":"电钻","description":"手电钻","quantity":3.0,
             "location":{"id":"l1","name":"仓库/A架"},
             "entityType":{"id":"t1","name":"工具"},
             "tags":[{"id":"g1","name":"电动"},{"id":"g2","name":"五金"}]}
        """.trimIndent()
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        val e = c.getEntity("e9")
        assertEquals("电钻", e.name)
        assertEquals(3.0, e.quantity, 0.001)
        assertEquals("仓库/A架", e.locationName)
        assertEquals("工具", e.typeName)
        assertEquals(2, e.tags.size)
        assertEquals("电动", e.tags[0].name)
    }

    @Test
    fun searchParsesListResult() {
        stub.routes["GET /api/v1/entities"] = 200 to
            """{"items":[{"id":"a","name":"A"},{"id":"b","name":"B"}],"total":2}"""
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        val list = c.search("A")
        assertEquals(2, list.size)
        assertEquals("B", list[1].name)
    }

    @Test
    fun locationsFlattenTree() {
        stub.routes["GET /api/v1/entities/tree"] = 200 to """
            [{"id":"l0","name":"仓库","type":"location","children":[
               {"id":"l1","name":"A架","type":"location","children":[]}]}]
        """.trimIndent()
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        val locs = c.locations()
        assertEquals(2, locs.size)
        assertEquals("仓库", locs[0].second)
        assertEquals("仓库 / A架", locs[1].second)
        assertEquals("l1", locs[1].first)
    }

    @Test
    fun errorSurfacesCodeAndMessage() {
        stub.routes["GET /api/v1/entities/bad"] = 401 to """{"error":"unauthorized"}"""
        val c = HomeboxClient("http://127.0.0.1:${stub.port}", "t")
        try {
            c.getEntity("bad")
            throw AssertionError("应当抛出 ApiException")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
            assertEquals("unauthorized", e.message)
        }
    }

    @Test
    fun normalizeServerHandlesApiSuffix() {
        assertEquals("http://host:7745", HomeboxClient.normalizeServer("host:7745/"))
        assertEquals("https://a.b", HomeboxClient.normalizeServer("https://a.b/api"))
        assertEquals("https://a.b", HomeboxClient.normalizeServer("https://a.b/api/v1"))
    }

    @Test
    fun jsonRoundTripParsesFloatQuantity() {
        val o = JSONObject("""{"id":"x","name":"n","quantity":2}""")
        val c = HomeboxClient("http://127.0.0.1:1")
        val m = c.javaClass.getDeclaredMethod("parseEntity", JSONObject::class.java)
        m.isAccessible = true
        val e = m.invoke(c, o) as Entity
        assertEquals(2.0, e.quantity, 0.001)
    }
}
