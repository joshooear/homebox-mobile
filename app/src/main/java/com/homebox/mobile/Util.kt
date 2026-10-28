package com.homebox.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.homebox.mobile.api.HomeboxClient

/** 登录态持久化 */
object Prefs {
    private const val NAME = "homebox_prefs"
    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun saveLogin(c: Context, server: String, user: String, token: String) {
        sp(c).edit().putString("server", server).putString("user", user)
            .putString("token", token).apply()
    }

    fun server(c: Context): String = sp(c).getString("server", "") ?: ""
    fun user(c: Context): String = sp(c).getString("user", "") ?: ""
    fun token(c: Context): String = sp(c).getString("token", "") ?: ""
    fun loggedIn(c: Context): Boolean = server(c).isNotBlank() && token(c).isNotBlank()

    fun clear(c: Context) {
        sp(c).edit().clear().apply()
    }

    /** 构造已登录的客户端 */
    fun client(c: Context): HomeboxClient = HomeboxClient(server(c), token(c))
}

/** 后台线程执行，结果回到主线程 */
fun Activity.doAsync(
    onError: (Throwable) -> Unit = { toast(it.message ?: "出错了") },
    block: () -> Unit
) {
    Thread {
        try {
            block()
        } catch (e: Throwable) {
            runOnUiThread { onError(e) }
        }
    }.start()
}

fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

/** 打开物品详情页 */
fun Context.openDetail(entityId: String) {
    startActivity(
        Intent(this, EntityDetailActivity::class.java)
            .putExtra("entityId", entityId)
    )
}

/** 附件下载到系统「下载」目录（DownloadManager，带鉴权头，完成有通知） */
fun Context.downloadWithManager(url: String, fileName: String) {
    // Android 9 及以下写公共下载目录需要存储权限
    if (android.os.Build.VERSION.SDK_INT <= 28 &&
        checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        (this as? android.app.Activity)?.requestPermissions(
            arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 4001
        )
        toast("请允许存储权限后重试下载")
        return
    }
    val dm = getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
    val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
    req.addRequestHeader("Authorization", Prefs.token(this).let { "Bearer $it" })
    req.setTitle(fileName)
    req.setDescription("Homebox 附件下载")
    req.setNotificationVisibility(
        android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
    )
    req.setDestinationInExternalPublicDir(
        android.os.Environment.DIRECTORY_DOWNLOADS,
        fileName
    )
    dm.enqueue(req)
    toast("已开始下载：$fileName")
}
