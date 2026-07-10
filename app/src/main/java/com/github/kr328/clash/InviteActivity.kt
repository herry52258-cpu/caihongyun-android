package com.github.kr328.clash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class InviteActivity : AppCompatActivity() {

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    private var token: String? = null
    private var inviteLink: String = ""

    private lateinit var statInvited: TextView
    private lateinit var statRate: TextView
    private lateinit var statCommission: TextView
    private lateinit var linkText: TextView
    private lateinit var copyBtn: TextView

    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke > 0) setStroke(dp(stroke), strokeColor) }
    private fun pill(radius: Int, colors: IntArray) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply { cornerRadius = dp(radius).toFloat() }
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SetupActivity.KEY_AUTH_TOKEN, null)
        setContentView(buildLayout())
        if (token.isNullOrEmpty()) {
            toast("请先重新登录后再邀请")
            startActivity(Intent(this, SetupActivity::class.java)); finish(); return
        }
        loadInvite()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0d0b18"))
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        // 顶部：返回 + 标题
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(tv("‹", 30f, Color.WHITE, true).apply {
            setPadding(0, 0, dp(12), 0); setOnClickListener { finish() }
        })
        top.addView(tv("邀请好友 · 赚返利", 20f, Color.WHITE, true))
        root.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 数据卡片
        val statRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun statCard(valueView: TextView, label: String): LinearLayout {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = rounded(0x0DFFFFFF, 12)
                setPadding(dp(6), dp(14), dp(6), dp(14))
            }
            valueView.gravity = Gravity.CENTER
            c.addView(valueView)
            c.addView(tv(label, 12f, Color.parseColor("#9b93b8")).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
            return c
        }
        statInvited = tv("0 人", 17f, Color.parseColor("#e0b3ff"), true)
        statRate = tv("10%", 17f, Color.parseColor("#e0b3ff"), true)
        statCommission = tv("¥0.00", 17f, Color.parseColor("#e0b3ff"), true)
        statRow.addView(statCard(statInvited, "已邀请"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        statRow.addView(statCard(statRate, "返利比例"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        statRow.addView(statCard(statCommission, "可用佣金"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(statRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(22) })

        root.addView(tv("你的专属邀请链接", 13f, Color.parseColor("#9b93b8")).apply { setPadding(dp(2), 0, 0, 0) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(22) })

        // 链接框 + 复制
        val linkBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x12FFFFFF, 12, 1, 0x1AFFFFFF)
            setPadding(dp(14), dp(6), dp(6), dp(6))
        }
        linkText = tv("生成中…", 13f, Color.WHITE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        linkBox.addView(linkText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        copyBtn = TextView(this).apply {
            text = "复制"; setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            background = pill(9, intArrayOf(0xFFe040fb.toInt(), 0xFF7c4dff.toInt()))
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener { copyLink() }
        }
        linkBox.addView(copyBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
        root.addView(linkBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        // 分享按钮
        val shareBtn = tv("分享给好友", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = pill(14, intArrayOf(0xFFff1e6e.toInt(), 0xFF7c4dff.toInt()))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { shareLink() }
        }
        root.addView(shareBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })

        root.addView(tv("好友通过你的链接注册并购买，你可获得返利，佣金可提现或抵扣续费。", 12.5f, Color.parseColor("#6f6a86")).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

        return ScrollView(this).apply { addView(root) }
    }

    private fun loadInvite() {
        val auth = mapOf("Authorization" to token!!)
        lifecycleScope.launch {
            try {
                suspend fun fetch(): JSONObject = withContext(Dispatchers.IO) {
                    val (_, r) = SetupActivity.httpApiRequest("GET", "/api/v1/user/invite/fetch", auth)
                    JSONObject(r)
                }
                var j = fetch()
                var codes = j.optJSONObject("data")?.optJSONArray("codes")
                if (codes == null || codes.length() == 0) {
                    // 没有邀请码则先生成
                    withContext(Dispatchers.IO) { SetupActivity.httpApiRequest("GET", "/api/v1/user/invite/save", auth) }
                    j = fetch()
                    codes = j.optJSONObject("data")?.optJSONArray("codes")
                }
                val data = j.optJSONObject("data")
                val code = if (codes != null && codes.length() > 0) codes.getJSONObject(0).optString("code") else ""
                inviteLink = if (code.isNotEmpty()) "https://${SetupActivity.XBOARD_PANEL_DOMAIN}/#/register?code=$code" else ""
                linkText.text = if (inviteLink.isNotEmpty()) inviteLink else "生成失败，请重试"
                val stat = data?.optJSONArray("stat")
                if (stat != null && stat.length() >= 5) {
                    statInvited.text = "${stat.optInt(0)} 人"
                    statRate.text = "${stat.optInt(3)}%"
                    statCommission.text = "¥${"%.2f".format(stat.optInt(4) / 100.0)}"
                }
            } catch (e: Exception) {
                toast("加载失败：${e.message}")
            }
        }
    }

    private fun copyLink() {
        if (inviteLink.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("邀请链接", inviteLink))
        copyBtn.text = "已复制"
        toast("邀请链接已复制")
    }

    private fun shareLink() {
        if (inviteLink.isEmpty()) return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "彩虹猫 · 安全上网，专属 LGBT 社群 🌈 用我的邀请链接注册：$inviteLink")
        }
        startActivity(Intent.createChooser(share, "分享邀请链接"))
    }
}
