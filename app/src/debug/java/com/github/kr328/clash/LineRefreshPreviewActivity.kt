package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * debug 包专用：用本地假订阅代替面板。
 * 第一次刷新返回 2 条线路，之后每次多 1 条。release 包不包含这个页面。
 */
class LineRefreshPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(SetupActivity.KEY_SUBSCRIPTION_URL, "mock://local")
            .remove(SetupActivity.KEY_AUTH_TOKEN)
            .apply()
        LineRefresh.noteJustFetched(this)
        val nextCount = AtomicInteger(2)
        LineRefresh.debugYamlProvider = { mockYaml(nextCount.getAndIncrement()) }
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun mockYaml(count: Int): String {
        val b = StringBuilder()
        b.append("mixed-port: 7890\n")
        b.append("mode: rule\n")
        b.append("proxies:\n")
        for (i in 1..count) {
            b.append("  - name: 线路$i\n")
            b.append("    type: trojan\n")
            b.append("    server: 127.0.0.1\n")
            b.append("    port: 443\n")
            b.append("    password: mock\n")
            b.append("    skip-cert-verify: true\n")
        }
        b.append("proxy-groups:\n")
        b.append("  - name: PROXY\n")
        b.append("    type: select\n")
        b.append("    proxies:\n")
        for (i in 1..count) b.append("      - 线路$i\n")
        b.append("rules:\n")
        b.append("  - MATCH,PROXY\n")
        return b.toString()
    }
}
