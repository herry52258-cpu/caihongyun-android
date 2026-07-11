package com.github.kr328.clash

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PurchaseActivity : AppCompatActivity() {

    data class PlanItem(val id: Int, val name: String, val content: String,
                        val price: Int, val period: String, val periodLabel: String,
                        val origPrice: Int? = null)
    data class PayItem(val id: Int, val name: String)

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    private var token: String? = null
    private val plans = ArrayList<PlanItem>()
    private val payments = ArrayList<PayItem>()
    private var selected: PlanItem? = null

    private lateinit var heroSub: TextView
    private lateinit var planBox: LinearLayout
    private lateinit var ctaBtn: TextView
    private val cards = HashMap<Int, LinearLayout>()
    private var webOverlay: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SetupActivity.KEY_AUTH_TOKEN, null)
        setContentView(buildScaffold())
        if (token.isNullOrEmpty()) {
            toast("请先重新登录后再购买")
            startActivity(Intent(this, SetupActivity::class.java)); finish(); return
        }
        loadData()
    }

    override fun onBackPressed() {
        if (webOverlay != null) { closeWeb(); return }
        super.onBackPressed()
    }

    // ---------- helpers ----------
    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke > 0) setStroke(dp(stroke), strokeColor) }
    private fun pill(radius: Int, colors: IntArray) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply { cornerRadius = dp(radius).toFloat() }
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun yuan(cents: Int) = if (cents % 100 == 0) "¥${cents / 100}" else "¥${cents / 100.0}"

    // ---------- UI ----------
    private fun buildScaffold(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0d0b18"))
        }
        // rainbow strip
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFFff1e6e.toInt(), 0xFFffe500.toInt(), 0xFF00f076.toInt(), 0xFF00c8ff.toInt(), 0xFFc433ff.toInt()))
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(4)))
        // header
        val hd = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(16), dp(8))
        }
        hd.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(Color.parseColor("#cfc7e6")); setPadding(0, 0, dp(12), dp(4))
            setOnClickListener { onBackPressed() }
        })
        hd.addView(tv("开通彩虹猫会员", 18f, Color.WHITE, true))
        root.addView(hd)

        // scroll body
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(16)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // hero status
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x29ff2d8c.toInt(), 0x22c433ff.toInt()))
                .apply { cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0x47ff2d8c.toInt()) }
            setPadding(dp(15), dp(13), dp(15), dp(13))
        }
        hero.addView(tv("彩虹猫会员", 17f, Color.WHITE, true))
        heroSub = tv("加载中…", 12f, Color.parseColor("#c9a9d6")).apply { setPadding(0, dp(3), 0, 0) }
        hero.addView(heroSub)
        body.addView(hero)

        // benefits
        val ben = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), dp(14), dp(2), dp(6)) }
        val benL = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val benR = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf("专属高速专线", "无广告纯净").forEach { benL.addView(tv("✓  $it", 13f, Color.parseColor("#d7d0ec")).apply { setPadding(0, dp(4), 0, dp(4)) }) }
        listOf("稳定不掉线", "无日志·隐私保护").forEach { benR.addView(tv("✓  $it", 13f, Color.parseColor("#d7d0ec")).apply { setPadding(0, dp(4), 0, dp(4)) }) }
        ben.addView(benL, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        ben.addView(benR, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        body.addView(ben)

        // plan container
        planBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        body.addView(planBox)

        // footer
        val foot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(16)) }
        ctaBtn = TextView(this).apply {
            text = "立即开通"; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(15), 0, dp(15))
            background = pill(18, intArrayOf(0xFFff1e6e.toInt(), 0xFFff6a00.toInt(), 0xFF00c8ff.toInt(), 0xFFc433ff.toInt()))
            setOnClickListener { onBuy() }
        }
        foot.addView(ctaBtn)
        foot.addView(tv("微信 / 支付宝 支付 · 到期不自动扣费 · 随时退订", 11.5f, Color.parseColor("#8a83a6")).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(9), 0, 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(foot)
        return root
    }

    private fun renderPlans() {
        planBox.removeAllViews(); cards.clear()
        for (p in plans) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(15), dp(14), dp(15), dp(14))
                setOnClickListener { select(p) }
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(tv(p.name, 16f, Color.WHITE, true))
            left.addView(tv(p.content, 11.5f, Color.parseColor("#9b93b8")).apply { setPadding(0, dp(3), 0, 0) })
            card.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            if (p.origPrice != null) right.addView(tv(yuan(p.origPrice), 12f, Color.parseColor("#7a7490")).apply {
                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG; gravity = Gravity.END
            })
            right.addView(tv("${yuan(p.price)} ${p.periodLabel}", 19f, Color.WHITE, true).apply { gravity = Gravity.END })
            card.addView(right, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            planBox.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(11) })
            cards[p.id] = card
        }
        // default select recommended (year) or first
        selected = plans.firstOrNull { it.period == "year_price" } ?: plans.firstOrNull()
        selected?.let { select(it) }
    }

    private fun select(p: PlanItem) {
        selected = p
        for ((id, card) in cards) {
            if (id == p.id) card.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x2400c8ff, 0x1ac433ff))
                .apply { cornerRadius = dp(18).toFloat(); setStroke(dp(2), 0xFF00c8ff.toInt()) }
            else card.background = rounded(0x0BFFFFFF, 18, 1, 0x17FFFFFF)
        }
        ctaBtn.text = "立即开通 · ${yuan(p.price)} ${p.periodLabel}"
    }

    // ---------- data ----------
    private fun loadData() {
        lifecycleScope.launch {
            try {
                val auth = mapOf("Authorization" to token!!)
                val (pc, pr) = withContext(Dispatchers.IO) { SetupActivity.httpApiRequest("GET", "/api/v1/user/plan/fetch", auth) }
                if (pc != 200) { toast("加载套餐失败 ($pc)"); return@launch }
                parsePlans(pr)
                val (mc, mr) = withContext(Dispatchers.IO) { SetupActivity.httpApiRequest("GET", "/api/v1/user/order/getPaymentMethod", auth) }
                if (mc == 200) parsePayments(mr)
                renderPlans()
                loadStatus(auth)
            } catch (e: Exception) { toast("加载失败：${e.message}") }
        }
    }

    private fun parsePlans(resp: String) {
        plans.clear()
        val arr = JSONObject(resp).optJSONArray("data") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optInt("show", 1) == 0) continue
            val id = o.getInt("id"); val name = o.optString("name"); val content = o.optString("content").take(40)
            val periods = listOf(
                "month_price" to "/月", "quarter_price" to "/季", "half_year_price" to "/半年",
                "year_price" to "/年", "two_year_price" to "/2年", "three_year_price" to "/3年", "onetime_price" to "/永久"
            )
            for ((key, label) in periods) {
                if (!o.isNull(key)) {
                    val price = o.getInt(key)
                    val orig = if (key == "onetime_price" && price == 99900) 199900 else null
                    plans.add(PlanItem(id, name, content, price, key, label, orig))
                    break
                }
            }
        }
    }

    private fun parsePayments(resp: String) {
        payments.clear()
        val arr = JSONObject(resp).optJSONArray("data") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            payments.add(PayItem(o.getInt("id"), o.optString("name")))
        }
    }

    private suspend fun loadStatus(auth: Map<String, String>) {
        try {
            val (c, r) = withContext(Dispatchers.IO) { SetupActivity.httpApiRequest("GET", "/api/v1/user/info", auth) }
            if (c != 200) { heroSub.text = "开通会员享不限速专线 · 全平台可用"; return }
            val d = JSONObject(r).optJSONObject("data") ?: return
            val total = d.optLong("transfer_enable", 0)
            val used = d.optLong("u", 0) + d.optLong("d", 0)
            val expired = d.optLong("expired_at", 0)
            val remainG = if (total > 0) "%.2f".format((total - used).coerceAtLeast(0) / 1073741824.0) else "-"
            val totalG = if (total > 0) (total / 1073741824) else 0
            val exp = if (expired > 0) {
                val days = ((expired - System.currentTimeMillis() / 1000) / 86400)
                if (days > 3650) "永久有效" else "剩 $days 天"
            } else "未开通"
            heroSub.text = "$exp · 剩余 ${remainG}G / ${totalG}G · 升级享更多流量"
        } catch (_: Exception) { heroSub.text = "开通会员享不限速专线 · 全平台可用" }
    }

    // ---------- buy ----------
    private fun onBuy() {
        val p = selected ?: run { toast("请选择套餐"); return }
        if (payments.isEmpty()) { toast("暂无可用支付方式"); return }
        if (payments.size == 1) { doOrder(p, payments[0]) ; return }
        val names = payments.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("选择支付方式")
            .setItems(names) { _, w -> doOrder(p, payments[w]) }.show()
    }

    private fun doOrder(p: PlanItem, pay: PayItem) {
        ctaBtn.text = "处理中…"; ctaBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                val auth = mapOf("Authorization" to token!!)
                fun saveReq(): Pair<Int, String> = SetupActivity.httpApiRequest("POST", "/api/v1/user/order/save",
                    auth, JSONObject().apply { put("plan_id", p.id); put("period", p.period) }.toString().toByteArray(Charsets.UTF_8))
                var res = withContext(Dispatchers.IO) { saveReq() }
                var sj = JSONObject(res.second)
                // 有未付款/开通中的旧订单 -> 自动取消后重下单
                val msg = sj.optString("message")
                if (res.first != 200 && (msg.contains("未付") || msg.contains("开通中") || msg.contains("pending", true) || msg.contains("unpaid", true))) {
                    cancelPending(auth)
                    res = withContext(Dispatchers.IO) { saveReq() }
                    sj = JSONObject(res.second)
                }
                if (res.first != 200) { toast(sj.optString("message", "下单失败")); return@launch }
                val tradeNo = sj.opt("data")?.toString() ?: run { toast("下单失败"); return@launch }

                val coBody = JSONObject().apply { put("trade_no", tradeNo); put("method", pay.id) }
                    .toString().toByteArray(Charsets.UTF_8)
                val (cc, cr) = withContext(Dispatchers.IO) {
                    SetupActivity.httpApiRequest("POST", "/api/v1/user/order/checkout", auth, coBody)
                }
                if (cc != 200) { toast(JSONObject(cr).optString("message", "支付发起失败")); return@launch }
                val cj = JSONObject(cr)
                val type = cj.optInt("type", 0)
                if (type == -1) { toast("开通成功！"); loadData(); return@launch }
                val payUrl = cj.opt("data")?.toString()
                if (payUrl.isNullOrEmpty() || payUrl == "true") { toast("支付发起失败"); return@launch }
                openPay(payUrl, tradeNo)
            } catch (e: Exception) {
                toast("下单出错：${e.message}")
            } finally {
                ctaBtn.isEnabled = true; selected?.let { ctaBtn.text = "立即开通 · ${yuan(it.price)} ${it.periodLabel}" }
            }
        }
    }

    // 取消该用户所有未付款订单(status=0)
    private suspend fun cancelPending(auth: Map<String, String>) {
        try {
            val (c, r) = withContext(Dispatchers.IO) { SetupActivity.httpApiRequest("GET", "/api/v1/user/order/fetch", auth) }
            if (c != 200) return
            val arr = JSONObject(r).optJSONArray("data") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optInt("status", -1) == 0) {
                    val body = JSONObject().apply { put("trade_no", o.optString("trade_no")) }.toString().toByteArray(Charsets.UTF_8)
                    withContext(Dispatchers.IO) { SetupActivity.httpApiRequest("POST", "/api/v1/user/order/cancel", auth, body) }
                }
            }
        } catch (_: Exception) {}
    }

    // ---------- payment webview ----------
    private fun openPay(url: String, tradeNo: String) {
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            setBackgroundColor(Color.parseColor("#0d0b18"))
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                    handleUrl(request.url.toString())
                @Deprecated("compat")
                override fun shouldOverrideUrlLoading(view: WebView, u: String): Boolean = handleUrl(u)
                override fun onPageFinished(view: WebView, u: String) {
                    // 网关无匹配通道时会返回 JSON 错误(如大额支付宝超限) -> 友好提示改用微信
                    view.evaluateJavascript(
                        "(document.body?document.body.innerText:'').slice(0,300)"
                    ) { r ->
                        if (r != null && (r.contains("没有找到符合金额") || r.contains("支付通道"))) {
                            runOnUiThread {
                                toast("该金额支付宝暂不支持，请返回改用【微信支付】")
                                closeWeb()
                            }
                        }
                    }
                }
            }
            loadUrl(url)
        }
        webOverlay = wv
        setContentView(wv)
    }

    // 回跳到订单页/我们的域名 => 视为支付流程结束，回购买页刷新状态
    private fun handleUrl(u: String): Boolean {
        if (u.contains("/#/order") || u.contains("caihongmao.org") || u.contains("13141069.xyz")) {
            closeWeb(); loadData(); return true
        }
        return false
    }

    private fun closeWeb() {
        webOverlay?.destroy(); webOverlay = null
        setContentView(buildScaffold())
        renderPlans()
        token?.let { lifecycleScope.launch { loadStatus(mapOf("Authorization" to it)) } }
    }
}
