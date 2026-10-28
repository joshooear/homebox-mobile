package com.homebox.mobile.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrParserTest {

    @Test
    fun bareUuid() {
        val r = QrParser.parse("3f2b8e9a-1c4d-4e5f-8a9b-0c1d2e3f4a5b")
        assertTrue(r is QrParser.Result.EntityId)
        assertEquals("3f2b8e9a-1c4d-4e5f-8a9b-0c1d2e3f4a5b", (r as QrParser.Result.EntityId).id)
    }

    @Test
    fun itemUrl() {
        val r = QrParser.parse("https://homebox.example.com/item/3F2B8E9A-1C4D-4E5F-8A9B-0C1D2E3F4A5B")
        assertTrue(r is QrParser.Result.EntityId)
        assertEquals("3f2b8e9a-1c4d-4e5f-8a9b-0c1d2e3f4a5b", (r as QrParser.Result.EntityId).id)
    }

    @Test
    fun entityUrlWithQuery() {
        val r = QrParser.parse("https://host/item/3f2b8e9a-1c4d-4e5f-8a9b-0c1d2e3f4a5b?ref=qr")
        assertTrue(r is QrParser.Result.EntityId)
        assertEquals("3f2b8e9a-1c4d-4e5f-8a9b-0c1d2e3f4a5b", (r as QrParser.Result.EntityId).id)
    }

    @Test
    fun shortIdFromUrl() {
        val r = QrParser.parse("https://host/item/abc123")
        assertTrue(r is QrParser.Result.EntityId)
        assertEquals("abc123", (r as QrParser.Result.EntityId).id)
    }

    @Test
    fun assetTagSlashAWithoutUrlBecomesHashSearch() {
        // /a/ 前面没有网址链接 → 替换为 # 走资产号搜索
        val r = QrParser.parse("/a/000-350")
        assertTrue(r is QrParser.Result.Query)
        assertEquals("#000-350", (r as QrParser.Result.Query).text)
    }

    @Test
    fun assetTagWithUrlKeepsUrlHandling() {
        // /a/ 前面有网址链接 → 不替换，走原有链接处理
        val r = QrParser.parse("https://example.com/a/000-350")
        assertTrue(r is QrParser.Result.EntityId)
        assertEquals("000-350", (r as QrParser.Result.EntityId).id)
    }

    @Test
    fun plainTextFallsBackToQuery() {
        val r = QrParser.parse("螺丝刀套装")
        assertTrue(r is QrParser.Result.Query)
        assertEquals("螺丝刀套装", (r as QrParser.Result.Query).text)
    }
}
