package com.homebox.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.loggedIn(this)) {
            goLogin()
            return
        }
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.tvServerInfo).text =
            "服务器：${Prefs.server(this)}    用户：${Prefs.user(this)}"

        // 扫码与查找已合并为一个入口
        findViewById<Button>(R.id.btnIdentify).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        findViewById<Button>(R.id.btnBrowse).setOnClickListener {
            startActivity(Intent(this, BrowseActivity::class.java))
        }

        findViewById<Button>(R.id.btnCreate).setOnClickListener {
            startActivity(Intent(this, CreateItemActivity::class.java))
        }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("确定要退出当前账号吗？")
                .setPositiveButton("退出") { _, _ ->
                    Prefs.clear(this)
                    goLogin()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
