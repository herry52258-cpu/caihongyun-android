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
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
        // 关于页 / 登录页展示的版本。发版时和 build.gradle 的 versionName 一起改。
        const val APP_VERSION = "2.11.42"
        const val XBOARD_HOST = "caihongmao.org"
        const val XBOARD_BASE = "https://$XBOARD_HOST"
        // 新面板入口，按这个顺序逐个试。都指向同一个 Xboard，挂在 Cloudflare 后面。只写域名。
        val NEW_PANEL_HOSTS = listOf(
            "nlogin.lianjieni.com",
            "np1.lianjieni.com",
            "login.lianjieni.com",
            "np2.lianjieni.com",
        )
        // 四个新入口都失败后才回退。这些旧地址在国内已被封，只作兜底。
        val OLD_PANEL_HOSTS = listOf(
            "p1.yuntanlu.com",
            "p2.yuntanlu.com",
            "w1.yuntanlu.com",
            "w2.yuntanlu.com",
            "w3.yuntanlu.com",
        )
        // 注册、忘记密码、邀请链接不使用 p1。
        val WEB_PANEL_HOSTS = NEW_PANEL_HOSTS + OLD_PANEL_HOSTS.filter { it != "p1.yuntanlu.com" }
        const val XBOARD_PANEL_DOMAIN = "nlogin.lianjieni.com"
        private const val KEY_PREFERRED_PANEL = "preferred_panel_host"
        private const val CONNECT_TIMEOUT_MS = 1500
        private const val READ_TIMEOUT_MS = 3500

        // 检查更新：先新面板（这些域名上有 /version.json），再下载站，最后旧地址。
        val VERSION_JSON_BASES = listOf(
            "https://nlogin.lianjieni.com",
            "https://np1.lianjieni.com",
            "https://login.lianjieni.com",
            "https://np2.lianjieni.com",
            "https://down.yuntanlu.com",
            "https://ndl.lianjieni.com",
            "https://p2.yuntanlu.com",
            "https://w1.yuntanlu.com",
            "https://w2.yuntanlu.com",
            "https://w3.yuntanlu.com",
            "https://dl.yuntanlu.com",
            "https://p1.yuntanlu.com",
        )
        // 下载站：down → ndl，dl 兜底。
        val DOWNLOAD_BASES = listOf(
            "https://down.yuntanlu.com",
            "https://ndl.lianjieni.com",
            "https://dl.yuntanlu.com",
        )
        const val DOWNLOAD_PAGE = "https://down.yuntanlu.com"

        fun isSetupDone(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return !prefs.getString(KEY_SUBSCRIPTION_URL, null).isNullOrBlank()
        }

        /**
         * 重新拉取订阅并覆盖节点配置。用于「付款开通后刷新节点」——订阅存的是静态文件，
         * 无套餐时导入的是空配置，开通后必须重拉一次才有节点，否则"付了钱却连不上"。
         * 返回 true=成功刷新且含真实节点；false=拉取失败或仍是空配置。
         */
        suspend fun reimportNodes(context: Context): Boolean {
            return try {
                // 与手动刷新同一条路径：用已保存的凭证重拉，并尽量保持当前选中线路
                LineRefresh.refresh(context).ok
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }
        }

        /** 上次成功的面板域名放最前，其余保持原顺序。 */
        fun orderedHosts(base: List<String>): List<String> {
            val preferred = preferredPanel()
            if (preferred.isNullOrBlank() || preferred !in base) return base
            return listOf(preferred) + base.filter { it != preferred }
        }

        fun versionJsonBases(): List<String> {
            val preferred = preferredPanel()?.let { "https://$it" }
            val promotable = NEW_PANEL_HOSTS.map { "https://$it" }.toSet()
            if (preferred != null && preferred in promotable) {
                return listOf(preferred) + VERSION_JSON_BASES.filter { it != preferred }
            }
            return VERSION_JSON_BASES
        }

        /** 注册 / 忘记密码 / 邀请链接用的面板域名。不会是 p1。 */
        fun webPanelHost(): String =
            preferredPanel()?.takeIf { it in WEB_PANEL_HOSTS } ?: XBOARD_PANEL_DOMAIN

        fun panelPageUrl(route: String): String {
            val hash = route.removePrefix("#").removePrefix("/")
            return "https://${webPanelHost()}/#/$hash"
        }

        /**
         * 面板 API。按顺序逐个试：新入口，然后旧地址。
         * 单个地址连不上就尽快换下一个；拿到 HTTP 状态码即停，并记住这个域名。
         */
        fun httpApiRequest(
            method: String,
            path: String,
            reqHeaders: Map<String, String> = emptyMap(),
            body: ByteArray? = null
        ): Pair<Int, String> {
            for (domain in orderedHosts(NEW_PANEL_HOSTS + OLD_PANEL_HOSTS)) {
                try {
                    val result = httpsRequest(domain, method, path, reqHeaders, body)
                    rememberPanel(domain)
                    return result
                } catch (_: Exception) {
                }
            }
            throw Exception("网络不通，请切换网络(WiFi/流量)后重试")
        }

        /** 注册 / 忘记密码 WebView：按顺序探测，跳过 p1，返回第一个能连通的域名。 */
        fun firstReachablePanelDomain(): String {
            for (d in orderedHosts(WEB_PANEL_HOSTS)) {
                try {
                    val (code, _) = httpsRequest(d, "GET", "/api/v1/guest/comm/config", emptyMap(), null)
                    if (code in 200..499) {
                        rememberPanel(d)
                        return d
                    }
                } catch (_: Exception) {
                }
            }
            return XBOARD_PANEL_DOMAIN
        }

        private fun preferredPanel(): String? = try {
            Global.application
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PREFERRED_PANEL, null)
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

        private fun rememberPanel(host: String) {
            try {
                Global.application
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PREFERRED_PANEL, host)
                    .apply()
            } catch (_: Exception) {
            }
        }

        private fun httpsRequest(
            domain: String,
            method: String, path: String,
            reqHeaders: Map<String, String>, body: ByteArray?
        ): Pair<Int, String> {
            val conn = java.net.URL("https://$domain$path")
                .openConnection() as javax.net.ssl.HttpsURLConnection
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            reqHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = (stream?.readBytes() ?: ByteArray(0)).toString(Charsets.UTF_8)
            conn.disconnect()
            return code to text
        }

    }

    private var useUrlMode = false
    private var panelWeb: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLoginMode()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panelWeb != null) {
            closePanelWeb()
            showLoginMode()
            return
        }
        super.onBackPressed()
    }

    private fun showLoginMode() {
        panelWeb = null
        useUrlMode = false
        setContentView(buildLayout())
    }

    private fun showUrlMode() {
        panelWeb = null
        useUrlMode = true
        setContentView(buildLayout())
    }

    private fun closePanelWeb() {
        val wv = panelWeb
        panelWeb = null
        wv?.destroy()
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
            text = "v$APP_VERSION · $XBOARD_HOST"
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
                text = "忘记密码？"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(0xFF7C3AED.toInt())
                setPadding(0, (12 * dp).toInt(), 0, 0)
                isClickable = true
                setOnClickListener { showForgotWebView() }
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
        // 登录刚导入过，回到首页的 60 秒内不必再自动拉一次
        LineRefresh.noteJustFetched(this)
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
        var handled = false
        var sawRegister = false
        // 注册成功后 xboard 会离开 /#/register（新版是自动登录跳 /#/dashboard，旧版跳 /#/login）。
        // 只有「先看到过注册页、随后又离开到别的面板路由」才算注册完成，避免初始加载误触发。
        fun backToAppLoginIfDone(url: String) {
            if (handled) return
            if (url.contains("/register")) { sawRegister = true; return }
            if (sawRegister && url.contains("/#/")) {   // 已离开注册页，跳到 dashboard/login 等
                handled = true
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@SetupActivity, "注册成功，请用刚注册的账号登录", android.widget.Toast.LENGTH_LONG
                    ).show()
                    showLoginMode()
                }
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                backToAppLoginIfDone(url)
            }
            // 关键：xboard 是 hash 路由 SPA，注册后跳 /#/dashboard 不会触发 onPageFinished，
            // 但会触发 doUpdateVisitedHistory（history 变化），在这里捕获才可靠。
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                backToAppLoginIfDone(url)
            }
        }
        panelWeb = wv
        setContentView(wv)
        // 按顺序找一个能打开的面板（不含 p1），再加载注册页。
        Thread {
            val d = firstReachablePanelDomain()
            runOnUiThread { if (panelWeb === wv) wv.loadUrl("https://$d/#/register") }
        }.start()
    }

    private fun showForgotWebView() {
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
        }
        wv.webViewClient = WebViewClient()
        panelWeb = wv
        setContentView(wv)
        Thread {
            val d = firstReachablePanelDomain()
            runOnUiThread { if (panelWeb === wv) wv.loadUrl("https://$d/#/forget") }
        }.start()
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
            // 403/401/404 = 套餐已到期或未开通（订阅内容端点对过期账号返回 403）。
            // 不当作故障：登录仍算成功、放行进首页，由 HomeActivity 显示
            // 「⏰ 会员已到期 · 点右侧续费」并弹出购买引导（maybeShowExpiredPrompt）。
            // 若在此硬报错，用户只看到「获取订阅内容失败 (HTTP 403)」，会误以为软件坏了直接卸载。
            if (code == 403 || code == 401 || code == 404) return@withContext
            if (code != 200) throw Exception("获取订阅内容失败 (HTTP $code)")
            if (!content.contains("proxies:") && !content.contains("proxy-providers:")) {
                throw Exception("订阅格式错误")
            }
            // 无真实节点(无套餐/试用或会员已过期)：xboard 返回的空配置里 proxy-group 引用了
            // 未定义的组，是无效 ClashMeta 配置，导入会失败。这种情况不导入 profile，
            // 但登录仍算成功——进首页显示订阅状态、引导用户开通套餐。开通后 reimportNodes 会补上节点。
            val hasNodes = content.contains("type: vless") || content.contains("type: trojan") ||
                content.contains("type: ss") || content.contains("proxy-providers:")
            if (!hasNodes) return@withContext

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
