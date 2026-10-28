package com.homebox.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.homebox.mobile.api.Entity

class EntityDetailActivity : AppCompatActivity() {

    private lateinit var entityId: String

    private val editLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                if (result.data?.getBooleanExtra("deleted", false) == true) {
                    // 物品已被删除，退出详情页（搜索页 onResume 会自动刷新列表）
                    finish()
                } else {
                    toast("已保存，正在刷新")
                    load()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        entityId = intent.getStringExtra("entityId") ?: run { finish(); return }

        findViewById<Button>(R.id.btnOpenWeb).setOnClickListener {
            val url = "${Prefs.server(this)}/item/$entityId"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        findViewById<Button>(R.id.btnEdit).setOnClickListener {
            editLauncher.launch(
                Intent(this, EditItemActivity::class.java)
                    .putExtra("entityId", entityId)
            )
        }

        load()
    }

    private fun load() {
        val progress = findViewById<ProgressBar>(R.id.progress)
        progress.visibility = View.VISIBLE
        doAsync(onError = { e ->
            progress.visibility = View.GONE
            findViewById<TextView>(R.id.tvName).text = "加载失败"
            findViewById<TextView>(R.id.tvDesc).text = e.message ?: ""
        }) {
            val entity = Prefs.client(this).getEntity(entityId)
            runOnUiThread {
                progress.visibility = View.GONE
                bind(entity)
            }
        }
    }

    private fun bind(e: Entity) {
        title = "物品详情"
        findViewById<TextView>(R.id.tvName).text = e.name
        bindPhotos(e)
        setOrHide(R.id.tvType, e.typeName?.let { "类型：$it" })
        findViewById<TextView>(R.id.tvQty).text = "数量：${trimNum(e.quantity)}"
        setOrHide(R.id.tvDesc, e.description.ifEmpty { null }?.let { "描述：$it" })
        setOrHide(R.id.tvLocation, e.locationName?.let { "位置：$it" })
        setOrHide(
            R.id.tvTags,
            if (e.tags.isNotEmpty()) "标签：" + e.tags.joinToString("、") { it.name } else null
        )
        setOrHide(R.id.tvAsset, e.assetId?.let { "资产编号：$it" })
        setOrHide(R.id.tvMfr, e.manufacturer?.let { "制造商：$it" })
        setOrHide(R.id.tvModel, e.modelNumber?.let { "型号：$it" })
        setOrHide(R.id.tvSerial, e.serialNumber?.let { "序列号：$it" })
        setOrHide(R.id.tvPrice, e.purchasePrice?.let { "购买价格：${trimNum(it)}" })
        setOrHide(R.id.tvWarranty, e.warrantyExpires?.let { "保修截止：$it" })
        setOrHide(R.id.tvNotes, e.notes?.let { "备注：$it" })
    }

    /** 照片横条：展示全部照片（点击 → 全屏预览/缩放/下载） */
    private fun bindPhotos(e: Entity) {
        val strip = findViewById<android.widget.HorizontalScrollView>(R.id.photoStrip)
        val inner = findViewById<android.widget.LinearLayout>(R.id.photoStripInner)
        inner.removeAllViews()
        val photos = e.attachments.filter { it.isPhoto }

        fun openPreview(attachmentId: String, name: String) {
            startActivity(
                android.content.Intent(this, ImagePreviewActivity::class.java)
                    .putExtra("entityId", e.id)
                    .putExtra("attachmentId", attachmentId)
                    .putExtra("fileName", name)
            )
        }

        if (photos.isEmpty()) {
            // 无附件时兑底：用 imageId/thumbnailId
            val anyId = ImageLoader.photoId(e)
            if (anyId == null) {
                strip.visibility = View.GONE
            } else {
                strip.visibility = View.VISIBLE
                val iv = android.widget.ImageView(this)
                iv.layoutParams = android.widget.LinearLayout.LayoutParams(dp(280), dp(200))
                iv.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                iv.setBackgroundColor(0xFFEEEEEE.toInt())
                ImageLoader.load(this, e.id, anyId, iv)
                iv.setOnClickListener { openPreview(anyId, "${e.name}.jpg") }
                inner.addView(iv)
            }
        } else {
            strip.visibility = View.VISIBLE
            for (att in photos) {
                val iv = android.widget.ImageView(this)
                val lp = android.widget.LinearLayout.LayoutParams(dp(280), dp(200))
                lp.marginEnd = dp(8)
                iv.layoutParams = lp
                iv.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                iv.setBackgroundColor(0xFFEEEEEE.toInt())
                ImageLoader.load(this, e.id, att.id, iv)
                iv.setOnClickListener { openPreview(att.id, att.title.ifBlank { "${e.name}.jpg" }) }
                inner.addView(iv)
            }
        }
        bindOtherAttachments(e)
    }

    /** 其他格式附件：仅提供下载 */
    private fun bindOtherAttachments(e: Entity) {
        val title = findViewById<TextView>(R.id.tvAttachTitle)
        val list = findViewById<android.widget.LinearLayout>(R.id.attachList)
        list.removeAllViews()
        val others = e.attachments.filterNot { it.isPhoto }
        if (others.isEmpty()) {
            title.visibility = View.GONE
            return
        }
        title.visibility = View.VISIBLE
        for (att in others) {
            val row = layoutInflater.inflate(R.layout.attachment_row, list, false)
            row.findViewById<TextView>(R.id.tvAttachName).text =
                att.title.ifBlank { att.id.take(8) }
            row.findViewById<Button>(R.id.btnDownload).setOnClickListener {
                val url = Prefs.client(this).attachmentUrl(e.id, att.id)
                downloadWithManager(url, att.title.ifBlank { att.id })
            }
            list.addView(row)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setOrHide(viewId: Int, text: String?) {
        val v = findViewById<TextView>(viewId)
        if (text.isNullOrBlank()) v.visibility = View.GONE
        else {
            v.visibility = View.VISIBLE
            v.text = text
        }
    }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
