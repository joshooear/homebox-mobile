package com.homebox.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** 上传照片压缩选项 */
object ImageCompressor {

    /** 档位：显示名 → 长边像素上限；null = 原图 */
    val OPTIONS: List<Pair<String, Int?>> = listOf(
        "压缩（长边≤2048，默认）" to 2048,
        "中等（长边≤1600）" to 1600,
        "较小（长边≤1280）" to 1280,
        "最小（长边≤800）" to 800,
        "原图（不压缩）" to null
    )

    const val DEFAULT_INDEX = 0

    /**
     * 压缩图片：maxLongEdge=null 原样返回；否则解码缩放到长边 ≤ maxLongEdge，JPEG 85%。
     * 在后台线程调用（解码耗时）。
     */
    fun compress(ctx: Context, uri: Uri, maxLongEdge: Int?): ByteArray {
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("读取图片失败")
        if (maxLongEdge == null) return raw

        // 先探尺寸，计算采样率
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return raw

        var sample = 1
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        while (longEdge / (sample * 2) >= maxLongEdge) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return raw

        // 精确缩放到长边上限
        val le = max(bmp.width, bmp.height)
        if (le > maxLongEdge) {
            val scale = maxLongEdge.toFloat() / le
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
