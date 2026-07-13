package com.github.kr328.clash

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * app 内在线更新：
 *   1. 拉 https://caihongmao.org/version.json
 *   2. versionCode 比当前大 → 弹更新框（changelog + 立即更新）
 *   3. 点「立即更新」→ 下载 APK 到外部缓存（带进度）→ 拉起系统安装器
 *
 * 说明：sideload 安装最后一步系统必弹「安装」确认框（安卓安全机制，绕不过）；
 * 首次可能要用户在设置里允许「安装未知应用」，本类会自动跳转引导。
 */
object Updater {

    private const val VERSION_URL = "https://caihongmao.org/version.json"

    // 每次启动至多提示一次，避免来回切前台反复弹
    @Volatile private var promptedThisSession = false

    fun checkAndPrompt(activity: Activity, manual: Boolean = false) {
        if (promptedThisSession && !manual) return
        Thread {
            try {
                val json = httpGet(VERSION_URL) ?: return@Thread
                val o = JSONObject(json)
                val latestCode = o.optInt("versionCode", 0)
                val latestName = o.optString("versionName", "")
                val apkUrl = o.optString("url", "")
                val changelog = o.optString("changelog", "发现新版本")
                val force = o.optBoolean("force", false)
                val current = currentVersionCode(activity)
                if (latestCode <= current || apkUrl.isEmpty()) {
                    if (manual) activity.runOnUiThread {
                        if (!activity.isFinishing)
                            android.widget.Toast.makeText(
                                activity, "已是最新版本", android.widget.Toast.LENGTH_SHORT
                            ).show()
                    }
                    return@Thread
                }

                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    promptedThisSession = true
                    val b = AlertDialog.Builder(activity)
                        .setTitle("发现新版本 v$latestName")
                        .setMessage(changelog)
                        .setPositiveButton("立即更新") { _, _ -> startUpdate(activity, apkUrl, force) }
                    if (force) {
                        b.setCancelable(false)
                    } else {
                        b.setNegativeButton("稍后", null)
                    }
                    b.show()
                }
            } catch (_: Exception) { /* 更新检查失败静默，不打扰用户 */ }
        }.start()
    }

    // ---------------- 下载 + 安装 ----------------

    private fun startUpdate(activity: Activity, apkUrl: String, force: Boolean) {
        // Android 8+ 需要「安装未知应用」授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(activity)
                .setTitle("需要允许安装权限")
                .setMessage("为完成 app 内更新，请在接下来的页面把「彩虹猫」的「允许安装未知应用」打开，然后返回重试。")
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    } catch (_: Exception) {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 进度弹窗
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false
        }
        val label = TextView(activity).apply { text = "正在下载新版本… 0%" }
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(label); addView(bar)
        }
        val dlg = AlertDialog.Builder(activity)
            .setTitle("更新彩虹猫")
            .setView(box)
            .setCancelable(false)
            .create()
        dlg.show()

        Thread {
            try {
                val outDir = File(activity.externalCacheDir, "updates").apply { mkdirs() }
                // 清掉旧的下载残留
                outDir.listFiles()?.forEach { it.delete() }
                val apk = File(outDir, "caihongmao-update.apk")

                val conn = URL(apkUrl).openConnection() as HttpsURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.connect()
                val total = conn.contentLength.toLong()
                conn.inputStream.use { input ->
                    apk.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        var lastPct = -1
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    activity.runOnUiThread {
                                        bar.progress = pct
                                        label.text = "正在下载新版本… $pct%"
                                    }
                                }
                            }
                        }
                    }
                }
                conn.disconnect()

                activity.runOnUiThread {
                    dlg.dismiss()
                    installApk(activity, apk)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    dlg.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("更新失败")
                        .setMessage("下载新版本失败：${e.message ?: "网络异常"}。可稍后重试，或到官网手动下载。")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }.start()
    }

    private fun installApk(activity: Activity, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.updateprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            AlertDialog.Builder(activity)
                .setTitle("无法启动安装")
                .setMessage("请到官网手动下载安装：caihongmao.org")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    // ---------------- helpers ----------------

    private fun currentVersionCode(activity: Activity): Int = try {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (_: Exception) { 0 }

    private fun httpGet(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.connectTimeout = 7000
        conn.readTimeout = 10000
        conn.setRequestProperty("Cache-Control", "no-cache")
        if (conn.responseCode == 200)
            conn.inputStream.readBytes().toString(Charsets.UTF_8) else null
    } catch (_: Exception) { null }
}
