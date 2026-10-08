package com.x45.expense.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Shapes taken from real exported texts (2 phones, 16k business texts, Oct 2026).
 * Names, card numbers and refs are changed; wording, spacing and line breaks are kept.
 */
class RealFormatsTest {

    private fun debit(sender: String, body: String): ParsedTxn {
        val r = SmsParser.parse(sender, body)
        if (r !is ParseResult.Debit) fail("expected Debit, got $r for: $body")
        return (r as ParseResult.Debit).txn
    }

    private fun check(sender: String, body: String, rupees: String, payee: String?, bank: String) {
        val t = debit(sender, body)
        assertEquals("amount for: $body", rupees, "%d.%02d".format(t.amountPaise / 100, t.amountPaise % 100))
        assertEquals("payee for: $body", payee?.lowercase(), t.payee?.lowercase())
        assertEquals("bank for: $body", bank, t.bank)
    }

    private fun ignored(sender: String, body: String) =
        assertEquals("should not be a spend: $body", ParseResult.Ignore, SmsParser.parse(sender, body))

    private fun unreadable(sender: String, body: String) =
        assertEquals("should go to 'texts I couldn't read': $body", ParseResult.Unreadable, SmsParser.parse(sender, body))

    // ---- spends my reader used to miss ----

    @Test fun hdfcCardOnUpiTxn() = check(
        "JD-HDFCBK-S",
        "Txn Rs.586.45\nOn HDFC Bank Card 1111\nAt makemytrip1.rzp@rxairtel \nby UPI 432500000111\nOn 20-11\nNot You?\nCall 18002586161/SMS BLOCK CC 1111 to 7308080808\n",
        "586.45", "makemytrip1.rzp@rxairtel", "HDFC Bank",
    )

    @Test fun hdfcCardOnUpiUsed() = check(
        "VM-HDFCBK-S",
        "Used Rs80.00\nOn HDFCBank Card 1111\nAt paytmqr6s4l@paytm \nby UPI 420900000831\nOn 27-07\nNot You?\nCall 18002586161/SMS BLOCK CC 1111 to 7308080808\n",
        "80.00", "paytmqr6s4l@paytm", "HDFC Bank",
    )

    @Test fun hdfcAutopaySuccess() = check(
        "VM-HDFCBK-S",
        "AutoPay (E-mandate) Success!\nFor GOOGLE WORKSPACE \nTxn Amt:INR125.00\nDt:06/08/2024\nVia:HDFC Bank CC 2222\nSI Hub ID: ABCdef1234\nTnC",
        "125.00", "GOOGLE WORKSPACE", "HDFC Bank",
    )

    @Test fun canaraDr() = check(
        "VM-CANBNK-S",
        "Dear Customer, Acct XXXX1234 Dr. INR 270.00 on 23/06/26 to RAVI K; UPI: 654000000330; Bal INR 13,945.55.Not you?SMS BLOCKUPI to 9901771222-CanaraBank",
        "270.00", "RAVI K", "Canara Bank",
    )

    @Test fun axisDebitedTowards() = check(
        "VM-AXISBK-S",
        "Your A/c has been debited towards Newtap for INR 5176.00 on 03-02-26. abc123def@axisb - Axis Bank",
        "5176.00", "Newtap", "Axis Bank",
    )

    @Test fun axisDebitCardPaymentIsTransfer() {
        val body = "Debit INR 708.00\nAxis Bank A/c XX1234\n02-07-25 23:41:07\nCreditCard Payment XX 9012\nWhatsApp BAL to 917036165000\nNot You? SMS BLOCKALL CustID to 919951860002"
        val t = debit("VM-AXISBK-S", body)
        assertEquals(70800L, t.amountPaise)
        assertTrue("card bill must be a transfer", Classify.isTransfer(t.payee, body))
    }

    @Test fun bobcardOnePaidAt() = check(
        "VM-BOBONE-S",
        "You've paid Rs. 2.00 at Google Workspace with your BOBCARD One Credit Card ending in XX9003 and earned reward points! To dispute this payment, visit: m.1crd.in/OneCrd/shcut",
        "2.00", "Google Workspace", "OneCard",
    )

    @Test fun bobcardOneSpentAt() = check(
        "VM-BOBONE-S",
        "Fresh picks! Rs. 215.00 spent at Blinkit on your BOBCARD One Credit Card xxXX9004. Reward points added. To dispute, click: https://1crd.in/OneCrd/HQL",
        "215.00", "Blinkit", "OneCard",
    )

    @Test fun indusindCardOnUpi() = check(
        "VM-INDUSB-S",
        "INR 1,000.00 spent on IndusInd Card XX9005 on 09-09-2026 04:09:56 pm at UPI SRI GAJANANA FRUIT. Avl Lmt: INR 29,000.00. To dispute, call 18602677777/SMS BLOCK 9005 to 5676757",
        "1000.00", "SRI GAJANANA FRUIT", "IndusInd Bank",
    )

    @Test fun bobcardUpiPrefix() = check(
        "VM-BOBCRD-S",
        "ALERT: INR 200.00 is spent on your BOBCARD ending 9011 at Upi-lingala Byraweshwa on 17-09-2026. Available credit limit is Rs 43,800.00, Current outstanding is Rs 0.00. Not you?  Call 18002090 (toll-free)",
        "200.00", "lingala Byraweshwa", "Bank of Baroda",
    )

    @Test fun unionFvg() = check(
        "AX-UNIONB-S",
        "Union Bank of India A/c *9010 Debited Rs:39.00 on 24-05-2026 16:48:51 by Mob Bk ref no 651000000598, Fvg: RAMESH K Avl Bal Rs:30468.74. Not you?Call 18002333/SMS BLOCK 9010 to 8879365472",
        "39.00", "RAMESH K", "Union Bank",
    )

    @Test fun credWallet() = check(
        "VM-CREDWL-S",
        "Update from CRED wallet:\n\nYou have successfully paid Rs.20.0 and your updated wallet balance is Rs.6350.0. \n\nFor any queries or support, call 080-62752800.",
        "20.00", null, "CRED",
    )

    @Test fun hdfcBankFee() = check(
        "VM-HDFCBK-S",
        "HDFC Bank A/c XXXX9008 is charged with Rs.594 (excl GST) for non-maintenance of Avg. bal. in AUG 23. Maintain req. bal. to avoid charges. Details: refer website",
        "594.00", "Bank charges", "HDFC Bank",
    )

    @Test fun hdfcCutOffUpiId() = check(
        "VM-HDFCBK-S",
        "Txn Rs.40.00\nOn HDFC Bank Card 1111\nAt BHARATPE90000@ \nby UPI 420900000111\nOn 01-08\nNot You?\nCall 18002586161/SMS BLOCK CC 1111 to 7308080808\n",
        "40.00", "BHARATPE90000@", "HDFC Bank",
    )

    @Test fun idfcStandingInstruction() = check(
        "VM-IDFCFB-S",
        "INR 125.00 for Google Workspace paid from your IDFC FIRST Bank Credit Card XX1234 on 03/09/2026 basis standing instruction. IDFC FIRST Bank",
        "125.00", "Google Workspace", "IDFC First Bank",
    )

    @Test fun idfcMinBalanceFee() = check(
        "VM-IDFCFB-S",
        "Dear Customer, Your a/c ending XXXXX1234 is debited by Rs. 295.00 as it did not have the required Average Monthly Balance. IDFC FIRST Bank",
        "295.00", "Bank charges", "IDFC First Bank",
    )

    // ---- not spends: these were wrongly counted ----

    @Test fun cardBillPaidThankYou() = ignored("VM-IDFCFB-S", "Thank you for payment of INR 791.00 towards your FIRST Millennia Credit Card XX9001 on 05-08-2024. IDFC FIRST Bank")
    @Test fun emiConverted() = ignored("VM-AXISBK-S", "Your Airtel Axis Bank Mastercard Credit Card transaction of INR 4778 has been successfully converted into EMI. Duration: 6 EMI. Interest: 1.33% p.m. Fee: INR 100. TnC - Axis Bank")
    @Test fun futureAutoDebit() = ignored("VM-AXISBK-S", "Payment of INR 183.20 for Fotor will be auto debited via Axis Bank Card no. XX9002 by 20-12-24. To deactivate Auto Pay facility against ID XXSrSbYPv5, please log into https://ccm.axbk.in/AXISBK/OPQJu8VF\nTnC apply.\n")
    @Test fun overdueReminder() = ignored("VM-HDFCBK-S", "REMINDER!\nRs. 666.00 is overdue on HDFC Bank Credit Card 9007. \nPlease complete your payment today:\nhttps://hdfcbk.io/HDFCBK/p/pE2bRN8Z\nIgnore if paid!")
    @Test fun loanIrregular() = ignored("VM-CBSSBI-S", "Dear Customer, your Loan A/c XXXXX900000 irregular by INR 20,543.86. Please pay immediately to avoid penal charges. Ignore if already paid. -SBI")
    @Test fun billGenerated() = ignored("VM-ONECRD-S", "Dear User,\nYour bill of Rs. 2759.84 has been generated.\n\nClear your dues before 2026-07-05 and ensure timely repayments.\n\nCheck Bill: 1crd.in/OneCrd/StmtDsh\nPay Now: 1crd.in/OneCrd/CnfmAmt\n\n**Please ignore if already paid\n\n- BOBCARD One Credit Card")
    @Test fun upcomingMandate() = ignored("VM-ONECRD-S", "Hi Ravi, you have an upcoming debit for the emandate registered on Google Workspace of INR5097.6 on 3/9/2026. View Details https://onecard.1crd.in/Oneapp/SubsDshb -Team OneCard")
    @Test fun smartEmiAd() = ignored("VM-HDFCBN-S", "Hurry! Split Rs.34510.73 spent on HDFC Bank Credit Card x9006  into SmartEMIs. Lower monthly outgo, easy approval! https://hdfcbk.io/HDFCBK/s/BRk4Vg0O  T&C")
    @Test fun pleaseMakePayment() = ignored("VM-HDFCBK-S", "Thank you for the confirmation, please make payment of Rs. 4792.44 by clicking on https://hdfcbk.io/HDFCBK/s/M7VoPN4p ,\n- HDFC Bank")
    @Test fun axisCreditPaid() = ignored("VM-AXISBK-S", "INR 20000.00 credited\nA/c no. XX9000\n01-07-25, 16:28:16 IST\nUPI/P2A/554800000934/RAVI K /UBIN/Paid - Axis Bank")
    @Test fun loanEmiNotice() = ignored("VM-LNTFIN-S", "Dear Customer, EMI debit of Rs.4500 against loan account number T1234 will be processed on 05APR-25 Please maintain sufficient balance in your bank account to avoid charges")
    @Test fun billerReceiptNotBank() = ignored("VM-BESCOM-S", "Dear Consumer, Thank you for making Online Payment of Rs1200 against Account ID 1234567 on 2025-01-05T10:00:00+05:30 BESCOM")
    @Test fun bankAdFromOtherSender() = ignored("VM-AKIKOG-S", "Dear customer,\nUse HDFC Bank RuPay credit card on UPI transactions & earn rewards.\nApply for this & get Rs 500 amazon voucher\nhttp://1kx.in/vzL9d")

    // ---- spends in another currency: shown in "texts I couldn't read" so nothing is lost ----

    @Test fun foreignCurrencyCard() = unreadable("VM-AXISBK-S", "Spent\nCard no. XX9002\nTHB 5\n08-11-24 13:02:05\nWWW*GRAB*CO\nAvl Lmt INR 31253.38\nSMS BLOCK 9002 to 919951860002, if not you - Axis Bank")
    @Test fun foreignCurrencyBobcardOne() = unreadable("VM-BOBONE-S", "You've paid USD 20.00 at Vercel Inc. with your BOBCARD One Credit Card ending in XX9003 and earned reward points! To dispute this payment, visit: m.1crd.in/OneCrd/shcut")
}
