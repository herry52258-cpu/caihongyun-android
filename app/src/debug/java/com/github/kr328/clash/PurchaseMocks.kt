package com.github.kr328.clash

/**
 * 只存在于 debug 包。用写死的套餐 JSON 打开购买页，不读登录态，不访问面板。
 * release 里同名对象直接返回 false。
 */
internal object PurchaseMocks {
    const val EXTRA = "caihongyun.mock_plans"

    /** 与公开套餐列表同一顺序，另加一条 show=0 和一条 0 元，用来确认解析会丢掉它们。 */
    const val FIXTURE_JSON = """
        {"data":[
          {"id":17,"show":1,"name":"独享版 · 小圈子","content":"独享干净线,仅少数人共用一个IP","month_price":5000,"year_price":48000},
          {"id":14,"show":1,"name":"彩虹猫会员 · 月付","content":"专享超速","month_price":1200},
          {"id":15,"show":1,"name":"彩虹猫会员 · 年付","content":"专享超速","year_price":8800},
          {"id":99,"show":0,"name":"隐藏套餐","content":"不应出现","month_price":100},
          {"id":16,"show":1,"name":"彩虹猫会员 · 两年付","content":"专享超速","two_year_price":15800},
          {"id":13,"show":1,"name":"永久会员","content":"一次买断","month_price":0,"onetime_price":99800}
        ]}
        """

    fun intercept(activity: PurchaseActivity): Boolean {
        if (!activity.intent.getBooleanExtra(EXTRA, false)) return false
        activity.showIsolatedPlans(
            parsePlanItems(FIXTURE_JSON),
            "本地预览 · 不是真实账号，不会向服务器下单"
        )
        return true
    }
}
