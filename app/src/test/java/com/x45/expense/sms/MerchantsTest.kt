package com.x45.expense.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantsTest {

    private fun cat(payee: String?) = Merchants.categoryFor(payee)

    @Test fun everyCategoryIsReachable() {
        assertEquals("Food", cat("SWIGGY"))
        assertEquals("Food", cat("Domino's Pizza"))
        assertEquals("Groceries", cat("BLINKIT"))
        assertEquals("Travel", cat("IRCTC"))
        assertEquals("Travel", cat("BMTC BUS"))
        assertEquals("Fuel", cat("INDIAN OIL PETROL PUMP"))
        assertEquals("Fuel", cat("HPCL"))
        assertEquals("Shopping", cat("AMAZON PAY"))
        assertEquals("Shopping", cat("Flipkart Internet"))
        assertEquals("Bills", cat("BESCOM"))
        assertEquals("Bills", cat("Airtel Prepaid"))
        assertEquals("Subscriptions", cat("UPI-NETFLIX"))
        assertEquals("Health", cat("Apollo Pharmacy"))
        assertEquals("Health", cat("cult.fit"))
        assertEquals("Fun", cat("BookMyShow"))
        assertEquals("Fun", cat("PVR LTD"))
        assertEquals("Personal care", cat("Urban Company"))
        assertEquals("Education", cat("UDEMY"))
        assertEquals("Rent", cat("NoBroker"))
        assertEquals("Gifts", cat("Ferns N Petals"))
        assertEquals("Investments", cat("ZERODHA BROKING"))
        assertEquals("Investments", cat("BSE STAR MF"))
        assertEquals("Investments", cat("Indian Clearing Corp"))
    }

    @Test fun normalisationIgnoresCaseSpacesAndPunctuation() {
        assertEquals("Groceries", cat("Big Basket"))
        assertEquals("Groceries", cat("bigbasket@ybl"))
        assertEquals("Groceries", cat("big-basket@ybl"))
        assertEquals("Subscriptions", cat("Sony LIV"))
    }

    @Test fun longestKeyWins() {
        assertEquals("Groceries", cat("Swiggy Instamart"))
        assertEquals("Subscriptions", cat("AMAZON PRIME"))
        assertEquals("Groceries", cat("Amazon Fresh"))
        assertEquals("Groceries", cat("JioMart"))
        assertEquals("Fuel", cat("Jio-bp"))
        assertEquals("Bills", cat("JIO RECHARGE"))
    }

    @Test fun vpaForms() {
        assertEquals("Travel", cat("paytm-uber@ptybl"))
        assertEquals("Groceries", cat("zepto.payu@hdfcbank"))
        assertEquals("Travel", cat("ola.money@axis"))
        assertEquals("Travel", cat("OLA CABS"))
    }

    @Test fun shortKeysOnlyMatchWholeWords() {
        assertTrue(cat("coca cola store") != "Travel")
        assertNull(cat("lakshmi.flowers@okaxis"))
        assertNull(cat("LAKSHMI"))
        val tea = cat("RAMESH TEA STALL")
        assertTrue("got $tea", tea == null || tea == "Food")
        assertNull(cat("PARENT TEACHER")) // "rent" must not match inside "parent"
        assertNull(cat("pineapple juice")) // "apple" is word-only
        assertNull(cat("promotion offer")) // "notion" is word-only
    }

    @Test fun nullOrBlankOrUnknownIsNull() {
        assertNull(cat(null))
        assertNull(cat(""))
        assertNull(cat("   "))
        assertNull(cat("@@--"))
        assertNull(cat("SURESH KUMAR"))
    }

    @Test fun dictionaryIsLarge() {
        assertTrue("size=${Merchants.size}", Merchants.size >= 300)
    }
}
