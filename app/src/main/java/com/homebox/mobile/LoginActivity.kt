package com.homebox.mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.homebox.mobile.api.HomeboxClient

class LoginActivity : AppCompatActivity() {

    private lateinit var etServer: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var cbStay: CheckBox
    private lateinit var btnLogin: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etServer = findViewById(R.id.etServer)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        cbStay = findViewById(R.id.cbStay)
        btnLogin = findViewById(R.id.btnLogin)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)

        // 无缺省服务器地址：仅回填上次登录过的地址（首次为空）
        etServer.setText(Prefs.server(this))

        // 已登录则直接进主页
        if (Prefs.loggedIn(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        btnLogin.setOnClickListener {
            val server = etServer.text.toString().trim()
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString()
            if (server.isEmpty()) {
                tvStatus.text = "请填写服务器地址"
                return@setOnClickListener
            }
            if (user.isEmpty() || pass.isEmpty()) {
                tvStatus.text = "请填写用户名和密码"
                return@setOnClickListener
            }
            btnLogin.isEnabled = false
            progress.visibility = View.VISIBLE
            tvStatus.text = "正在登录……"

            doAsync(onError = { e ->
                btnLogin.isEnabled = true
                progress.visibility = View.GONE
                tvStatus.text = "登录失败：${e.message}"
            }) {
                val client = HomeboxClient(server)
                val r = client.login(user, pass, cbStay.isChecked)
                Prefs.saveLogin(this, client.server, user, r.token)
                runOnUiThread {
                    progress.visibility = View.GONE
                    tvStatus.text = "登录成功"
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
        }
    }
}
