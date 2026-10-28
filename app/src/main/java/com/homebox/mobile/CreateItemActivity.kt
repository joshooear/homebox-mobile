package com.homebox.mobile

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.homebox.mobile.api.EntityCreateReq
import com.homebox.mobile.api.EntityType
import com.homebox.mobile.api.Tag

class CreateItemActivity : AppCompatActivity() {

    private lateinit var etName: EditText
    private lateinit var etDesc: EditText
    private lateinit var etQty: EditText
    private lateinit var etMfr: EditText
    private lateinit var etModel: EditText
    private lateinit var spType: Spinner
    private lateinit var spLocation: Spinner
    private lateinit var btnTags: Button
    private lateinit var btnCreate: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView

    private var types: List<EntityType> = emptyList()
    private var locations: List<Pair<String, String>> = emptyList()
    private var tags: List<Tag> = emptyList()
    private val selectedTagIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create)

        etName = findViewById(R.id.etName)
        etDesc = findViewById(R.id.etDesc)
        etQty = findViewById(R.id.etQty)
        etMfr = findViewById(R.id.etMfr)
        etModel = findViewById(R.id.etModel)
        spType = findViewById(R.id.spType)
        spLocation = findViewById(R.id.spLocation)
        btnTags = findViewById(R.id.btnTags)
        btnCreate = findViewById(R.id.btnCreate)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)

        loadOptions()

        btnTags.setOnClickListener { pickTags() }
        btnCreate.setOnClickListener { create() }
    }

    /** 加载类型 / 位置 / 标签选项 */
    private fun loadOptions() {
        busy(true)
        doAsync(onError = { e ->
            busy(false)
            tvStatus.text = "加载选项失败：${e.message}"
        }) {
            val c = Prefs.client(this)
            types = runCatching { c.entityTypes() }.getOrDefault(emptyList())
            locations = runCatching { c.locations() }.getOrDefault(emptyList())
            tags = runCatching { c.tags() }.getOrDefault(emptyList())
            runOnUiThread {
                busy(false)
                val typeLabels = mutableListOf("(默认类型)")
                typeLabels += types.filter { !it.isLocation }.map { it.name }
                spType.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_item, typeLabels
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

                val locLabels = mutableListOf("(不指定位置)")
                locLabels += locations.map { it.second }
                spLocation.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_item, locLabels
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

                btnTags.text = if (tags.isEmpty()) "标签（无可用标签）"
                else "标签（已选 ${selectedTagIds.size}/${tags.size}）"
            }
        }
    }

    private fun pickTags() {
        if (tags.isEmpty()) {
            toast("服务器上还没有标签")
            return
        }
        val names = tags.map { it.name }.toTypedArray()
        val checked = tags.map { selectedTagIds.contains(it.id) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("选择标签")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked) selectedTagIds.add(tags[which].id)
                else selectedTagIds.remove(tags[which].id)
            }
            .setPositiveButton("确定") { _, _ ->
                btnTags.text = "标签（已选 ${selectedTagIds.size}/${tags.size}）"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun create() {
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            tvStatus.text = "请填写物品名称"
            return
        }
        val qty = etQty.text.toString().toDoubleOrNull() ?: 1.0
        val typeIdx = spType.selectedItemPosition
        val locIdx = spLocation.selectedItemPosition

        val req = EntityCreateReq(
            name = name,
            description = etDesc.text.toString().trim(),
            quantity = qty,
            locationId = if (locIdx > 0) locations[locIdx - 1].first else null,
            entityTypeId = if (typeIdx > 0) types.filter { !it.isLocation }[typeIdx - 1].id else null,
            manufacturer = etMfr.text.toString().trim().ifEmpty { null },
            modelNumber = etModel.text.toString().trim().ifEmpty { null },
            tagIds = selectedTagIds.toList()
        )

        busy(true)
        doAsync(onError = { e ->
            busy(false)
            tvStatus.text = "创建失败：${e.message}"
        }) {
            val created = Prefs.client(this).createEntity(req)
            runOnUiThread {
                busy(false)
                AlertDialog.Builder(this)
                    .setTitle("创建成功")
                    .setMessage("物品「${created.name}」已创建。\nID：${created.id}")
                    .setPositiveButton("查看详情") { _, _ ->
                        openDetail(created.id)
                        finish()
                    }
                    .setNegativeButton("继续创建") { _, _ ->
                        etName.text.clear()
                        etDesc.text.clear()
                        tvStatus.text = ""
                    }
                    .show()
            }
        }
    }

    private fun busy(b: Boolean) {
        progress.visibility = if (b) View.VISIBLE else View.GONE
        btnCreate.isEnabled = !b
    }
}
