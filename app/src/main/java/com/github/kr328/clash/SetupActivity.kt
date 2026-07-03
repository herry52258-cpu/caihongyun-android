package com.github.kr328.clash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SetupActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "caihongyun_prefs"
        const val KEY_SUBSCRIPTION_URL = "subscription_url"
        const val XBOARD_BASE = "https://13141069.xyz"

        fun isSetupDone(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return !prefs.getString(KEY_SUBSCRIPTION_URL, null).isNullOrBlank()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(80, 120, 80, 80)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val logo = TextView(this).apply {
            text = "🌈 彩虹云"
            textSize = 32f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val subtitle = TextView(this).apply {
            text = "安全自由，专属LGBT社群"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 60)
        }

        val emailLabel = TextView(this).apply { text = "邮箱" }
        val emailInput = EditText(this).apply {
            hint = "请输入邮箱"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        val passwordLabel = TextView(this).apply {
            text = "密码"
            setPadding(0, 24, 0, 0)
        }
        val passwordInput = EditText(this).apply {
            hint = "请输入密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val loginBtn = Button(this).apply {
            text = "登录并连接"
            setPadding(0, 32, 0, 0)
        }

        val statusText = TextView(this).apply {
            text = ""
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
            setTextColor(0xFFFF5555.toInt())
        }

        val registerLink = TextView(this).apply {
            text = "还没有账号？点此注册"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF7C3AED.toInt())
            setPadding(0, 32, 0, 0)
            isClickable = true
            setOnClickListener {
                val intent = Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("$XBOARD_BASE/#/register"))
                startActivity(intent)
            }
        }

        layout.addView(logo)
        layout.addView(subtitle)
        layout.addView(emailLabel)
        layout.addView(emailInput)
        layout.addView(passwordLabel)
        layout.addView(passwordInput)
        layout.addView(loginBtn)
        layout.addView(statusText)
        layout.addView(registerLink)

        setContentView(layout)

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

                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_SUBSCRIPTION_URL, subscriptionUrl)
                        .apply()

                    startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    loginBtn.isEnabled = true
                    statusText.setTextColor(0xFFFF5555.toInt())
                    statusText.text = e.message ?: "登录失败，请重试"
                }
            }
        }
    }

    private suspend fun doLogin(email: String, password: String): String =
        withContext(Dispatchers.IO) {
            val loginUrl = URL("$XBOARD_BASE/api/v1/passport/auth/login")
            val conn = loginUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val body = JSONObject().apply {
                put("email", email)
                put("password", password)
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = conn.responseCode
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            if (responseCode != 200) {
                throw Exception("登录失败，请检查邮箱和密码")
            }

            val json = JSONObject(response)
            val data = json.optJSONObject("data")
                ?: throw Exception("登录响应格式错误")

            val token = data.optString("auth_data", "")
            if (token.isEmpty()) throw Exception("获取 token 失败")

            getSubscriptionUrl(token)
        }

    private suspend fun getSubscriptionUrl(token: String): String =
        withContext(Dispatchers.IO) {
            val url = URL("$XBOARD_BASE/api/v1/user/getSubscribe")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", token)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val responseCode = conn.responseCode
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            if (responseCode != 200) throw Exception("获取订阅失败")

            val json = JSONObject(response)
            val data = json.optJSONObject("data")
                ?: throw Exception("订阅响应格式错误")

            val subUrl = data.optString("subscribe_url", "")
            if (subUrl.isEmpty()) throw Exception("订阅链接为空")

            subUrl
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
