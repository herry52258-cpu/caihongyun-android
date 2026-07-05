package com.github.kr328.clash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.webkit.*
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
import javax.net.ssl.*

class SetupActivity : AppCompatActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startClashService()
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val PREFS_NAME = "caihongyun_prefs"
        const val KEY_SUBSCRIPTION_URL = "subscription_url"
        const val XBOARD_HOST = "13141069.xyz"
        const val XBOARD_BASE = "https://$XBOARD_HOST"
        // Direct server IP + port 8080 (plain HTTP, no TLS).
        // GFW strips SNI from TLS ClientHello on Cloudflare IPs — HTTPS is unworkable.
        // xboard listens on 0.0.0.0:8080, directly reachable from the internet.
        const val XBOARD_IP = "67.215.237.125"
        const val XBOARD_API_PORT = 8080
        // Cloudflare IPs still used for WebView HTTPS (registration page)
        val CF_FALLBACK_IPS = listOf("172.67.221.198", "104.21.78.130", XBOARD_IP)

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
        private fun httpApiRequest(
            method: String,
            path: String,
            reqHeaders: Map<String, String> = emptyMap(),
            body: ByteArray? = null
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

        /** HTTPS via raw SSLSocket — used only for WebView (register page). */
        private fun directHttps(
            ip: String,
            method: String,
            path: String,
            reqHeaders: Map<String, String> = emptyMap(),
            body: ByteArray? = null
        ): RawResponse {
            val tcp = Socket()
            tcp.connect(InetSocketAddress(InetAddress.getByName(ip), 443), 8000)
            tcp.soTimeout = 15000
            val ssl = SSLContext.getDefault().socketFactory
                .createSocket(tcp, XBOARD_HOST, 443, true) as SSLSocket
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(XBOARD_HOST, ssl.session)) {
                ssl.close()
                throw SSLHandshakeException("证书验证失败: $XBOARD_HOST")
            }
            val out = ssl.outputStream
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
            val raw = ssl.inputStream.readBytes()
            ssl.close()
            return parseRawHttp(raw)
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
            text = "🌈 彩虹云"
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
            text = "v1.0.9 · $XBOARD_HOST"
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
                text = "可从管理员或彩虹云群里获取订阅链接"
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
            startClashService()
            startActivity(Intent(this, MainActivity::class.java))
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
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val host = request.url.host ?: return null
                if (!host.contains(XBOARD_HOST)) return null
                return try {
                    val path = request.url.path ?: "/"
                    val query = request.url.query
                    val fullPath = if (query != null) "$path?$query" else path
                    val filtered = request.requestHeaders
                        .filter { (k, _) -> !k.equals("Host", ignoreCase = true) }
                    val r = directHttps(CF_FALLBACK_IPS[0], request.method, fullPath, filtered)
                    val ct = r.headers["content-type"] ?: "text/html"
                    val mime = ct.split(";").firstOrNull()?.trim() ?: "text/html"
                    val charset = ct.split(";")
                        .firstOrNull { it.trim().startsWith("charset") }
                        ?.split("=")?.getOrNull(1)?.trim() ?: "utf-8"
                    WebResourceResponse(mime, charset, r.body.inputStream())
                } catch (_: Exception) { null }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url.contains("/login") || url.contains("/#/")) showLoginMode()
            }
        }
        wv.loadUrl("$XBOARD_BASE/#/register")
        setContentView(wv)
    }

    private suspend fun importSubscription(url: String) {
        withProfile {
            val uuid = create(type = Profile.Type.Url, name = "彩虹云订阅", source = url)
            commit(uuid)
            val profile = queryByUUID(uuid) ?: return@withProfile
            setActive(profile)
        }
    }
}
