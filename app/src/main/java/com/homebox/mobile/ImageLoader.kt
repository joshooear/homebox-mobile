package com.homebox.mobile

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import com.homebox.mobile.api.HomeboxClient

/**
 * 物品图片异步加载器：
 * 内存缓存 + 后台线程下载，ImageView 用 tag 校验防止列表复用错位。
 */
object ImageLoader {

    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** 照片优先级：缩略图 > 原图 */
    fun photoId(e: com.homebox.mobile.api.Entity): String? =
        e.thumbnailId?.takeIf { it.isNotEmpty() } ?: e.imageId?.takeIf { it.isNotEmpty() }

    /** 加载并显示；无图片时隐藏 ImageView。ctx 需为 Activity（用于取登录态） */
    fun load(activity: Activity, entityId: String, attachmentId: String?, into: ImageView) {
        if (attachmentId.isNullOrEmpty()) {
            into.tag = null
            into.visibility = View.GONE
            return
        }
        val key = "$entityId/$attachmentId"
        into.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            into.setImageBitmap(cached)
            into.visibility = View.VISIBLE
            return
        }
        into.visibility = View.GONE
        val client: HomeboxClient = Prefs.client(activity)
        executor.execute {
            try {
                val bytes = client.downloadAttachment(entityId, attachmentId) ?: return@execute
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                cache.put(key, bmp)
                main.post {
                    if (into.tag == key && !activity.isFinishing) {
                        into.setImageBitmap(bmp)
                        into.visibility = View.VISIBLE
                    }
                }
            } catch (_: Exception) {
                // 图片加载失败静默处理（不影响列表/详情展示）
            }
        }
    }
}
