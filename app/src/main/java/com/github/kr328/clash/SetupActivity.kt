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
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
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
        // 13141069.xyz Cloudflare CDN IP，DNS 被污染时直连用
        val CF_FALLBACK_IPS = listOf("172.67.221.198", "104.21.78.130")

        fun isSetupDone(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return !prefs.getString(KEY_SUBSCRIPTION_URL, null).isNullOrBlank()
        }

        // 创建绕过 DNS、通过硬编码 IP 直连的 HTTPS 连接
        // 关键修复: 把 XBOARD_HOST (域名) 传给 baseFactory，让 BoringSSL 正确设置 SNI
        // 错误做法是把 IP 传给 baseFactory，会导致 SNI 缺失→Cloudflare 拒绝握手
        private fun openConnection(ip: String, path: String): HttpURLConnection {
            val conn = URL("https://$ip$path").openConnection() as HttpsURLConnection
            val baseFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
            val baseVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            conn.sslSocketFactory = object : SSLSocketFactory() {
                override fun getDefaultCipherSuites() = baseFactory.defaultCipherSuites
                override fun getSupportedCipherSuites() = baseFactory.supportedCipherSuites
                // Android HttpsURLConnection 走这条路径: 已有 TCP socket，包一层 SSL
                // 传 XBOARD_HOST 而非 h(=IP)，BoringSSL 才会在 ClientHello 里带正确 SNI
                override fun createSocket(s: Socket, h: String, p: Int, ac: Boolean): Socket =
                    baseFactory.createSocket(s, XBOARD_HOST, p, ac)
                // 下面三个用于新建 TCP+SSL 的情况，直连硬编码 IP 但用域名做 SNI
                override fun createSocket(h: String, p: Int): Socket {
                    val plain = Socket(ip, p)
                    return baseFactory.createSocket(plain, XBOARD_HOST, p, true)
                }
                override fun createSocket(h: String, p: Int, la: InetAddress, lp: Int): Socket {
                    val plain = Socket(ip, p)
                    return baseFactory.createSocket(plain, XBOARD_HOST, p, true)
                }
                override fun createSocket(a: InetAddress, p: Int): Socket {
                    val plain = Socket(ip, p)
                    return baseFactory.createSocket(plain, XBOARD_HOST, p, true)
                }
                override fun createSocket(a: InetAddress, p: Int, la: InetAddress, lp: Int): Socket {
                    val plain = Socket(ip, p)
                    return baseFactory.createSocket(plain, XBOARD_HOST, p, true)
                }
            }
            conn.hostnameVerifier = HostnameVerifier { _, session ->
                baseVerifier.verify(XBOARD_HOST, session)
            }
            conn.setRequestProperty("Host", XBOARD_HOST)
            return conn
        }

        // 直接用硬编码 Cloudflare IP，完全跳过系统 DNS（防污染）
        fun openApiConnection(path: String): HttpURLConnection {
            var last: Exception = Exception("连接失败，请检查网络")
            for (ip in CF_FALLBACK_IPS) {
                try {
                    return openConnection(ip, path).also {
                        it.connectTimeout = 8000
                        it.readTimeout = 15000
                    }
                } catch (e: Exception) { last = e }
            }
            throw last
        }
    }

    private var useUrlMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLoginMode()
    }

    private fun showLoginMode() {
        useUrlMode = false
        val layout = buildLayout()
        setContentView(layout)
    }

    private fun showUrlMode() {
        useUrlMode = true
        val layout = buildLayout()
        setContentView(layout)
    }

    private fun buildLayout(): LinearLayout {
        val dp = resources.displayMetrics.density

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val logo = TextView(this).apply {
            text = "🌈 彩虹云"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "安全自由，专属LGBT社群"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        val versionLabel = TextView(this).apply {
            text = "v1.0.7 · $XBOARD_HOST"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, (24 * dp).toInt())
        }
        layout.addView(logo)
        layout.addView(subtitle)
        layout.addView(versionLabel)

        val statusText = TextView(this).apply {
            text = ""
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFFF5555.toInt())
        }

        if (!useUrlMode) {
            // ── 邮箱密码登录 ──
            val emailLabel = TextView(this).apply { text = "邮箱" }
            val emailInput = EditText(this).apply {
                hint = "请输入邮箱"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            val passwordLabel = TextView(this).apply {
                text = "密码"
                setPadding(0, (16 * dp).toInt(), 0, 0)
            }
            val passwordInput = EditText(this).apply {
                hint = "请输入密码"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val loginBtn = Button(this).apply {
                text = "登录并连接"
            }
            val switchBtn = TextView(this).apply {
                text = "已有订阅链接？直接粘贴"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFF7C3AED.toInt())
                setPadding(0, (16 * dp).toInt(), 0, 0)
                isClickable = true
                setOnClickListener { showUrlMode() }
            }
            val registerLink = TextView(this).apply {
                text = "还没有账号？点此注册"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(0xFF7C3AED.toInt())
                setPadding(0, (12 * dp).toInt(), 0, 0)
                isClickable = true
                setOnClickListener { showRegisterWebView() }
            }

            layout.addView(emailLabel)
            layout.addView(emailInput)
            layout.addView(passwordLabel)
            layout.addView(passwordInput)
            layout.addView(loginBtn)
            layout.addView(statusText)
            layout.addView(switchBtn)
            layout.addView(registerLink)

            loginBtn.setOnClickListener {
                val email = emailInput.text.toString().trim()
                val password = passwordInput.text.toString().trim()
                if (email.isEmpty() || password.isEmpty()) {
                    statusText.text = "请输入邮箱和密码"
                    return@setOnClickListener
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
            // ── 直接粘贴订阅链接 ──
            val urlLabel = TextView(this).apply {
                text = "订阅链接"
            }
            val urlInput = EditText(this).apply {
                hint = "粘贴 clash:// 或 https:// 订阅链接"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
                minLines = 2
            }
            val hint = TextView(this).apply {
                text = "可从管理员或彩虹云群里获取订阅链接"
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                setPadding(0, (8 * dp).toInt(), 0, 0)
            }
            val confirmBtn = Button(this).apply {
                text = "导入并连接"
            }
            val switchBtn = TextView(this).apply {
                text = "返回邮箱登录"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFF7C3AED.toInt())
                setPadding(0, (16 * dp).toInt(), 0, 0)
                isClickable = true
                setOnClickListener { showLoginMode() }
            }

            layout.addView(urlLabel)
            layout.addView(urlInput)
            layout.addView(hint)
            layout.addView(confirmBtn)
            layout.addView(statusText)
            layout.addView(switchBtn)

            confirmBtn.setOnClickListener {
                val url = urlInput.text.toString().trim()
                if (url.isEmpty()) {
                    statusText.text = "请粘贴订阅链接"
                    return@setOnClickListener
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
            .edit()
            .putString(KEY_SUBSCRIPTION_URL, subscriptionUrl)
            .apply()

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
            val conn = openApiConnection("/api/v1/passport/auth/login")
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("email", email)
                put("password", password)
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = conn.responseCode
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            if (responseCode != 200) throw Exception("登录失败，请检查邮箱和密码")

            val json = JSONObject(response)
            val data = json.optJSONObject("data") ?: throw Exception("登录响应格式错误")
            val token = data.optString("auth_data", "")
            if (token.isEmpty()) throw Exception("获取 token 失败")

            getSubscriptionUrl(token)
        }

    private suspend fun getSubscriptionUrl(token: String): String =
        withContext(Dispatchers.IO) {
            val conn = openApiConnection("/api/v1/user/getSubscribe")
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", token)
            conn.setRequestProperty("Accept", "application/json")

            val responseCode = conn.responseCode
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            if (responseCode != 200) throw Exception("获取订阅失败")

            val json = JSONObject(response)
            val data = json.optJSONObject("data") ?: throw Exception("订阅响应格式错误")
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
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host ?: return null
                if (!host.contains(XBOARD_HOST)) return null
                return try {
                    val path = request.url.path ?: "/"
                    val query = request.url.query
                    val fullPath = if (query != null) "$path?$query" else path
                    val conn = openConnection(CF_FALLBACK_IPS[0], fullPath)
                    conn.requestMethod = request.method
                    conn.connectTimeout = 10000
                    conn.readTimeout = 15000
                    request.requestHeaders.forEach { (k, v) ->
                        if (k != null && k != "Host") conn.setRequestProperty(k, v)
                    }
                    val mime = conn.contentType?.split(";")?.firstOrNull() ?: "text/html"
                    val encoding = conn.contentEncoding ?: "utf-8"
                    WebResourceResponse(mime, encoding, conn.inputStream)
                } catch (_: Exception) { null }
            }

            override fun onPageFinished(view: WebView, url: String) {
                // 注册完成后返回登录页
                if (url.contains("/login") || url.contains("/#/")) {
                    showLoginMode()
                }
            }
        }
        // 用域名加载，shouldInterceptRequest 会拦截并通过硬编码 IP 路由，SNI 正确
        wv.loadUrl("$XBOARD_BASE/#/register")
        setContentView(wv)
    }

    private suspend fun importSubscription(url: String) {
        withProfile {
            val uuid = create(
                type = Profile.Type.Url,
                name = "彩虹云订阅",
                source = url,
            )
            commit(uuid)
            val profile = queryByUUID(uuid) ?: return@withProfile
            setActive(profile)
        }
    }
}
