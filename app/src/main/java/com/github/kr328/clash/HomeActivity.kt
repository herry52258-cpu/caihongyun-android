package com.github.kr328.clash

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startClashService()
    }

    private lateinit var nodeName: TextView
    private lateinit var subStatus: TextView
    private lateinit var statusMain: TextView
    private lateinit var statusSub: TextView
    private lateinit var connectBtn: TextView
    private lateinit var ring: ImageView
    private lateinit var cat: ImageView
    private lateinit var ringAnim: ObjectAnimator

    private var connecting = false
    private var connectStartAt = 0L
    private var selectorGroup: String? = null
    private var webOverlay: WebView? = null

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!SetupActivity.isSetupDone(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(buildLayout())
        startStatePolling()
    }

    override fun onBackPressed() {
        if (webOverlay != null) { closeWeb(); return }
        super.onBackPressed()
    }

    // ---------------- UI ----------------

    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

    private fun gradientPill(radius: Int, colors: IntArray) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            cornerRadius = dp(radius).toFloat()
        }

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0d0b18")) }

        // top rainbow strip
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFFff1e6e.toInt(), 0xFFffe500.toInt(), 0xFF00f076.toInt(), 0xFF00c8ff.toInt(), 0xFFc433ff.toInt())
            )
        }, FrameLayout.LayoutParams(MATCH_PARENT, dp(4)))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(22))
        }
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // top bar: node chip + menu
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nodeChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x14FFFFFF, 16, 1, 0x1AFFFFFF)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setOnClickListener { pickNode() }
        }
        nodeChip.addView(TextView(this).apply { text = "🌐"; textSize = 17f }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(10) })
        nodeName = TextView(this).apply { text = "选择节点"; setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        nodeChip.addView(nodeName, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        nodeChip.addView(TextView(this).apply { text = "⌄"; setTextColor(Color.parseColor("#9b93b8")); textSize = 16f })
        topBar.addView(nodeChip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val menuBtn = TextView(this).apply {
            text = "☰"; setTextColor(Color.parseColor("#cfc7e6")); textSize = 20f; gravity = Gravity.CENTER
            background = rounded(0x14FFFFFF, 14, 1, 0x1AFFFFFF)
            setOnClickListener { showMenu(this) }
        }
        topBar.addView(menuBtn, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(8) })
        col.addView(topBar)

        // subscription status bar
        val subBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x29ff2d8c.toInt(), 0x24c433ff.toInt())
            ).apply { cornerRadius = dp(15).toFloat(); setStroke(dp(1), 0x52ff2d8c.toInt()) }
            setPadding(dp(14), dp(11), dp(12), dp(11))
        }
        subStatus = TextView(this).apply { text = "🎁 彩虹猫会员 · 点右侧升级"; setTextColor(Color.WHITE); textSize = 13f }
        subBar.addView(subStatus, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        subBar.addView(TextView(this).apply {
            text = "升级 ›"; setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = gradientPill(12, intArrayOf(0xFFff2d6e.toInt(), 0xFFff8a3c.toInt()))
            setPadding(dp(15), dp(8), dp(15), dp(8))
            setOnClickListener { startActivity(Intent(this@HomeActivity, PurchaseActivity::class.java)) }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(subBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(11) })

        // 邀请好友返利入口
        val inviteBar = TextView(this).apply {
            text = "🎁 邀请好友注册，拉新得返利 ›"
            setTextColor(Color.parseColor("#e0b3ff")); textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
            background = rounded(0x14FFFFFF, 13, 1, 0x40c433ff.toInt())
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setOnClickListener { startActivity(Intent(this@HomeActivity, InviteActivity::class.java)) }
        }
        col.addView(inviteBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(9) })

        // stage: ring + cat
        val stage = FrameLayout(this)
        ring = ImageView(this).apply { setImageResource(R.drawable.ring_rainbow); alpha = 0f }
        cat = ImageView(this).apply { setImageResource(R.drawable.cat_hero); alpha = 0.9f }
        stage.addView(ring, FrameLayout.LayoutParams(dp(230), dp(230), Gravity.CENTER))
        stage.addView(cat, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER))
        col.addView(stage, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        ringAnim = ObjectAnimator.ofFloat(ring, "rotation", 0f, 360f).apply {
            setDuration(1400L); repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator()
        }

        // status text
        statusMain = TextView(this).apply { text = "● 未连接"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        statusSub = TextView(this).apply { text = "点下方按钮，一键开启"; setTextColor(Color.parseColor("#9b93b8")); textSize = 12.5f; gravity = Gravity.CENTER }
        col.addView(statusMain, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(statusSub, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        // connect button
        connectBtn = TextView(this).apply {
            text = "🚀 一键连接"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(17), 0, dp(17))
            background = gradientPill(20, intArrayOf(0xFFff1e6e.toInt(), 0xFFff6a00.toInt(), 0xFF00c8ff.toInt(), 0xFFc433ff.toInt()))
            setOnClickListener { onConnectClick() }
        }
        col.addView(connectBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(22) })

        col.addView(TextView(this).apply {
            text = "安全自由 · 专属 LGBT 社群"; setTextColor(Color.parseColor("#6f6a86")); textSize = 12f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })

        return root
    }

    // ---------------- connect ----------------

    private fun onConnectClick() {
        if (Remote.broadcasts.clashRunning) {
            stopClashService()
            connecting = false
            return
        }
        lifecycleScope.launch {
            val active = withProfile { queryActive() }
            if (active == null || !active.imported) {
                toast("订阅尚未就绪，请从菜单重新登录")
                return@launch
            }
            connecting = true
            connectStartAt = System.currentTimeMillis()
            val req = startClashService()
            if (req != null) vpnPermission.launch(req)
        }
    }

    private fun startStatePolling() {
        lifecycleScope.launch {
            while (isActive) {
                render()
                kotlinx.coroutines.delay(700)
            }
        }
    }

    private suspend fun render() {
        if (webOverlay != null) return
        val running = Remote.broadcasts.clashRunning
        if (running) connecting = false
        val connectingNow = connecting && (System.currentTimeMillis() - connectStartAt < 15000)

        if (running) {
            var node = "已连接"
            try {
                val g = selectorGroupName()
                if (g != null) node = withClash { queryProxyGroup(g, ProxySort.Default) }.now
            } catch (_: Exception) {}
            statusMain.text = "● 已连接"
            statusMain.setTextColor(Color.parseColor("#22e08a"))
            statusSub.text = if (node.isNotEmpty()) "节点：${nf(node)}" else "已连接"
            if (node.isNotEmpty()) nodeName.text = nf(node)
            cat.alpha = 1f
            ring.alpha = 1f
            if (!ringAnim.isStarted) ringAnim.start()
            ringAnim.setDuration(4000L)
            connectBtn.text = "● 已连接　点击断开"
            connectBtn.background = rounded(0x0FFFFFFF, 20, 2, 0x99ff5078.toInt())
            connectBtn.setTextColor(Color.parseColor("#ff6b8b"))
        } else if (connectingNow) {
            statusMain.text = "● 连接中…"
            statusMain.setTextColor(Color.parseColor("#ffd54a"))
            statusSub.text = "正在建立安全隧道"
            cat.alpha = 0.95f
            ring.alpha = 1f
            if (!ringAnim.isStarted) ringAnim.start()
            ringAnim.setDuration(1200L)
        } else {
            statusMain.text = "● 未连接"
            statusMain.setTextColor(Color.WHITE)
            statusSub.text = "点下方按钮，一键开启"
            cat.alpha = 0.88f
            ring.alpha = 0f
            if (ringAnim.isStarted) ringAnim.cancel()
            ring.rotation = 0f
            connectBtn.text = "🚀 一键连接"
            connectBtn.background = gradientPill(20, intArrayOf(0xFFff1e6e.toInt(), 0xFFff6a00.toInt(), 0xFF00c8ff.toInt(), 0xFFc433ff.toInt()))
            connectBtn.setTextColor(Color.WHITE)
        }
    }

    private suspend fun selectorGroupName(): String? {
        selectorGroup?.let { return it }
        return try {
            val names = withClash { queryProxyGroupNames(true) }
            names.firstOrNull()?.also { selectorGroup = it }
        } catch (_: Exception) { null }
    }

    // ---------------- node picker ----------------

    private fun pickNode() {
        if (!Remote.broadcasts.clashRunning) { toast("请先连接后再选择节点"); return }
        lifecycleScope.launch {
            try {
                val g = selectorGroupName() ?: run { toast("暂无可选节点"); return@launch }
                val group = withClash { queryProxyGroup(g, ProxySort.Default) }
                val proxies = group.proxies
                if (proxies.isEmpty()) { toast("暂无可选节点"); return@launch }
                val labels = proxies.map { p ->
                    val d = if (p.delay in 1..9999) "  ${p.delay}ms" else ""
                    (if (p.name == group.now) "✓ " else "   ") + nf(p.name) + d
                }.toTypedArray()
                AlertDialog.Builder(this@HomeActivity)
                    .setTitle("选择节点")
                    .setItems(labels) { _, which ->
                        lifecycleScope.launch {
                            try {
                                withClash { patchSelector(g, proxies[which].name) }
                                nodeName.text = nf(proxies[which].name)
                            } catch (_: Exception) { toast("切换失败") }
                        }
                    }
                    .show()
            } catch (_: Exception) { toast("获取节点失败") }
        }
    }

    // ---------------- menu ----------------

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "购买 / 我的订阅")
        pm.menu.add(0, 6, 1, "🎁 邀请好友返利")
        pm.menu.add(0, 2, 2, "复制订阅链接")
        pm.menu.add(0, 3, 3, "高级设置")
        pm.menu.add(0, 4, 4, "切换账号")
        pm.menu.add(0, 5, 5, "关于")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> startActivity(Intent(this@HomeActivity, PurchaseActivity::class.java))
                6 -> startActivity(Intent(this@HomeActivity, InviteActivity::class.java))
                2 -> copySubscription()
                3 -> startActivity(Intent(this, MainActivity::class.java))
                4 -> switchAccount()
                5 -> toast("彩虹猫 v1.0.23 · ${SetupActivity.XBOARD_HOST}")
            }
            true
        }
        pm.show()
    }

    private fun copySubscription() {
        val url = getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SetupActivity.KEY_SUBSCRIPTION_URL, null)
        if (url.isNullOrEmpty()) { toast("暂无订阅链接"); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("subscription", url))
        toast("订阅链接已复制")
    }

    private fun switchAccount() {
        if (Remote.broadcasts.clashRunning) stopClashService()
        getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(SetupActivity.KEY_SUBSCRIPTION_URL).apply()
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    // ---------------- in-app web (purchase / panel) ----------------

    private fun openWeb(url: String) {
        // 用登录时存下的 token 提前设 access_token cookie，面板 SPA 读到即自动登录
        val base = "http://${SetupActivity.XBOARD_IP}:${SetupActivity.XBOARD_API_PORT}"
        val token = getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SetupActivity.KEY_AUTH_TOKEN, null)
        val cm = android.webkit.CookieManager.getInstance()
        cm.setAcceptCookie(true)
        if (!token.isNullOrEmpty()) {
            cm.setCookie(base, "access_token=" + token.replace(" ", "%20") + "; path=/")
        }
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            cm.setAcceptThirdPartyCookies(this, true)
            setBackgroundColor(Color.parseColor("#0d0b18"))
            webViewClient = WebViewClient()
            loadUrl(url)
        }
        cm.flush()
        webOverlay = wv
        setContentView(wv)
    }

    private fun closeWeb() {
        webOverlay?.destroy()
        webOverlay = null
        // 复用同一个轮询协程（读取字段，buildLayout 已重新赋值视图引用）
        setContentView(buildLayout())
    }

    // 节点名前加国旗，好区分又好看
    private fun flagFor(name: String): String = when {
        name.contains("美国") || name.contains("洛杉矶") || name.contains("弗吉尼亚") || name.contains("硅谷") || name.contains("US", true) -> "🇺🇸"
        name.contains("日本") || name.contains("东京") || name.contains("大阪") || name.contains("JP", true) -> "🇯🇵"
        name.contains("香港") || name.contains("HK", true) -> "🇭🇰"
        name.contains("新加坡") || name.contains("狮城") || name.contains("SG", true) -> "🇸🇬"
        name.contains("韩国") || name.contains("首尔") || name.contains("KR", true) -> "🇰🇷"
        name.contains("台湾") || name.contains("台北") || name.contains("TW", true) -> "🇹🇼"
        name.contains("英国") || name.contains("伦敦") || name.contains("UK", true) -> "🇬🇧"
        name.contains("德国") || name.contains("DE", true) -> "🇩🇪"
        name.contains("自动") -> "🎯"
        name.contains("故障") || name.contains("转移") -> "🔀"
        name.contains("直连") || name.contains("DIRECT", true) -> "🏠"
        name.contains("拒绝") || name.contains("REJECT", true) -> "🚫"
        else -> "🌐"
    }
    private fun nf(name: String) = "${flagFor(name)} $name"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
