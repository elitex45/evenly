package com.x45.expense.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class StatsTest {
    private fun at(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    private var nextId = 1L
    private fun t(rupees: Long, payee: String?, cat: String?, ts: Long) = Txn(
        nextId++, rupees * 100, payee, payee, "HDFC Bank", "1234", null, "UPI", ts,
        null, null, null, cat, null, "VM-HDFCBK", "",
    )

    @Test fun byCategoryGroupsAndSortsBiggestFirst() {
        val s = Stats.byCategory(listOf(t(100, "A", "Food", 0), t(300, "B", "Travel", 0), t(50, "C", "Food", 0), t(10, "D", null, 0)))
        assertEquals(listOf("Travel", "Food", "Untagged"), s.map { it.key })
        assertEquals(15_000L, s[1].paise)
        assertEquals(2, s[1].count)
    }

    @Test fun perDayPutsSpendsOnTheRightDay() {
        val from = at(2026, 10, 1, 0)
        val d = Stats.perDay(listOf(t(100, "A", null, at(2026, 10, 1, 9)), t(40, "B", null, at(2026, 10, 3, 23)), t(5, "C", null, at(2026, 11, 1))), from, 31)
        assertEquals(10_000L, d[0])
        assertEquals(0L, d[1])
        assertEquals(4_000L, d[2])
        assertEquals(14_000L, d.sum()) // November spend falls outside
    }

    @Test fun changeIsNullWithoutHistory() {
        assertNull(Stats.change(500, 0))
        assertEquals(50, Stats.change(150, 100))
        assertEquals(-25, Stats.change(75, 100))
    }

    @Test fun projectionAndSafePerDay() {
        assertEquals(30_000L, Stats.projected(10_000, 10, 30))
        assertEquals(1_000L, Stats.safePerDay(30_000, 10_000, 20))
        assertEquals(0L, Stats.safePerDay(10_000, 12_000, 5)) // over budget: zero, never negative
    }

    @Test fun recurringFindsMonthlySubscriptionNotRandomRepeats() {
        val all = listOf(
            t(649, "NETFLIX", "Subscriptions", at(2026, 8, 5)),
            t(649, "NETFLIX", "Subscriptions", at(2026, 9, 5)),
            t(649, "NETFLIX", "Subscriptions", at(2026, 10, 5)),
            t(120, "Chai Point", "Food", at(2026, 10, 1)), // same week repeats: not monthly
            t(80, "Chai Point", "Food", at(2026, 10, 2)),
            t(90, "Chai Point", "Food", at(2026, 10, 3)),
        )
        val r = Stats.recurring(all)
        assertEquals(listOf("NETFLIX"), r.map { it.payee })
        assertEquals(3, r[0].months)
        assertEquals(64_900L, r[0].paise)
    }

    @Test fun insightsMentionIncreaseAndBiggestSpend() {
        val prev = listOf(t(1000, "A", "Food", 0), t(1000, "B", "Travel", 0))
        val cur = listOf(t(2000, "A", "Food", 0), t(1500, "B", "Travel", 0), t(100, "C", "Fun", 0))
        val s = Stats.insights(cur, prev, "month")
        assertTrue(s.toString(), s[0] == "You spent 80% more than last month.")
        assertTrue(s.toString(), s.any { it == "Food is up 100% vs last month." })
        assertTrue(s.toString(), s.any { it == "Biggest spend: ₹2,000 at A." })
    }
}
