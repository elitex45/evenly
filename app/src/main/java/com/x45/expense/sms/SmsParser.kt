package com.x45.expense.sms

/** One money-out event pulled from a bank SMS. Amount is in paise to avoid float errors. */
data class ParsedTxn(
    val amountPaise: Long,
    val payee: String?,
    val bank: String?,
    val account: String?,
    val ref: String?,
    val mode: String,
)

sealed interface ParseResult {
    /** A debit we understood. */
    data class Debit(val txn: ParsedTxn) : ParseResult
    /** From a bank, mentions money, but we couldn't read it. Shown in "Texts I couldn't read". */
    data object Unreadable : ParseResult
    /** Not a spend (OTP, credit, promo, reminder, personal text...). Ignored. */
    data object Ignore : ParseResult
}

object SmsParser {

    private val I = RegexOption.IGNORE_CASE

    private val amountRx = Regex("""(?:rs\.?|inr|₹)\s*:?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", I)
    // A bare number right after "debited by/for" (SBI writes "debited by 100.0").
    private val bareDebitAmountRx = Regex("""debited\s+(?:by|for|with)\s+([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", I)

    private val debitRx = Regex(
        """\b(debited|debit(?:ed)? for|spent|sent|paid|withdrawn|withdrawal|purchase|purchased|transferred|txn of|transaction of|used (?:at|for)|charged|dr\.?\s+from|payment of)\b""", I
    )
    // "Txn Rs.586", "Used Rs80", "Dr. INR 270", "Debit INR 708", "Txn Amt:INR125"
    private val debitShapeRx = Regex("""\b(?:txn|used)\s*(?:rs|inr|₹)|\bdr\.?\s+(?:inr|rs)|\bdebit\s+(?:inr|rs)|\btxn\s*amt""", I)
    private val moneyContextRx = Regex("""\b(a/?c|acct|account|card|upi|vpa|bank|wallet|imps|neft|rtgs)\b""", I)

    // If any of these match, it's not a completed spend.
    private val ignoreRx = listOf(
        """\botp\b""", """one[\s-]?time password""", """verification code""",
        """\bwill be debited\b""", """\bis due\b""", """\bdue (?:on|date|by)\b""", """minimum (?:amount )?due""",
        """\bstatement\b""", """\bfailed\b""", """\bdeclined\b""", """\breversed\b""", """\brefund""",
        """\brequested\b""", """collect request""", """\bpre-?approved\b""", """\boffer\b""", """\bcashback of\b""",
        """\bauto-?pay\b.*\bscheduled\b""", """\bmandate\b.*\bcreated\b""", """\bunsuccessful\b""",
        // Reminders, bills, ads, EMI offers: money is mentioned but nothing was spent now.
        """thank you for (?:your )?payment""", """converted into emi""", """\bwill be (?:auto[\s-]?)?debited\b""",
        """\bwill be processed\b""", """\boverdue\b""", """\birregular by\b""", """\bupcoming debit\b""",
        """\bbill\b[^\n]{0,40}\b(?:generated|is ready)\b""", """smart\s?emi""", """please (?:make|complete) (?:your |the )?payment""",
        """\bvoucher\b""",
    ).map { Regex(it, I) }

    private val maskRx = Regex("""[x*]{2,}\s?\d{3,}|ending\s+(?:in\s+|with\s+)?[x*]*\d{3,}""", I)

    private val creditOnlyRx = Regex("""\b(credited|received|deposited)\b""", I)

    private val refRx = Regex(
        """(?:upi\s*ref(?:\.|erence)?\s*(?:no\.?|number)?|ref(?:erence)?\s*(?:no\.?|number|id)?|refno|rrn|utr|txn\s*id|transaction\s*id)\s*[:.#-]?\s*([0-9]{6,})""", I
    )
    private val upiInfoRx = Regex("""upi/(?:p2[am]|dr|cr)?/?([0-9]{6,})""", I)

    private val accountRx = Regex(
        """(?:a/?c(?:count)?|acct|card|account)\s*(?:no\.?|number|ending(?: with)?)?\s*[:.]?\s*(?:[x*.\s]*)([0-9]{3,6})\b""", I
    )
    private val cardWordRx = Regex("""\b(card|credit card|cc)\b""", I)

    // Payee patterns, tried in order. Group 1 is the payee.
    private val payeeRx: List<Regex> = listOf(
        // HDFC "AutoPay (E-mandate) Success!\nFor NAME \n"
        """autopay[^\n]*\n\s*for\s+([^\n]{2,50})""",
        // Union "Fvg: NAME Avl Bal"
        """fvg:?\s+(.{2,60}?)\s+avl""",
        // OneCard "paid Rs. 2 at NAME with your"
        """\bat\s+(.{2,50}?)\s+with\s+your""",
        // HDFC card on UPI "At abc@paytm \nby UPI"
        """\bat\s+([\w.\-]+@[\w.\-]*)""",
        // IDFC "INR 125 for NAME paid from your"
        """\bfor\s+(.{2,50}?)\s+paid from your""",
        // ICICI: "...; LAKSHMI credited. UPI:..."
        """;\s*([^;.]{2,60}?)\s+credited""",
        // "trf to NAME Refno" (SBI)
        """trf to\s+(.{2,60}?)\s+(?:ref|on\b|upi)""",
        // "Cr. to VPA/NAME." (BoB)
        """cr\.?\s+to\s+([^\s.,]+(?:\s[^\s.,]+){0,4}?)(?:\.\s|\.?$|,|\s+ref)""",
        // "to VPA abc@okaxis"
        """to\s+vpa\s+([\w.\-]+@[\w.\-]+)""",
        // "UPI/P2A/123456/NAME/..." or "Info: UPI/123456/NAME"
        """upi/(?:p2[am]/|dr/)?[0-9]{6,}/([^/\n]{2,40})""",
        // HDFC UPI multi-line: "\nTo LAKSHMI\n"
        """\nto\s+([^\n]{2,60})\n""",
        // Cards: "at MERCHANT on" / "At SWIGGY On 2025-..."
        """\bat\s+(.{2,50}?)\s+on\s+(?:\d|your|\w{3}\b)""",
        // ICICI CC: "on 01-Jan-25 on AMAZON."
        """on\s+\d{1,2}[-/ ]\w{2,3}[-/ ]\d{2,4}\s+on\s+(.{2,50}?)(?:\.\s|\.?$|\s+avl|,)""",
        // "to abc@okaxis on" / "to NAME on 01-.." / "to NAME, UPI Ref"
        """\bto\s+([\w.\-]+@[\w.\-]+)""",
        """\bto\s+(.{2,60}?)(?:;|\s+on\s+\d|,\s*upi|\.\s*upi|\s+upi\s+ref|\s+ref|\s+via|\.\s|\.?$)""",
        """\btowards\s+(.{2,60}?)(?:\s+for\s+(?:inr|rs)|\s+on\s+\d|\.\s|\.?$|,)""",
        // "at MERCHANT." end of sentence
        """\bat\s+([A-Za-z0-9*&'\- ]{2,50}?)(?:\.\s|\.?$|,|\s+avl)""",
    ).map { Regex(it, setOf(I, RegexOption.MULTILINE)) }

    // Axis card: merchant is its own line after the time line.
    private val axisCardMerchantRx = Regex("""\d{2}:\d{2}(?::\d{2})?(?:\s*IST)?\s*\n([^\n/]{2,50})\n""", I)

    private val payeeJunkRx = Regex("""^(your|you|a/?c|account|acct|the|bank|card|ac|to|dispute|continue|avoid|boost|click|pay|http)\b""", I)
    private val bankFeeRx = Regex("""charged with\b[^\n]{0,60}\bnon-?maint|did not have the required average""", I)

    fun parse(sender: String, body: String): ParseResult {
        val knownBank = Banks.bankForSender(sender)
        // Personal phone numbers are never treated as bank messages.
        if (!Banks.isBusinessSender(sender)) return ParseResult.Ignore

        val text = body.replace('\r', '\n')
        if (ignoreRx.any { it.containsMatchIn(text) }) return ParseResult.Ignore

        val hasDebitWord = debitRx.containsMatchIn(text) || debitShapeRx.containsMatchIn(text)
        val looksFinancial = knownBank != null || moneyContextRx.containsMatchIn(text)
        if (!looksFinancial) return ParseResult.Ignore
        if (!hasDebitWord) {
            return ParseResult.Ignore // credits, balance alerts, promos
        }
        // "credited" without any debit word is income; with one (e.g. ICICI "debited ...; X credited") it's a spend.
        // "INR 15000 credited ... /Paid": the credit comes first, so money came in.
        val creditAt = creditOnlyRx.find(text)?.range?.first
        val debitAt = listOfNotNull(debitRx.find(text)?.range?.first, debitShapeRx.find(text)?.range?.first).minOrNull()
        if (creditAt != null && debitAt != null && creditAt < debitAt) return ParseResult.Ignore
        // Unknown senders (shops, billers, loan apps) only count if the text shows a masked account/card.
        if (knownBank == null && !maskRx.containsMatchIn(text)) return ParseResult.Ignore
        if (creditOnlyRx.containsMatchIn(text) && !Regex("""\b(debited|sent|paid|spent|dr\.)""", I).containsMatchIn(text)) {
            return ParseResult.Ignore
        }

        val amount = findAmount(text) ?: return if (knownBank != null) ParseResult.Unreadable else ParseResult.Ignore
        // Strip UPI IDs first: "momos@okicici" must not count as ICICI Bank.
        val bank = knownBank ?: Banks.bankFromBody(text.replace(Regex("""[\w.\-]+@[\w.\-]+"""), " "))
        if (bank == null && !moneyContextRx.containsMatchIn(text)) return ParseResult.Ignore

        val ref = refRx.find(text)?.groupValues?.get(1) ?: upiInfoRx.find(text)?.groupValues?.get(1)
        val account = accountRx.find(text)?.groupValues?.get(1)?.takeLast(4)
        val mode = when {
            Regex("""\b(atm|withdrawn|withdrawal)\b""", I).containsMatchIn(text) -> "ATM"
            Regex("""\b(upi|vpa)\b|@[a-z]""", I).containsMatchIn(text) -> "UPI"
            cardWordRx.containsMatchIn(text) || Regex("""\bspent\b""", I).containsMatchIn(text) -> "Card"
            Regex("""\b(neft|imps|rtgs|net ?banking|mob bk)\b""", I).containsMatchIn(text) -> "Bank transfer"
            else -> "Other"
        }

        return ParseResult.Debit(
            ParsedTxn(
                amountPaise = amount,
                payee = findPayee(text, mode),
                bank = bank,
                account = account,
                ref = ref,
                mode = mode,
            )
        )
    }

    /** First amount that isn't a balance or limit. */
    internal fun findAmount(text: String): Long? {
        val candidates = amountRx.findAll(text).map { it.range.first to it.groupValues[1] }.toMutableList()
        bareDebitAmountRx.find(text)?.let { candidates.add(it.range.first to it.groupValues[1]) }
        return candidates
            .sortedBy { it.first }
            .firstOrNull { (pos, _) ->
                val before = text.substring(maxOf(0, pos - 25), pos).lowercase()
                listOf("bal", "limit", "lmt", "avl", "available", "outstanding", "due").none { before.contains(it) }
            }
            ?.second
            ?.let(::toPaise)
            ?.takeIf { it > 0 }
    }

    private fun toPaise(s: String): Long? {
        val clean = s.replace(",", "")
        val parts = clean.split('.')
        val rupees = parts[0].toLongOrNull() ?: return null
        val paise = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0
        return rupees * 100 + paise
    }

    internal fun findPayee(text: String, mode: String): String? {
        if (mode == "ATM") return "ATM withdrawal"
        if (bankFeeRx.containsMatchIn(text)) return "Bank charges"
        axisCardMerchantRx.find(text)?.groupValues?.get(1)?.let(::cleanPayee)?.let { if (!amountRx.containsMatchIn(it)) return it }
        for (rx in payeeRx) {
            val m = rx.find(text) ?: continue
            val p = cleanPayee(m.groupValues[1]) ?: continue
            return p
        }
        return null
    }

    private fun cleanPayee(raw: String): String? {
        var p = raw.trim().trim('.', ',', ';', ':', '-', '*').replace(Regex("""\s+"""), " ")
        p = p.replace(Regex("""\s+(on|via|ref|upi|avl|from)\b.*$""", I), "").trim()
        p = p.replace(Regex("""^upi[\s-]+""", I), "").trim()
        if (p.length < 2 || p.length > 60) return null
        if (payeeJunkRx.containsMatchIn(p)) return null
        if (p.all { it.isDigit() || it == ' ' || it == '-' || it == '/' || it == ':' }) return null
        if (amountRx.containsMatchIn(p)) return null
        return p
    }
}
