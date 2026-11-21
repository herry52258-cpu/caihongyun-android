package com.github.kr328.clash

/** release 不提供假套餐入口。 */
internal object PurchaseMocks {
    fun intercept(activity: PurchaseActivity): Boolean = false
}
