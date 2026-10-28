package com.homebox.mobile

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.homebox.mobile.api.Entity
import com.homebox.mobile.api.QrParser

/**
 * 识别 / 查找物品（合并界面）：
 * - 扫码识别物品二维码（物品链接 / UUID），识别后直接进详情
 * - 也可手动输入搜索：资产号（缺省 "#000-" 前缀，如 "#000-350"）、名称、关键字
 * - 多页展示，每页 20 条
 */
class SearchActivity : AppCompatActivity() {

    companion object {
        /** 搜索框缺省值：homebox 资产号搜索前缀 */
        const val DEFAULT_QUERY = "#000-"
        const val PAGE_SIZE = 20
    }

    private lateinit var etQuery: EditText
    private lateinit var tvCount: TextView
    private lateinit var tvPage: TextView
    private lateinit var listResults: ListView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button

    private var results: List<Entity> = emptyList()
    private var page = 1
    private var totalPages = 1
    private var totalItems = 0
    private var lastQuery = DEFAULT_QUERY

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            onScanResult(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        etQuery = findViewById(R.id.etQuery)
        tvCount = findViewById(R.id.tvCount)
        tvPage = findViewById(R.id.tvPage)
        listResults = findViewById(R.id.listResults)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)

        findViewById<Button>(R.id.btnSearch).setOnClickListener {
            lastQuery = etQuery.text.toString().trim()
            page = 1
            load()
        }
        findViewById<Button>(R.id.btnScan).setOnClickListener { launchScan() }
        etQuery.setOnEditorActionListener { _, _, _ ->
            lastQuery = etQuery.text.toString().trim()
            page = 1
            load(); true
        }
        btnPrev.setOnClickListener {
            if (page > 1) { page--; load() }
        }
        btnNext.setOnClickListener {
            if (page < totalPages) { page++; load() }
        }

        listResults.setOnItemClickListener { _, _, position, _ ->
            if (position < results.size) openDetail(results[position].id)
        }

        // 缺省值
        val initial = intent.getStringExtra("query") ?: DEFAULT_QUERY
        etQuery.setText(initial)
        etQuery.setSelection(initial.length)
        lastQuery = initial
        load()
    }

    override fun onResume() {
        super.onResume()
        // 从详情/编辑返回时刷新当前页（可能有删除/修改）
        if (results.isNotEmpty()) load()
    }

    private fun launchScan() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("将物品二维码对准取景框")
            .setBeepEnabled(true)
            .setOrientationLocked(true) // 强制竖屏，不旋转成横屏
        scanLauncher.launch(options)
    }

    /** 二维码识别结果处理：物品 ID → 直接进详情；其他内容 → 填入搜索框并搜索 */
    private fun onScanResult(raw: String) {
        when (val r = QrParser.parse(raw)) {
            is QrParser.Result.EntityId -> {
                toast("识别到物品，正在查询……")
                doAsync(onError = { e ->
                    AlertDialog.Builder(this)
                        .setTitle("未找到物品")
                        .setMessage("扫码内容：$raw\n\n${e.message}")
                        .setPositiveButton("改为搜索") { _, _ ->
                            etQuery.setText(raw)
                            lastQuery = raw
                            page = 1
                            load()
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }) {
                    val entity = Prefs.client(this).getEntity(r.id)
                    runOnUiThread { openDetail(entity.id) }
                }
            }
            is QrParser.Result.Query -> {
                toast("扫码内容不是物品链接，已转为搜索")
                etQuery.setText(r.text)
                lastQuery = r.text
                page = 1
                load()
            }
        }
    }

    private fun load() {
        val q = lastQuery
        if (q.isEmpty()) {
            tvCount.text = "请输入搜索内容（缺省 #000- 为资产号前缀）"
            return
        }
        tvCount.text = "搜索中……"
        doAsync(onError = { e ->
            tvCount.text = "搜索失败：${e.message}"
        }) {
            val r = Prefs.client(this).listEntities(q = q, page = page, pageSize = PAGE_SIZE)
            results = r.items
            totalPages = r.pageCount
            totalItems = r.total
            page = r.page
            runOnUiThread {
                if (r.items.isEmpty()) {
                    tvCount.text = "没有找到匹配的物品"
                    tvPage.text = ""
                    btnPrev.isEnabled = false
                    btnNext.isEnabled = false
                } else {
                    tvCount.text = "共 $totalItems 个结果，点击查看详情"
                    tvPage.text = "第 $page / $totalPages 页"
                    btnPrev.isEnabled = page > 1
                    btnNext.isEnabled = page < totalPages
                }
                listResults.adapter = EntityAdapter(this, r.items)
            }
        }
    }
}
