package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** debug 包的假购买页入口。只打开本地套餐，不登录、不下单。 */
class PurchasePreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, PurchaseActivity::class.java)
                .putExtra(PurchaseMocks.EXTRA, true)
        )
        finish()
    }
}
