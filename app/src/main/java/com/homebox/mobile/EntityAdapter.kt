package com.homebox.mobile

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import com.homebox.mobile.api.Entity

/**
 * 物品列表适配器（搜索 / 位置标签浏览 共用）：
 * 每行显示 物品名称 + 说明；有照片则显示第一张缩略图。
 */
class EntityAdapter(
    private val activity: Activity,
    items: List<Entity>
) : ArrayAdapter<Entity>(activity, R.layout.list_item, R.id.tvTitle, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = super.getView(position, convertView, parent)
        val e = getItem(position)!!
        v.findViewById<TextView>(R.id.tvTitle).text = e.name
        v.findViewById<TextView>(R.id.tvSub).text = e.description.ifEmpty { "（无说明）" }
        ImageLoader.load(activity, e.id, ImageLoader.photoId(e), v.findViewById(R.id.ivThumb))
        return v
    }
}
