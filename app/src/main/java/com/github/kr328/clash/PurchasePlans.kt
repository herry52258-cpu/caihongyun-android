package com.github.kr328.clash

import org.json.JSONObject

internal const val PURCHASE_DEDICATED_NOTE =
    "强烈推荐少人共享专线。常刷 TikTok、看 YouTube 或在线 4K 视频，优先考虑专线，减少多人争抢带宽。实际流畅度仍受本地网络和时段影响。"

/** 支付通道单笔限额(分)：支付宝 ¥1-300，微信 ¥1-1000。 */
internal fun purchasePayMaxCents(name: String): Int = when {
    name.contains("支付宝") -> 30000
    name.contains("微信") -> 100000
    else -> Int.MAX_VALUE
}

/** 按接口数组原顺序展开周期。show=0 或价格小于等于 0 的周期不进列表。 */
internal fun parsePlanItems(resp: String): List<PurchaseActivity.PlanItem> {
    val plans = ArrayList<PurchaseActivity.PlanItem>()
    val arr = JSONObject(resp).optJSONArray("data") ?: return plans
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        if (o.optInt("show", 1) == 0) continue
        val id = o.getInt("id")
        val name = o.optString("name")
        val content = o.optString("content").take(40)
        val periods = listOf(
            "month_price" to "/月", "quarter_price" to "/季", "half_year_price" to "/半年",
            "year_price" to "/年", "two_year_price" to "/2年", "three_year_price" to "/3年", "onetime_price" to "/永久"
        )
        for ((key, label) in periods) {
            if (!o.isNull(key)) {
                val price = o.getInt(key)
                if (price <= 0) continue
                val orig = if (key == "onetime_price" && price == 99900) 199900 else null
                plans.add(PurchaseActivity.PlanItem(id, name, content, price, key, label, orig))
            }
        }
    }
    return plans
}

/** 默认选中第一张年付；没有年付就选第一张。 */
internal fun defaultPurchasePlan(plans: List<PurchaseActivity.PlanItem>): PurchaseActivity.PlanItem? =
    plans.firstOrNull { it.period == "year_price" } ?: plans.firstOrNull()
