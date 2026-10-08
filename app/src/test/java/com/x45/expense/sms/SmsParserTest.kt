package com.x45.expense.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sample texts follow the formats Indian banks are known to use. They are written from
 * known formats, not copied from real phones, so real texts may differ in small ways.
 * When a real text fails, add it here as a new case.
 */
class SmsParserTest {

    private fun debit(sender: String, body: String): ParsedTxn {
        val r = SmsParser.parse(sender, body)
        if (r !is ParseResult.Debit) fail("expected Debit, got $r for: $body")
        return (r as ParseResult.Debit).txn
    }

    private fun check(sender: String, body: String, rupees: String, payee: String?, bank: String) {
        val t = debit(sender, body)
        assertEquals("amount for: $body", rupees, "%d.%02d".format(t.amountPaise / 100, t.amountPaise % 100))
        if (payee != null) assertEquals("payee for: $body", payee.lowercase(), t.payee?.lowercase())
        assertEquals("bank for: $body", bank, t.bank)
    }

    // ---- the user's banks ----

    @Test fun canaraUpi() = check(
        "VM-CANBNK-S",
        "Rs.150.00 paid thru A/C XX1234 on 08-10-26 10:15:22 to LAKSHMI, UPI Ref 628112345678. If not done, SMS BLOCKUPI to 9901771222.-Canara Bank",
        "150.00", "LAKSHMI", "Canara Bank",
    )

    @Test fun kotakUpi() = check(
        "AD-KOTAKB-S",
        "Sent Rs.250.00 from Kotak Bank AC X1234 to lakshmi.flowers@okaxis on 08-10-26.UPI Ref 628198765432. Not you, https://kotak.com/KBANKT/Fraud",
        "250.00", "lakshmi.flowers@okaxis", "Kotak Bank",
    )

    @Test fun hdfcUpiMultiline() = check(
        "JD-HDFCBK-S",
        "Sent Rs.80.00\nFrom HDFC Bank A/C *1234\nTo RAMESH TEA STALL\nOn 08/10/26\nRef 628155550000\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808",
        "80.00", "RAMESH TEA STALL", "HDFC Bank",
    )

    @Test fun hdfcCreditCard() = check(
        "VM-HDFCBK-S",
        "Spent Rs.499 On HDFC Bank Card 5678 At SWIGGY On 2026-10-08:13:02:11.Not You? To Block+Reissue Call 18002586161/SMS BLOCK CC 5678 to 7308080808",
        "499.00", "SWIGGY", "HDFC Bank",
    )

    @Test fun hdfcRupayCreditCardUpi() = check(
        "VM-HDFCBK-S",
        "Rs.60.00 debited from HDFC Bank Credit Card 5678 to VPA chaiwala@ybl on 08-10-26. Ref No 628100001111. Not you? Call 18002586161",
        "60.00", "chaiwala@ybl", "HDFC Bank",
    )

    @Test fun idfcCreditCard() = check(
        "BZ-IDFCFB-S",
        "INR 1,250.00 spent on your IDFC FIRST Bank Credit Card ending XX4321 at AMAZON PAY on 08 OCT 2026 at 07:45 PM. Avl Limit: INR 98,750.00",
        "1250.00", "AMAZON PAY", "IDFC First Bank",
    )

    @Test fun idfcBankUpi() = check(
        "BZ-IDFCFB-S",
        "Your A/C XXXXX1234 is debited by INR 300.00 on 08/10/26 11:00. New Bal :INR 5,000.00. Info: UPI/628177778888/SURESH KIRANA",
        "300.00", "SURESH KIRANA", "IDFC First Bank",
    )

    @Test fun oneCard() = check(
        "VM-ONECRD-S",
        "You've spent Rs. 720.50 at ZOMATO on your OneCard credit card ending XX99 on 08-Oct-26.",
        "720.50", "ZOMATO", "OneCard",
    )

    @Test fun credUpi() = check(
        "VM-CREDIN-S",
        "Rs 45 paid to Metro Juice via CRED UPI on 08-10-26. UPI Ref 628122223333.",
        "45.00", "Metro Juice", "CRED",
    )

    // ---- the friend's banks ----

    @Test fun unionBank() = check(
        "AX-UNIONB-S",
        "A/c *1234 Debited for Rs:500.00 on 08-10-2026 12:10:01 by Mob Bk ref no 628144445555 Avl Bal Rs:12,000.00.If not you, Call 1800222243 -Union Bank of India",
        "500.00", null, "Union Bank",
    )

    @Test fun axisBankUpi() = check(
        "VM-AXISBK-S",
        "INR 220.00 debited\nA/c no. XX1234\n08-10-26, 09:30:12\nUPI/P2A/628166667777/MURALI\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank",
        "220.00", "MURALI", "Axis Bank",
    )

    @Test fun axisRupayCard() = check(
        "VM-AXISBK-S",
        "Spent INR 999\nAxis Bank Card no. XX5678\n08-10-26 20:01:33 IST\nBIGBASKET\nAvl Limit: INR 45,001\nNot you? SMS BLOCK 5678 to 919951860002",
        "999.00", "BIGBASKET", "Axis Bank",
    )

    @Test fun bobUpi() = check(
        "AD-BOBTXN-S",
        "Rs.130.00 Dr. from A/C XXXXXX1234 and Cr. to fruitshop@paytm. Ref:628188889999. AvlBal:Rs2000.00(2026:10:08 16:20:00). Not you? Call 18005700-BOB",
        "130.00", "fruitshop@paytm", "Bank of Baroda",
    )

    @Test fun indusindCard() = check(
        "VM-INDUSB-S",
        "INR 1,500.00 spent on IndusInd Card XX9012 on 08-10-26 at DECATHLON. Avl Lmt: INR 60,000.00",
        "1500.00", "DECATHLON", "IndusInd Bank",
    )

    // ---- other big banks ----

    @Test fun sbiUpi() = check(
        "VM-SBIUPI-S",
        "Dear UPI user A/C X1234 debited by 75.0 on date 08Oct26 trf to AUTO DRIVER Refno 628100002222. If not u? call 1800111109. -SBI",
        "75.00", "AUTO DRIVER", "SBI",
    )

    @Test fun iciciUpi() = check(
        "VM-ICICIB-S",
        "ICICI Bank Acct XX123 debited for Rs 340.00 on 08-Oct-26; PRIYA MEDICALS credited. UPI:628133334444. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.",
        "340.00", "PRIYA MEDICALS", "ICICI Bank",
    )

    @Test fun iciciCard() = check(
        "VM-ICICIT-S",
        "INR 2,100.00 spent using ICICI Bank Card XX4455 on 08-Oct-26 on FLIPKART. Avl Limit: INR 1,20,000.00. If not you, call 1800 2662.",
        "2100.00", "FLIPKART", "ICICI Bank",
    )

    @Test fun unknownSenderCodeFallsBackToBody() = check(
        "VM-XYZBNK-S",
        "Rs.90.00 debited from your Yes Bank A/c XX1111 to VPA momos@okicici on 08-10-26. Ref 628155556666",
        "90.00", "momos@okicici", "Yes Bank",
    )

    @Test fun atm() {
        val t = debit("VM-SBIINB-S", "Rs.2000.00 withdrawn at SBI ATM S1AW000123 from A/c X1234 on 08Oct26 Txn# 1234. Avl Bal Rs 8000.00")
        assertEquals(200000L, t.amountPaise)
        assertEquals("ATM", t.mode)
    }

    @Test fun balanceIsNotTheAmount() {
        val t = debit("AX-UNIONB-S", "Avl Bal Rs:9,999.00. A/c *1234 Debited for Rs:20.00 on 08-10-2026 by UPI ref 628100000001 -Union Bank of India")
        assertEquals(2000L, t.amountPaise)
    }

    @Test fun refIsCaptured() {
        val t = debit("VM-CANBNK-S", "Rs.150.00 paid thru A/C XX1234 on 08-10-26 10:15:22 to LAKSHMI, UPI Ref 628112345678. -Canara Bank")
        assertEquals("628112345678", t.ref)
        assertEquals("1234", t.account)
    }

    // ---- things that must NOT become expenses ----

    private fun ignored(sender: String, body: String) {
        val r = SmsParser.parse(sender, body)
        assertTrue("expected Ignore, got $r for: $body", r is ParseResult.Ignore)
    }

    @Test fun otpIgnored() = ignored("VM-HDFCBK-S", "OTP is 123456 for txn of Rs.500.00 at AMAZON on HDFC Bank Card 5678. Do not share.")
    @Test fun creditIgnored() = ignored("VM-KOTAKB-S", "Received Rs.1000.00 in your Kotak Bank AC X1234 from rahul@okaxis on 08-10-26.UPI Ref 628100009999.")
    @Test fun creditedIgnored() = ignored("VM-CANBNK-S", "Your A/c XX1234 is credited with Rs.5000.00 on 08-10-26 by NEFT. -Canara Bank")
    @Test fun dueReminderIgnored() = ignored("VM-HDFCBK-S", "Payment of Rs.5,000 for HDFC Bank Credit Card XX5678 is due on 15-10-26. Pay now to avoid charges.")
    @Test fun failedIgnored() = ignored("VM-AXISBK-S", "Your UPI transaction of Rs.200.00 to shop@ybl has failed. Amount if debited will be reversed.")
    @Test fun collectRequestIgnored() = ignored("VM-SBIUPI-S", "SURESH has requested money Rs.500 from you on UPI. Pay only if you know them.")
    @Test fun personalTextIgnored() = ignored("+919876543210", "I sent Rs 500 to your account, check")
    @Test fun promoIgnored() = ignored("VM-SWIGGY-S", "Get 50% off up to Rs.100 on your next order! Use code TASTY. T&C apply.")
    @Test fun autopayFutureIgnored() = ignored("VM-ICICIB-S", "Rs 199.00 will be debited from your ICICI Bank Acct XX123 on 10-Oct-26 for NETFLIX mandate.")
}
