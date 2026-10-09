package com.x45.expense.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitTest {
    @Test fun equalSplitAddsUpExactly() {
        assertEquals(listOf(334L, 333L, 333L), Split.equal(1000, 3))
        assertEquals(listOf(30000L, 30000L, 30000L), Split.equal(90000, 3))
        assertEquals(listOf(1L, 0L), Split.equal(1, 2))
    }

    @Test fun nobodyTicked() = assertEquals(emptyList<Long>(), Split.equal(1000, 0))

    private val ravi = Member("r", "Ravi Kumar")
    private val asha = Member("a", "Asha")

    @Test fun owesLinesFromMySide() {
        val debts = listOf(Debt("me", "r", 30000), Debt("a", "me", 5000), Debt("a", "r", 100))
        assertEquals(listOf(Debt("me", "r", 30000)), Split.iOwe(debts, "me"))
        assertEquals(listOf(Debt("a", "me", 5000)), Split.owedToMe(debts, "me"))
    }

    // "Sent ₹300 to Ravi" from my own bank text looks like paying Ravi back.
    @Test fun paybackGuessNeedsSameAmountAndTheirName() {
        val owe = listOf(Payback("g", ravi, 30000))
        assertEquals(owe[0], Split.paybackFor(30000, "RAVI K", owe))
        assertEquals(owe[0], Split.paybackFor(30000, "ravi.k@okaxis", owe))
        assertNull("different amount", Split.paybackFor(29900, "RAVI K", owe))
        assertNull("someone else", Split.paybackFor(30000, "Swiggy", owe))
        assertNull("name inside a longer word", Split.paybackFor(30000, "Ravindra Stores", owe))
        assertNull("no payee", Split.paybackFor(30000, null, owe))
        assertNull("short first name is too risky", Split.paybackFor(500, "AL", listOf(Payback("g", Member("x", "Al"), 500))))
        assertEquals(Payback("g", asha, 500), Split.paybackFor(500, "Asha", listOf(Payback("g", asha, 500))))
    }

    // The admin may remove someone only when they're settled: the People list shows each person's money.
    @Test fun netOfSaysWhoOwesAndWhoIsOwed() {
        val g = Group("g", "Flat", "🏠", "ABCDEF", listOf(asha, ravi), listOf(Debt(ravi.id, asha.id, 3000)), adminId = asha.id)
        assertEquals(-3000L, g.netOf(ravi.id))
        assertEquals(3000L, g.netOf(asha.id))
        assertEquals(0L, g.netOf("nobody"))
        assertEquals(true, g.isAdmin(asha.id))
        assertEquals(false, g.isAdmin(ravi.id))
        assertEquals(false, g.isAdmin(null))
    }
}
