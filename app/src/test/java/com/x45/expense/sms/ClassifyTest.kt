package com.x45.expense.sms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifyTest {
    @Test fun creditCardBillFromBankIsTransfer() =
        assertTrue(Classify.isTransfer("HDFC CREDIT CARD", "Rs.12,500.00 debited from A/c XX1234 towards Credit Card Bill payment of HDFC Bank card XX5678"))

    @Test fun bbpsPaymentIsTransfer() =
        assertTrue(Classify.isTransfer(null, "Your payment of Rs.4500 via BBPS for IDFC FIRST Credit Card is successful"))

    @Test fun credPayeeIsTransfer() = assertTrue(Classify.isTransfer("cred.club@axisb", "Rs.9000 debited to VPA cred.club@axisb"))

    @Test fun selfTransferIsTransfer() = assertTrue(Classify.isTransfer("Self", "Rs.5000 transferred to Self"))

    @Test fun cardSwipeIsNotTransfer() =
        assertFalse(Classify.isTransfer("SWIGGY", "Spent Rs.499 On HDFC Bank Card 5678 At SWIGGY On 2026-10-08:13:02:11"))

    @Test fun shopWithCredInNameIsNotTransfer() =
        assertFalse(Classify.isTransfer("Credence Bakery", "Rs.200 paid to Credence Bakery via UPI"))

    @Test fun personNamedSelvamIsNotTransfer() = assertFalse(Classify.isTransfer("SELVAM", "Rs.300 paid to SELVAM UPI"))
}
