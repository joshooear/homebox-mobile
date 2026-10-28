package com.homebox.mobile

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.homebox.mobile.api.Attachment
import com.homebox.mobile.api.Entity
import com.homebox.mobile.api.EntityUpdateReq

/**
 * 编辑物品：
 * - 修改名称/说明/备注/数量/制造商/型号/序列号/资产编号
 * - 照片：多张上传（追加）、删除；上传时可选压缩档位（默认长边≤2048，原图可选）
 * 保存采用全量 PUT（带上位置/类型/标签等原有字段，避免被清空）。
 */
class EditItemActivity : AppCompatActivity() {

    private lateinit var entityId: String
    private var current: Entity? = null

    /** 多选相册（可连续选多张） */
    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (!uris.isNullOrEmpty()) uploadPhotos(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        entityId = intent.getStringExtra("entityId") ?: run { finish(); return }
        title = "编辑物品"

        // 压缩档位下拉
        val spinner = findViewById<Spinner>(R.id.spQuality)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            ImageCompressor.OPTIONS.map { it.first }
        )
        spinner.setSelection(ImageCompressor.DEFAULT_INDEX)

        findViewById<Button>(R.id.btnUploadPhoto).setOnClickListener {
            pickImages.launch("image/*")
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnDeleteItem).setOnClickListener { confirmDelete() }

        load()
    }

    private fun load() {
        val progress = findViewById<ProgressBar>(R.id.progress)
        progress.visibility = View.VISIBLE
        doAsync(onError = { e ->
            progress.visibility = View.GONE
            toast("加载失败：${e.message}")
        }) {
            val e = Prefs.client(this).getEntity(entityId)
            current = e
            runOnUiThread {
                progress.visibility = View.GONE
                findViewById<EditText>(R.id.etName).setText(e.name)
                findViewById<EditText>(R.id.etDesc).setText(e.description)
                findViewById<EditText>(R.id.etNotes).setText(e.notes ?: "")
                findViewById<EditText>(R.id.etQty).setText(trimNum(e.quantity))
                findViewById<EditText>(R.id.etMfr).setText(e.manufacturer ?: "")
                findViewById<EditText>(R.id.etModel).setText(e.modelNumber ?: "")
                findViewById<EditText>(R.id.etSerial).setText(e.serialNumber ?: "")
                findViewById<EditText>(R.id.etAsset).setText(e.assetId ?: "")
                renderPhotos(e)
            }
        }
    }

    /** 照片列表：缩略图 + 标题 + 删除 */
    private fun renderPhotos(e: Entity) {
        val container = findViewById<LinearLayout>(R.id.photoList)
        container.removeAllViews()
        val photos = e.attachments.filter { it.isPhoto }
        findViewById<TextView>(R.id.tvPhotoCount).text =
            if (photos.isEmpty()) "还没有照片"
            else "共 ${photos.size} 张照片（上传为追加，不覆盖；首张为封面）"
        for (att in photos) {
            val row = layoutInflater.inflate(R.layout.photo_row, container, false)
            row.findViewById<TextView>(R.id.tvPhotoName).text =
                if (att.title.isBlank()) att.id.take(8) else att.title
            ImageLoader.load(this, e.id, att.id, row.findViewById(R.id.ivPhoto))
            row.findViewById<Button>(R.id.btnDeletePhoto).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("删除照片")
                    .setMessage("确定删除「${att.title.ifBlank { att.id.take(8) }}」？")
                    .setPositiveButton("删除") { _, _ -> deletePhoto(att) }
                    .setNegativeButton("取消", null)
                    .show()
            }
            container.addView(row)
        }
    }

    private fun uploadPhotos(uris: List<Uri>) {
        val spinner = findViewById<Spinner>(R.id.spQuality)
        val maxEdge = ImageCompressor.OPTIONS[spinner.selectedItemPosition].second
        val label = ImageCompressor.OPTIONS[spinner.selectedItemPosition].first
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "正在上传 ${uris.size} 张照片（$label）……"
        doAsync(onError = { e ->
            status.text = "上传失败：${e.message}"
        }) {
            val client = Prefs.client(this)
            var last: Entity? = null
            for ((i, uri) in uris.withIndex()) {
                val name = queryDisplayName(uri) ?: "photo_${System.currentTimeMillis()}.jpg"
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: continue
                // 压缩（原图档返回原始字节）
                val bytes = try {
                    ImageCompressor.compress(this, uri, maxEdge)
                } catch (_: Exception) {
                    raw
                }
                // 追加上传：只有当物品还没有任何照片时才设为主图
                val hasPhoto = (last ?: current)?.attachments?.any { it.isPhoto } == true
                last = client.uploadPhoto(entityId, name, "image/jpeg", bytes, primary = !hasPhoto)
                val done = i + 1
                runOnUiThread { status.text = "已上传 $done/${uris.size} 张……" }
            }
            val updated = last ?: client.getEntity(entityId)
            current = updated
            runOnUiThread {
                status.text = "上传完成"
                renderPhotos(updated)
            }
        }
    }

    private fun deletePhoto(att: Attachment) {
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "正在删除照片……"
        doAsync(onError = { e ->
            status.text = "删除失败：${e.message}"
        }) {
            Prefs.client(this).deleteAttachment(entityId, att.id)
            val updated = Prefs.client(this).getEntity(entityId)
            current = updated
            runOnUiThread {
                status.text = "照片已删除"
                renderPhotos(updated)
            }
        }
    }

    /** 删除物品（需确认） */
    private fun confirmDelete() {
        val e = current ?: return
        AlertDialog.Builder(this)
            .setTitle("删除物品")
            .setMessage("确定删除「${e.name}」？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ -> doDelete() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doDelete() {
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "正在删除……"
        doAsync(onError = { ex ->
            status.text = "删除失败：${ex.message}"
        }) {
            Prefs.client(this).deleteEntity(entityId)
            runOnUiThread {
                toast("物品已删除")
                setResult(RESULT_OK, android.content.Intent().putExtra("deleted", true))
                finish()
            }
        }
    }

    private fun save() {
        val e = current ?: run { toast("尚未加载完成"); return }
        val name = findViewById<EditText>(R.id.etName).text.toString().trim()
        if (name.isEmpty()) {
            toast("名称不能为空")
            return
        }
        val qty = findViewById<EditText>(R.id.etQty).text.toString().toDoubleOrNull() ?: e.quantity

        val req = EntityUpdateReq(
            id = e.id,
            name = name,
            description = findViewById<EditText>(R.id.etDesc).text.toString().trim(),
            quantity = qty,
            notes = findViewById<EditText>(R.id.etNotes).text.toString().trim(),
            manufacturer = findViewById<EditText>(R.id.etMfr).text.toString().trim().ifEmpty { null },
            modelNumber = findViewById<EditText>(R.id.etModel).text.toString().trim().ifEmpty { null },
            serialNumber = findViewById<EditText>(R.id.etSerial).text.toString().trim().ifEmpty { null },
            assetId = findViewById<EditText>(R.id.etAsset).text.toString().trim().ifEmpty { null },
            locationId = e.locationId,
            entityTypeId = e.entityTypeId,
            tagIds = e.tags.map { it.id }
        )

        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "正在保存……"
        doAsync(onError = { ex ->
            status.text = ""
            toast("保存失败：${ex.message}")
        }) {
            Prefs.client(this).updateEntity(req)
            runOnUiThread {
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
