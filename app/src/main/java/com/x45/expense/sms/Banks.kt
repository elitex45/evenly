package com.x45.expense.sms

/**
 * Indian bank / card / wallet SMS sender codes.
 *
 * Indian commercial SMS come from DLT headers like "VM-HDFCBK" or "AD-HDFCBK-S".
 * We strip the 2-letter operator prefix and the 1-letter suffix, then look up the
 * 6-ish letter code here. Codes are best-known values, not an official list, so the
 * parser also falls back to spotting the bank name inside the message body.
 */
object Banks {

    // code -> display name
    private val senders: Map<String, String> = buildMap {
        fun add(name: String, vararg codes: String) = codes.forEach { put(it, name) }
        add("HDFC Bank", "HDFCBK", "HDFCBN", "HDFCCC", "HDFCSC", "HDFCPY")
        add("SBI", "SBIINB", "SBIUPI", "SBMSMS", "ATMSBI", "CBSSBI", "SBIPSG", "SBICRD", "SBICARD", "SBIBNK", "SBYONO")
        add("ICICI Bank", "ICICIB", "ICICIT", "ICICIO", "ICIBNK", "ICICRD")
        add("Axis Bank", "AXISBK", "AXISBN", "AXISCR", "AXISMR", "AXBANK")
        add("Kotak Bank", "KOTAKB", "KOTAK", "KOTAKM", "KMBANK", "KOTKBK")
        add("Canara Bank", "CANBNK", "CNRBNK", "CANBKM", "CANARA")
        add("Union Bank", "UNIONB", "UBOI", "UBOIMB", "UNIONM", "UBINBK")
        add("Bank of Baroda", "BOBTXN", "BOBSMS", "BARODA", "BOBCRD", "BOBCARD", "BOBFIN", "BOBCCD")
        add("Punjab National Bank", "PNBSMS", "PUNBNK", "PNBBNK")
        add("IDFC First Bank", "IDFCFB", "IDFCBK", "IDFCFR", "IDFCCC")
        add("IndusInd Bank", "INDUSB", "INDUSL", "IBLCRD", "INDUSC", "IBLBNK")
        add("Yes Bank", "YESBNK", "YESBK", "YESBKC")
        add("Indian Overseas Bank", "IOBCHN", "IOBBNK", "IOBSMS")
        add("Indian Bank", "INDBNK", "INDIAN", "INDBKM")
        add("Bank of India", "BOIIND", "BOISMS", "BOIBNK")
        add("Central Bank of India", "CENTBK", "CBOI", "CBIBNK")
        add("UCO Bank", "UCOBNK", "UCOBK")
        add("Bank of Maharashtra", "BOMBNK", "MAHABK", "MAHABNK")
        add("Punjab & Sind Bank", "PSBANK", "PSBBNK")
        add("Federal Bank", "FEDBNK", "FEDFIB", "FEDSCP")
        add("South Indian Bank", "SIBSMS", "SOUTHB", "SIBBNK")
        add("Karur Vysya Bank", "KVBANK", "KVBSMS", "KVBBNK")
        add("City Union Bank", "CUBANK", "CUBLTD", "CUBSMS")
        add("Karnataka Bank", "KBLBNK", "KARBNK", "KTKBNK")
        add("Tamilnad Mercantile Bank", "TMBANK", "TMBLTD", "TMBSMS")
        add("DCB Bank", "DCBBNK", "DCBANK")
        add("RBL Bank", "RBLBNK", "RBLCRD", "RBLCRC")
        add("Bandhan Bank", "BANDHN", "BDNSMS", "BNDHAN")
        add("AU Small Finance Bank", "AUBANK", "AUSFBL", "AUBNK")
        add("Equitas Bank", "EQUTAS", "ESFBNK", "EQUITS")
        add("Ujjivan Bank", "UJJIVN", "UJVNSB", "UJJIVB")
        add("J&K Bank", "JKBANK", "JKBNKS")
        add("DBS Bank", "DBSBNK", "DBSIND", "DIGIBK")
        add("Standard Chartered", "SCBANK", "STANCB", "SCBNKI")
        add("Citi", "CITIBK", "CITIBA", "CITIIN")
        add("HSBC", "HSBCIN", "HSBCBK", "HSBCCC")
        add("IDBI Bank", "IDBIBK", "IDBIBN", "IDBIBA")
        add("CSB Bank", "CSBBNK", "CSBANK")
        add("Dhanlaxmi Bank", "DHANBK", "DHANLX")
        add("Nainital Bank", "NAINIT", "NTBANK")
        add("Saraswat Bank", "SARBNK", "SARSWT")
        add("Cosmos Bank", "COSMOS", "COSBNK")
        add("ESAF Bank", "ESAFBK", "ESAFSB")
        add("Jana Bank", "JANABK", "JSFBNK")
        add("Suryoday Bank", "SURYOD", "SSFBNK")
        add("Paytm Payments Bank", "PAYTMB", "PYTMBK", "PAYTM", "iPaytm")
        add("Airtel Payments Bank", "AIRBNK", "AIRPBK", "AIRTLB")
        add("Fino Payments Bank", "FINOBK", "FINOPB")
        add("India Post Payments Bank", "IPPBNK", "IPBMSG")
        add("NSDL Payments Bank", "NSDLPB", "NSDLBK")
        add("American Express", "AMEXIN", "AMEXIC")
        add("OneCard", "ONECRD", "ONECARD", "OCARDS", "FPLTEC", "BOBONE")
        add("CRED", "CREDIN", "CREDPY", "CREDAP", "CREDMN", "CREDWL")
        add("Slice", "SLICEP", "SLCEIT", "SLICEB")
        add("Jupiter", "JUPITR", "JUPTER")
        add("Fi", "FIMONY", "EPIFI")
        add("PhonePe", "PHONPE", "PHNEPE")
        add("Amazon Pay", "AMZNPY", "AMAZON")
    }

    // Body text -> display name; used when the sender code is unknown.
    // Order matters: longer / more specific names first.
    private val bodyNames: List<Pair<Regex, String>> = listOf(
        "hdfc" to "HDFC Bank",
        "state bank|\\bsbi\\b" to "SBI",
        "icici" to "ICICI Bank",
        "axis" to "Axis Bank",
        "kotak" to "Kotak Bank",
        "canara" to "Canara Bank",
        "union bank" to "Union Bank",
        "bank of baroda|\\bbob\\b" to "Bank of Baroda",
        "punjab national|\\bpnb\\b" to "Punjab National Bank",
        "idfc" to "IDFC First Bank",
        "indusind" to "IndusInd Bank",
        "yes bank" to "Yes Bank",
        "indian overseas" to "Indian Overseas Bank",
        "bank of india" to "Bank of India",
        "central bank" to "Central Bank of India",
        "indian bank" to "Indian Bank",
        "uco bank" to "UCO Bank",
        "bank of maharashtra" to "Bank of Maharashtra",
        "federal bank" to "Federal Bank",
        "south indian bank" to "South Indian Bank",
        "karur vysya" to "Karur Vysya Bank",
        "city union" to "City Union Bank",
        "karnataka bank" to "Karnataka Bank",
        "rbl" to "RBL Bank",
        "bandhan" to "Bandhan Bank",
        "\\bau bank|au small" to "AU Small Finance Bank",
        "equitas" to "Equitas Bank",
        "idbi" to "IDBI Bank",
        "standard chartered" to "Standard Chartered",
        "\\bciti" to "Citi",
        "hsbc" to "HSBC",
        "\\bdbs\\b" to "DBS Bank",
        "paytm" to "Paytm Payments Bank",
        "airtel payments" to "Airtel Payments Bank",
        "onecard|one card" to "OneCard",
        "\\bcred\\b" to "CRED",
        "amex|american express" to "American Express",
    ).map { (p, n) -> Regex(p, RegexOption.IGNORE_CASE) to n }

    /** "VM-HDFCBK-S" -> "HDFCBK". Returns null for plain phone numbers. */
    fun senderCode(sender: String): String? {
        val s = sender.trim().uppercase()
        if (s.isEmpty() || s.replace("+", "").all { it.isDigit() }) return null
        val parts = s.split('-').filter { it.isNotEmpty() }
        return when {
            parts.size >= 2 && parts[0].length == 2 -> parts[1]
            else -> parts[0]
        }
    }

    fun bankForSender(sender: String): String? {
        val code = senderCode(sender) ?: return null
        senders[code]?.let { return it }
        // Some operators truncate or extend codes; try a prefix match.
        return senders.entries.firstOrNull { code.startsWith(it.key) || it.key.startsWith(code) && code.length >= 5 }?.value
    }

    fun bankFromBody(body: String): String? = bodyNames.firstOrNull { it.first.containsMatchIn(body) }?.second

    /** True if this looks like a business (DLT) sender, not a person's number. */
    fun isBusinessSender(sender: String): Boolean = senderCode(sender) != null

    val count: Int get() = senders.values.toSet().size
}
