package com.github.kr328.clash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class SetupActivity : AppCompatActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // 已授予 VPN 权限，但不自动连接：进主页保持断开，由用户手动点"一键连接"
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    companion object {
        const val PREFS_NAME = "caihongyun_prefs"
        const val KEY_SUBSCRIPTION_URL = "subscription_url"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val XBOARD_HOST = "caihongmao.org"
        const val XBOARD_BASE = "https://$XBOARD_HOST"
        // 面板域名（走 Cloudflare 的 HTTPS 通道，作为直连 IP 的备用/优先通道）
        const val XBOARD_PANEL_DOMAIN = "my.caihongmao.org"
        // Direct server IP + port 8080 (plain HTTP, no TLS).
        // GFW strips SNI from TLS ClientHello on Cloudflare IPs — HTTPS is unworkable.
        // xboard listens on 0.0.0.0:8080, directly reachable from the internet.
        const val XBOARD_IP = "5.253.38.67"
        const val XBOARD_API_PORT = 8080

        fun isSetupDone(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return !prefs.getString(KEY_SUBSCRIPTION_URL, null).isNullOrBlank()
        }

        private data class RawResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray)

        private fun parseRawHttp(raw: ByteArray): RawResponse {
            var sepPos = -1
            for (i in 0..raw.size - 4) {
                if (raw[i] == 13.toByte() && raw[i+1] == 10.toByte() &&
                    raw[i+2] == 13.toByte() && raw[i+3] == 10.toByte()) {
                    sepPos = i; break
                }
            }
            if (sepPos < 0) throw Exception("无效 HTTP 响应")
            val headerStr = raw.copyOfRange(0, sepPos).toString(Charsets.UTF_8)
            var bodyBytes = raw.copyOfRange(sepPos + 4, raw.size)
            val lines = headerStr.split("\r\n")
            val statusCode = lines.getOrElse(0) { "" }.split(" ").getOrNull(1)?.toInt() ?: 0
            val headers = lines.drop(1).associate { line ->
                val idx = line.indexOf(':')
                if (idx > 0) line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
                else "" to ""
            }
            if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                bodyBytes = decodeChunked(bodyBytes)
            }
            return RawResponse(statusCode, headers, bodyBytes)
        }

        /**
         * Plain HTTP request to xboard on port 8080.
         *
         * HTTPS via Cloudflare IPs is impossible from China: GFW strips the SNI extension
         * from TLS ClientHello, causing Cloudflare to send HANDSHAKE_FAILURE immediately.
         * Multiple approaches (custom SSLSocketFactory, raw SSLSocket) all fail for the
         * same reason — the fix must be at the network level, not the SSL library level.
         *
         * Solution: xboard's Docker container is exposed on 0.0.0.0:8080, directly reachable
         * from the internet. Plain HTTP on port 8080 to the server IP bypasses Cloudflare,
         * TLS, SNI, and GFW SNI stripping entirely.
         */
        fun httpApiRequest(
            method: String,
            path: String,
            reqHeaders: Map<String, String> = emptyMap(),
            body: ByteArray? = null
        ): Pair<Int, String> {
            // 双通道：优先 Cloudflare 域名 HTTPS（能开 caihongmao.org 的用户最稳），
            // TLS/连接失败（GFW 剥 SNI 等）再回退直连 IP:8080 裸 HTTP。
            // 只在“连接层失败”时回退；拿到任何 HTTP 状态码（含 4xx）即视为通道可用。
            return try {
                httpsCfRequest(method, path, reqHeaders, body)
            } catch (e: Exception) {
                rawIpRequest(method, path, reqHeaders, body)
            }
        }

        private fun httpsCfRequest(
            method: String, path: String,
            reqHeaders: Map<String, String>, body: ByteArray?
        ): Pair<Int, String> {
            val conn = java.net.URL("https://$XBOARD_PANEL_DOMAIN$path")
                .openConnection() as javax.net.ssl.HttpsURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 7000
            conn.readTimeout = 12000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            reqHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode   // 抛异常=连接层失败=回退 IP
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = (stream?.readBytes() ?: ByteArray(0)).toString(Charsets.UTF_8)
            conn.disconnect()
            return code to text
        }

        private fun rawIpRequest(
            method: String, path: String,
            reqHeaders: Map<String, String>, body: ByteArray?
        ): Pair<Int, String> {
            val socket = Socket()
            socket.connect(InetSocketAddress(InetAddress.getByName(XBOARD_IP), XBOARD_API_PORT), 8000)
            socket.soTimeout = 15000

            val out = socket.outputStream
            val req = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: $XBOARD_HOST\r\n")
                append("Accept: application/json\r\n")
                append("Accept-Encoding: identity\r\n")
                append("Connection: close\r\n")
                reqHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
                if (body != null) {
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${body.size}\r\n")
                }
                append("\r\n")
            }
            out.write(req.toByteArray(Charsets.UTF_8))
            body?.let { out.write(it) }
            out.flush()

            val raw = socket.inputStream.readBytes()
            socket.close()
            val r = parseRawHttp(raw)
            return r.code to r.body.toString(Charsets.UTF_8)
        }

        private fun decodeChunked(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var pos = 0
            while (pos < data.size) {
                var end = pos
                while (end < data.size - 1 &&
                    !(data[end] == 13.toByte() && data[end + 1] == 10.toByte())) end++
                if (end >= data.size - 1) break
                val size = data.copyOfRange(pos, end).toString(Charsets.UTF_8).trim()
                    .toIntOrNull(16) ?: break
                if (size == 0) break
                pos = end + 2
                if (pos + size > data.size) break
                out.write(data, pos, size)
                pos += size + 2
            }
            return out.toByteArray()
        }

    }

    private var useUrlMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLoginMode()
    }

    private fun showLoginMode() {
        useUrlMode = false
        setContentView(buildLayout())
    }

    private fun showUrlMode() {
        useUrlMode = true
        setContentView(buildLayout())
    }

    private fun buildLayout(): LinearLayout {
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }

        layout.addView(TextView(this).apply {
            text = "🌈 彩虹猫"
            textSize = 28f
            gravity = Gravity.CENTER
        })
        layout.addView(TextView(this).apply {
            text = "安全自由，专属LGBT社群"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, (8 * dp).toInt())
        })
        layout.addView(TextView(this).apply {
            text = "v1.0.23 · $XBOARD_HOST"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, (24 * dp).toInt())
        })

        val statusText = TextView(this).apply {
            text = ""
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFFF5555.toInt())
        }

        if (!useUrlMode) {
            val emailInput = EditText(this).apply {
                hint = "请输入邮箱"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            val passwordInput = EditText(this).apply {
                hint = "请输入密码"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val loginBtn = Button(this).apply { text = "登录并连接" }

            layout.addView(TextView(this).apply { text = "邮箱" })
            layout.addView(emailInput)
            layout.addView(TextView(this).apply {
                text = "密码"
                setPadding(0, (16 * dp).toInt(), 0, 0)
            })
            layout.addView(passwordInput)
            layout.addView(loginBtn)
            layout.addView(statusText)
            layout.addView(TextView(this).apply {
                text = "已有订阅链接？直接粘贴"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFF7C3AED.toInt())
                setPadding(0, (16 * dp).toInt(), 0, 0)
                isClickable = true
                setOnClickListener { showUrlMode() }
            })
            layout.addView(TextView(this).apply {
                text = "还没有账号？点此注册"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(0xFF7C3AED.toInt())
                setPadding(0, (12 * dp).toInt(), 0, 0)
                isClickable = true
                setOnClickListener { showRegisterWebView() }
            })

            loginBtn.setOnClickListener {
                val email = emailInput.text.toString().trim()
                val password = passwordInput.text.toString().trim()
                if (email.isEmpty() || password.isEmpty()) {
                    statusText.text = "请输入邮箱和密码"; return@setOnClickListener
                }
                loginBtn.isEnabled = false
                statusText.setTextColor(0xFF888888.toInt())
                statusText.text = "登录中..."
                lifecycleScope.launch {
                    try {
                        val subscriptionUrl = doLogin(email, password)
                        statusText.text = "正在导入订阅..."
                        importSubscription(subscriptionUrl)
                        saveAndLaunch(subscriptionUrl)
                    } catch (e: Exception) {
                        loginBtn.isEnabled = true
                        statusText.setTextColor(0xFFFF5555.toInt())
                        statusText.text = e.message ?: "登录失败，请重试"
                    }
                }
            }
        } else {
            val urlInput = EditText(this).apply {
                hint = "粘贴 clash:// 或 https:// 订阅链接"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
                minLines = 2
            }
            val confirmBtn = Button(this).apply { text = "导入并连接" }

            layout.addView(TextView(this).apply { text = "订阅链接" })
            layout.addView(urlInput)
            layout.addView(TextView(this).apply {
                text = "可从管理员或彩虹猫群里获取订阅链接"
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                setPadding(0, (8 * dp).toInt(), 0, 0)
            })
            layout.addView(confirmBtn)
            layout.addView(statusText)
            layout.addView(TextView(this).apply {
                text = "返回邮箱登录"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFF7C3AED.toInt())
                setPadding(0, (16 * dp).toInt(), 0, 0)
                isClickable = true
                setOnClickListener { showLoginMode() }
            })

            confirmBtn.setOnClickListener {
                val url = urlInput.text.toString().trim()
                if (url.isEmpty()) {
                    statusText.text = "请粘贴订阅链接"; return@setOnClickListener
                }
                confirmBtn.isEnabled = false
                statusText.setTextColor(0xFF888888.toInt())
                statusText.text = "正在导入..."
                lifecycleScope.launch {
                    try {
                        importSubscription(url)
                        saveAndLaunch(url)
                    } catch (e: Exception) {
                        confirmBtn.isEnabled = true
                        statusText.setTextColor(0xFFFF5555.toInt())
                        statusText.text = e.message ?: "导入失败，请检查链接"
                    }
                }
            }
        }

        return layout
    }

    private fun saveAndLaunch(subscriptionUrl: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUBSCRIPTION_URL, subscriptionUrl).apply()
        val vpnRequest = VpnService.prepare(this)
        if (vpnRequest != null) {
            vpnPermissionLauncher.launch(vpnRequest)
        } else {
            // 不自动连接：登录后进主页保持断开，用户手动点"一键连接"
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }
    }

    private suspend fun doLogin(email: String, password: String): String =
        withContext(Dispatchers.IO) {
            val bodyBytes = JSONObject().apply {
                put("email", email)
                put("password", password)
            }.toString().toByteArray(Charsets.UTF_8)

            val (code, resp) = httpApiRequest("POST", "/api/v1/passport/auth/login", body = bodyBytes)
            if (code != 200) throw Exception("登录失败，请检查邮箱和密码 (HTTP $code)")

            val data = JSONObject(resp).optJSONObject("data")
                ?: throw Exception("登录响应格式错误")
            val token = data.optString("auth_data", "")
            if (token.isEmpty()) throw Exception("获取 token 失败")

            // 存 token,供 App 内购买/面板网页自动登录(cookie access_token)
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_AUTH_TOKEN, token).apply()

            getSubscriptionUrl(token)
        }

    private suspend fun getSubscriptionUrl(token: String): String =
        withContext(Dispatchers.IO) {
            val (code, resp) = httpApiRequest(
                "GET", "/api/v1/user/getSubscribe",
                reqHeaders = mapOf("Authorization" to token)
            )
            if (code != 200) throw Exception("获取订阅失败 (HTTP $code)")

            val data = JSONObject(resp).optJSONObject("data")
                ?: throw Exception("订阅响应格式错误")
            val subUrl = data.optString("subscribe_url", "")
            if (subUrl.isEmpty()) throw Exception("订阅链接为空")
            subUrl
        }

    private fun showRegisterWebView() {
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // 注册完成后 xboard 跳转到登录页，回到 app 登录界面
                if (url.contains("/#/login")) showLoginMode()
            }
        }
        // 走 Cloudflare 域名（IP:8080 会被 GFW 间歇拦截导致空白页）
        wv.loadUrl("https://$XBOARD_PANEL_DOMAIN/#/register")
        setContentView(wv)
    }

    private suspend fun importSubscription(url: String) {
        withContext(Dispatchers.IO) {
            // Extract path from subscription URL — handles any domain or IP
            val path = try {
                val uri = java.net.URI(url)
                uri.rawPath + if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
            } catch (e: Exception) {
                "/s/" + url.substringAfterLast("/s/")
            }

            // Fetch Clash YAML via our socket client (bypasses ClashMeta Go HTTP entirely)
            // ClashMetaForAndroid UA makes xboard return Clash YAML instead of base64 proxy list
            val (code, content) = httpApiRequest(
                "GET", path,
                reqHeaders = mapOf("User-Agent" to "ClashMetaForAndroid/2.10.1")
            )
            if (code != 200) throw Exception("获取订阅内容失败 (HTTP $code)")
            if (!content.contains("proxies:") && !content.contains("proxy-providers:")) {
                throw Exception("订阅格式错误 (无节点数据)")
            }

            // Create a File-type profile — ClashMeta reads local file, no HTTP request
            val uuid = withProfile {
                create(type = Profile.Type.File, name = "彩虹猫订阅", source = "")
            }

            // Write subscription YAML into ClashMeta's pending directory
            filesDir.resolve("pending").resolve(uuid.toString())
                .resolve("config.yaml")
                .writeText(content)

            // Commit: File type validates local file, no HTTP fetch needed
            withProfile {
                commit(uuid)
                val profile = queryByUUID(uuid) ?: return@withProfile
                setActive(profile)
            }
        }
    }
}
