package com.homebox.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.max
import kotlin.math.min

/**
 * 图片全屏预览：双指缩放、双击放大/还原、拖动平移、一键下载。
 * 图片按屏幕尺寸解码（大图不 OOM）。
 */
class ImagePreviewActivity : AppCompatActivity() {

    private lateinit var entityId: String
    private lateinit var attachmentId: String
    private var fileName: String = "photo.jpg"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        entityId = intent.getStringExtra("entityId") ?: run { finish(); return }
        attachmentId = intent.getStringExtra("attachmentId") ?: run { finish(); return }
        fileName = intent.getStringExtra("fileName")?.ifBlank { null } ?: "${attachmentId.take(8)}.jpg"

        findViewById<TextView>(R.id.tvTitle).text = fileName
        val progress = findViewById<ProgressBar>(R.id.progress)

        doAsync(onError = { e ->
            progress.visibility = View.GONE
            findViewById<TextView>(R.id.tvTitle).text = "加载失败：${e.message}"
        }) {
            val bytes = Prefs.client(this).downloadAttachment(entityId, attachmentId)
            if (bytes == null) throw IllegalStateException("图片不存在")
            val bmp = decodeSampled(bytes, resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels)
            runOnUiThread {
                progress.visibility = View.GONE
                val iv = findViewById<ZoomImageView>(R.id.zoomImage)
                iv.setImageBitmap(bmp)
            }
        }

        findViewById<Button>(R.id.btnDownload).setOnClickListener {
            val url = Prefs.client(this).attachmentUrl(entityId, attachmentId)
            downloadWithManager(url, fileName)
        }
        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
    }

    /** 按目标尺寸采样解码，防大图 OOM */
    private fun decodeSampled(bytes: ByteArray, reqW: Int, reqH: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= reqW && opts.outHeight / (sample * 2) >= reqH) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("图片解码失败")
    }
}

/**
 * 可缩放 ImageView：双指捏合缩放、双击放大/还原、单指拖动平移。
 */
class ZoomImageView(context: Context, attrs: android.util.AttributeSet? = null) :
    ImageView(context, attrs) {

    private val matrix = Matrix()
    private val saved = Matrix()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private var mode = NONE
    private val start = PointF()
    private val mid = PointF()
    private var oldDist = 1f

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
        private const val MAX_SCALE = 6f
    }

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        if (bm != null) {
            // 适配屏幕居中
            post {
                val vw = width.toFloat()
                val vh = height.toFloat()
                val bw = bm.width.toFloat()
                val bh = bm.height.toFloat()
                val s = min(vw / bw, vh / bh)
                matrix.reset()
                matrix.postScale(s, s)
                matrix.postTranslate((vw - bw * s) / 2, (vh - bh * s) / 2)
                imageMatrix = matrix
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                saved.set(matrix)
                start.set(event.x, event.y)
                mode = DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    saved.set(matrix)
                    midPoint(mid, event)
                    mode = ZOOM
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG) {
                    matrix.set(saved)
                    matrix.postTranslate(event.x - start.x, event.y - start.y)
                } else if (mode == ZOOM && event.pointerCount >= 2) {
                    val newDist = spacing(event)
                    if (newDist > 10f) {
                        matrix.set(saved)
                        val scale = newDist / oldDist
                        matrix.postScale(scale, scale, mid.x, mid.y)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> mode = NONE
        }
        imageMatrix = matrix
        fixTranslation()
        return true
    }

    /** 缩放范围钳制 + 平移边界钳制 */
    private fun fixTranslation() {
        val values = FloatArray(9)
        matrix.getValues(values)
        var scaleX = values[Matrix.MSCALE_X]
        var scaleY = values[Matrix.MSCALE_Y]
        if (scaleX < 1f) {
            matrix.postScale(1f / scaleX, 1f / scaleY, width / 2f, height / 2f)
            matrix.getValues(values)
            scaleX = values[Matrix.MSCALE_X]
            scaleY = values[Matrix.MSCALE_Y]
        }
        if (scaleX > MAX_SCALE) {
            matrix.postScale(MAX_SCALE / scaleX, MAX_SCALE / scaleY, width / 2f, height / 2f)
            matrix.getValues(values)
        }
        val tx = values[Matrix.MTRANS_X]
        val ty = values[Matrix.MTRANS_Y]
        val dw = drawable?.intrinsicWidth ?: return
        val dh = drawable?.intrinsicHeight ?: return
        val maxX = width - dw * scaleX
        val maxY = height - dh * scaleY
        if (maxX < 0) matrix.postTranslate(if (tx > 0) -tx else if (tx < maxX) maxX - tx else 0f, 0f)
        if (maxY < 0) matrix.postTranslate(0f, if (ty > 0) -ty else if (ty < maxY) maxY - ty else 0f)
        imageMatrix = matrix
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        point.set((event.getX(0) + event.getX(1)) / 2, (event.getY(0) + event.getY(1)) / 2)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val values = FloatArray(9)
            matrix.getValues(values)
            val cur = values[Matrix.MSCALE_X]
            val target = (cur * detector.scaleFactor).coerceIn(1f, MAX_SCALE)
            matrix.postScale(target / cur, target / cur, detector.focusX, detector.focusY)
            imageMatrix = matrix
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // 双击：在 1x / 2.5x 之间切换
            val values = FloatArray(9)
            matrix.getValues(values)
            val cur = values[Matrix.MSCALE_X]
            val target = if (cur > 1.6f) 1f else 2.5f
            matrix.postScale(target / cur, target / cur, e.x, e.y)
            imageMatrix = matrix
            fixTranslation()
            return true
        }
    }
}
