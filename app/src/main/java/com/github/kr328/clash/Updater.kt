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
 * app 内在线更新（多通道抗封）：
 *   1. 依次从多个未被墙域名拉 /version.json（任一成功即用）
 *   2. versionCode 比当前大 → 弹更新框（changelog + 立即更新）
 *   3. 下载 APK 同样多通道回退（原url→cdn4→cdn6→cdn7→dl→apex），单域名被 GFW reset 自动切换
 *
 * 域名被封时：服务器泛域名底座加新子域名，并把这里 DOMAINS 补一条即可。
 */
object Updater {

    // 多通道域名（查版本 + 下 APK 共用）。都需在服务器挂了 /version.json 与 /downloads/。
    private val DOMAINS = listOf(
        "https://p1.yuntanlu.com",
        "https://p2.yuntanlu.com",
        "https://w1.yuntanlu.com",
        "https://dl.yuntanlu.com",
        "https://w2.yuntanlu.com",
        "https://w3.yuntanlu.com",
    )

    // 每次启动至多提示一次，避免来回切前台反复弹
    @Volatile private var promptedThisSession = false

    fun checkAndPrompt(activity: Activity, manual: Boolean = false) {
        if (promptedThisSession && !manual) return
        Thread {
            try {
                val json = fetchVersionJson() ?: return@Thread
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
            val outDir = File(activity.externalCacheDir, "updates").apply { mkdirs() }
            // 清掉旧的下载残留
            outDir.listFiles()?.forEach { it.delete() }
            val apk = File(outDir, "caihongmao-update.apk")

            // 多通道下载：原 url 先试，再依次试各域名的 /downloads/<文件名>；被封自动切换
            val fileName = apkUrl.substringAfterLast("/")
            val urls = ArrayList<String>()
            urls.add(apkUrl)
            for (base in DOMAINS) {
                val u = "$base/downloads/$fileName"
                if (u != apkUrl) urls.add(u)
            }

            var ok = false
            var lastErr: Exception? = null
            for (u in urls) {
                try {
                    downloadApkOnce(u, apk) { pct ->
                        activity.runOnUiThread {
                            bar.progress = pct
                            label.text = "正在下载新版本… $pct%"
                        }
                    }
                    ok = true
                    break
                } catch (e: Exception) {
                    lastErr = e
                    activity.runOnUiThread { label.text = "线路切换重试…" }
                }
            }

            activity.runOnUiThread {
                dlg.dismiss()
                if (ok) {
                    installApk(activity, apk)
                } else {
                    AlertDialog.Builder(activity)
                        .setTitle("更新失败")
                        .setMessage("下载失败：${lastErr?.message ?: "网络异常"}。可稍后重试，或到 dl.yuntanlu.com 手动下载。")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }.start()
    }

    /** 单次下载；被 reset 截断或文件异常会抛异常，交由上层切下一条通道。 */
    private fun downloadApkOnce(urlStr: String, apk: File, onPct: (Int) -> Unit) {
        val conn = URL(urlStr).openConnection() as HttpsURLConnection
        conn.connectTimeout = 4000
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
                        if (pct != lastPct) { lastPct = pct; onPct(pct) }
                    }
                }
            }
        }
        conn.disconnect()
        // 完整性校验：防止被 GFW reset 截断的半包被当成"下载成功"
        if (total > 0 && apk.length() < total) throw Exception("下载不完整（线路中断）")
        if (apk.length() < 1_000_000) throw Exception("文件异常")
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
                .setMessage("请到 dl.yuntanlu.com 手动下载安装。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    // ---------------- helpers ----------------

    /** 依次试各域名的 /version.json，任一返回含 versionCode 的内容即用。 */
    private fun fetchVersionJson(): String? {
        for (base in DOMAINS) {
            val j = httpGet("$base/version.json")
            if (j != null && j.contains("versionCode")) return j
        }
        return null
    }

    private fun currentVersionCode(activity: Activity): Int = try {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (_: Exception) { 0 }

    private fun httpGet(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 8000
        conn.setRequestProperty("Cache-Control", "no-cache")
        if (conn.responseCode == 200)
            conn.inputStream.readBytes().toString(Charsets.UTF_8) else null
    } catch (_: Exception) { null }
}
