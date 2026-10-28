package com.homebox.mobile

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.homebox.mobile.api.Entity

/**
 * 按位置 / 标签浏览物品：
 * 两个下拉（位置、标签），按其中一个或两个筛选，多页展示，每页 20 条。
 * 展示方式与查找物品界面一致（名称 + 说明 + 首张照片）。
 */
class BrowseActivity : AppCompatActivity() {

    private lateinit var spLocation: Spinner
    private lateinit var spTag: Spinner
    private lateinit var tvCount: TextView
    private lateinit var tvPage: TextView
    private lateinit var listResults: ListView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button

    /** 下拉数据：显示名 → id（第一项为“全部”，id 为空） */
    private var locationOptions: List<Pair<String, String>> = listOf("全部位置" to "")
    private var tagOptions: List<Pair<String, String>> = listOf("全部标签" to "")

    private var page = 1
    private var totalPages = 1
    private var totalItems = 0
    private var results: List<Entity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        title = "按位置 / 标签查看"

        spLocation = findViewById(R.id.spLocation)
        spTag = findViewById(R.id.spTag)
        tvCount = findViewById(R.id.tvCount)
        tvPage = findViewById(R.id.tvPage)
        listResults = findViewById(R.id.listResults)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)

        findViewById<Button>(R.id.btnFilter).setOnClickListener {
            page = 1
            load()
        }
        btnPrev.setOnClickListener { if (page > 1) { page--; load() } }
        btnNext.setOnClickListener { if (page < totalPages) { page++; load() } }
        listResults.setOnItemClickListener { _, _, position, _ ->
            if (position < results.size) openDetail(results[position].id)
        }

        loadOptions()
    }

    /** 加载位置树与标签到两个下拉 */
    private fun loadOptions() {
        tvCount.text = "正在加载位置/标签……"
        doAsync(onError = { e ->
            tvCount.text = "加载失败：${e.message}"
            bindSpinners()
        }) {
            val client = Prefs.client(this)
            val locs = client.locations()          // List<Pair<id, 层级名>>
            val tags = client.tags()
            // 下拉选项统一为 Pair<显示名, id>（注意 locations() 是 Pair<id, 名称>，需对调）
            locationOptions = listOf("全部位置" to "") + locs.map { (id, label) -> label to id }
            tagOptions = listOf("全部标签" to "") + tags.map { it.name to it.id }
            runOnUiThread {
                bindSpinners()
                load()
            }
        }
    }

    private fun bindSpinners() {
        spLocation.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            locationOptions.map { it.first }
        )
        spTag.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            tagOptions.map { it.first }
        )
    }

    private fun load() {
        val locId = locationOptions.getOrNull(spLocation.selectedItemPosition)?.second ?: ""
        val tagId = tagOptions.getOrNull(spTag.selectedItemPosition)?.second ?: ""
        val filters = buildList {
            if (locId.isNotEmpty()) add("位置=${locationOptions[spLocation.selectedItemPosition].first}")
            if (tagId.isNotEmpty()) add("标签=${tagOptions[spTag.selectedItemPosition].first}")
        }
        tvCount.text = if (filters.isEmpty()) "搜索中……（未筛选，显示全部）"
        else "搜索中……（${filters.joinToString("、")}）"
        doAsync(onError = { e ->
            tvCount.text = "搜索失败：${e.message}"
        }) {
            val r = Prefs.client(this).listEntities(
                tagIds = if (tagId.isNotEmpty()) listOf(tagId) else emptyList(),
                locationId = locId.ifEmpty { null },
                page = page,
                pageSize = SearchActivity.PAGE_SIZE
            )
            totalItems = r.total
            totalPages = r.pageCount
            page = r.page
            results = r.items
            runOnUiThread {
                tvCount.text = if (r.items.isEmpty()) "没有符合条件的物品"
                else "共 $totalItems 个结果，点击查看详情"
                tvPage.text = if (r.items.isEmpty()) "" else "第 $page / $totalPages 页"
                btnPrev.isEnabled = r.items.isNotEmpty() && page > 1
                btnNext.isEnabled = r.items.isNotEmpty() && page < totalPages
                listResults.adapter = EntityAdapter(this, r.items)
            }
        }
    }
}
