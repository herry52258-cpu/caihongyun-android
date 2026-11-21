package com.github.kr328.clash

import android.content.Intent
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 假套餐只证明：文案在列表上方、接口顺序和默认年付没改、支付限额没改、说明文字不盖住购买按钮。
 * 不是登录/到期/流量耗尽的真机验收，也不是国内拨测。
 */
@RunWith(AndroidJUnit4::class)
class PurchasePlanOrderTest {
    @Test
    fun keepsApiOrderAndFirstYearlyDefault() {
        val plans = parsePlanItems(PurchaseMocks.FIXTURE_JSON)
        assertEquals(
            listOf(
                "17-month_price-5000",
                "17-year_price-48000",
                "14-month_price-1200",
                "15-year_price-8800",
                "16-two_year_price-15800",
                "13-onetime_price-99800"
            ),
            plans.map { "${it.id}-${it.period}-${it.price}" }
        )
        assertFalse(plans.any { it.id == 99 })
        val selected = defaultPurchasePlan(plans)
        assertNotNull(selected)
        assertEquals(17, selected!!.id)
        assertEquals("year_price", selected.period)
        assertEquals(48000, selected.price)
    }

    @Test
    fun payLimitsStayTheSame() {
        assertEquals(30000, purchasePayMaxCents("支付宝"))
        assertEquals(100000, purchasePayMaxCents("微信支付"))
        assertEquals(Int.MAX_VALUE, purchasePayMaxCents("其他"))
    }

    @Test
    fun noteStaysAboveListAndDoesNotCoverBuyButton() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, PurchaseActivity::class.java)
            .putExtra(PurchaseMocks.EXTRA, true)
        ActivityScenario.launch<PurchaseActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val note = root.findViewWithTag<TextView>("purchase_note")
                val cta = root.findViewWithTag<TextView>("purchase_cta")
                assertEquals(PURCHASE_DEDICATED_NOTE, note.text.toString())
                assertTrue("说明至少要折成多行才算测到换行", note.lineCount >= 2)
                val noteRect = Rect()
                val ctaRect = Rect()
                assertTrue(note.getGlobalVisibleRect(noteRect))
                assertTrue(cta.getGlobalVisibleRect(ctaRect))
                assertFalse(Rect.intersects(noteRect, ctaRect))
                assertTrue(ctaRect.top >= noteRect.bottom)
                assertTrue(ctaRect.height() > 0)
                assertTrue(ctaRect.bottom <= activity.resources.displayMetrics.heightPixels)

                // 按钮在滚动区域外面。把说明测到至少 9 行时，按钮的位置不被这段文字盖住。
                val density = activity.resources.displayMetrics.density
                val probe = TextView(activity)
                probe.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12.5f * 2f * density)
                probe.text = note.text
                var lines = 0
                var usedWidth = 0
                for (dpWidth in listOf(240, 200, 180, 160)) {
                    usedWidth = (dpWidth * density).toInt()
                    probe.measure(
                        View.MeasureSpec.makeMeasureSpec(usedWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    lines = probe.lineCount
                    if (lines >= 9) break
                }
                assertTrue("字体放大后应能折成至少 9 行，实际 $lines", lines >= 9)
                assertFalse(Rect.intersects(noteRect, ctaRect))
            }
        }
    }
}
