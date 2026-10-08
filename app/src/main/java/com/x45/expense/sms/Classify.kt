package com.x45.expense.sms

/**
 * Some debits aren't spending: paying your own credit card bill (the real spends were already
 * counted when you swiped the card) or moving money to your own account. Counting them would
 * double your totals, so they are stored as "transfer".
 */
object Classify {
    private val cardBill = Regex(
        """credit\s*card\s*(bill|payment|dues?)|creditcard\s*payment|card\s*one\s*credit\b|fpl\s*tech|ach-dr-creditsaison|\bcc\s*(bill|payment)|card\s*bill|\bbbps\b|bill\s*desk.*card|towards\s+(your\s+)?(credit\s*)?card\s*(no|ending|xx)""",
        RegexOption.IGNORE_CASE
    )

    // CRED is used almost only to pay card bills. "cred.club@axisb", "CRED", "CREDCLUB".
    private val credPayee = Regex("""^(cred|credclub|cred\.club.*|cred club)$""", RegexOption.IGNORE_CASE)
    private val selfWords = Regex("""\b(self|own a/?c|own account|to self)\b""", RegexOption.IGNORE_CASE)

    fun isTransfer(payee: String?, body: String): Boolean =
        cardBill.containsMatchIn(body) ||
            (payee != null && credPayee.matches(payee.trim())) ||
            (payee != null && selfWords.containsMatchIn(payee))
}
