package com.x45.expense.sms

/**
 * Built-in merchant -> category dictionary, so most spends are categorised on day one.
 *
 * Payees come from SMS in many shapes: "SWIGGY", "Swiggy Instamart", "zepto.payu@hdfcbank",
 * "paytm-uber@ptybl", "UPI-NETFLIX". Matching is done on a normalised form: lowercase with
 * every non-alphanumeric removed, so "Big Basket", "bigbasket" and "big-basket@ybl" all match.
 *
 * Rules:
 *  - The longest matching key wins ("swiggy instamart" -> Groceries beats "swiggy" -> Food).
 *  - Keys shorter than 5 normalised chars ("ola", "kfc", "jio") and keys added with [word]
 *    only match whole words of the payee, so "ola" matches "OLA CABS" but not "coca cola".
 *  - Unknown payees return null, never "Other".
 * Keys are best-known brand names and billers, not an official list.
 */
object Merchants {

    private val allowed = setOf(
        "Food", "Groceries", "Travel", "Fuel", "Shopping", "Bills", "Subscriptions", "Health",
        "Fun", "Personal care", "Education", "Rent", "Gifts", "Investments", "Other",
    )

    private class Key(val norm: String, val category: String, val wordOnly: Boolean)

    private val nonAlnum = Regex("[^a-z0-9]+")

    private fun normalize(s: String) = s.lowercase().replace(nonAlnum, "")

    // Sorted longest first, so the first hit is the most specific key.
    private val keys: List<Key> = run {
        val byNorm = LinkedHashMap<String, Key>()
        fun put(category: String, wordOnly: Boolean, names: Array<out String>) {
            require(category in allowed) { "bad category $category" }
            names.forEach { name ->
                val n = normalize(name)
                require(n.isNotEmpty()) { "empty key '$name'" }
                val k = Key(n, category, wordOnly || n.length < 5)
                val old = byNorm[n]
                require(old == null || old.category == category) { "'$name' is both ${old?.category} and $category" }
                if (old == null || (old.wordOnly && !k.wordOnly)) byNorm[n] = k
            }
        }
        fun add(category: String, vararg names: String) = put(category, false, names)
        /** Long keys that are also ordinary words: match only as whole words. */
        fun word(category: String, vararg names: String) = put(category, true, names)

        // Food delivery, restaurants, cafes
        add("Food", "swiggy", "zomato", "domino's", "dominos pizza", "mcdonald's", "mcdonalds india", "kfc",
            "burger king", "pizza hut", "subway", "starbucks", "tata starbucks", "chaayos", "third wave coffee",
            "thirdwave", "blue tokai", "cafe coffee day", "ccd", "haldiram's", "bikanervala", "wow momo",
            "behrouz biryani", "faasos", "oven story", "eatsure", "box8", "freshmenu", "rebel foods", "chai point",
            "adyar ananda bhavan", "a2b", "saravana bhavan", "rameshwaram cafe", "meghana foods", "paradise biryani",
            "biryani by kilo", "barbeque nation", "absolute barbecues", "theobroma", "baskin robbins",
            "naturals ice cream", "keventers", "haagen dazs", "krispy kreme", "dunkin", "taco bell", "wendy's",
            "nando's", "la pino'z", "mojo pizza", "eatfit", "magicpin", "eazydiner", "dineout", "swiggy dineout",
            "california burrito", "burger singh", "chili's", "hard rock cafe", "mainland china", "truffles",
            "vidyarthi bhavan", "mtr", "brahmin's coffee bar", "cream stone", "polar bear", "amul parlour",
            "chai sutta bar", "tea villa", "costa coffee", "tim hortons", "irani cafe", "restaurant", "cafe",
            "bakery", "dhaba", "canteen", "food court", "eat club", "goila", "zomato order", "biryani blues")
        word("Food", "barista", "sweets", "foods")

        // Groceries & quick commerce
        add("Groceries", "swiggy instamart", "instamart", "zepto", "blinkit", "grofers", "bigbasket", "bbnow",
            "bb daily", "dmart", "d mart", "avenue supermarts", "reliance fresh", "reliance smart", "smart bazaar",
            "more retail", "more supermarket", "more megastore", "jiomart", "nature's basket", "ratnadeep",
            "spencer's", "star bazaar", "spar", "lulu hypermarket", "metro cash and carry", "easyday", "nilgiris",
            "heritage fresh", "freshtohome", "licious", "country delight", "milkbasket", "dunzo", "amazon fresh",
            "flipkart minutes", "flipkart supermart", "big bazaar", "fraazo", "otipy", "dailyninja", "akshayakalpa",
            "amul", "mother dairy", "safal", "hopcoms", "supermarket", "hypermarket", "grocery", "groceries",
            "kirana", "provision store", "vegetables", "fruits and vegetables", "jiomart grocery", "bb now",
            "swiggy instamart order", "kpn fresh", "foodhall", "le marche", "godrej natures basket")

        // Travel & transport
        add("Travel", "uber", "ola", "ola cabs", "olacabs", "ola money", "rapido", "namma yatri", "blusmart",
            "meru cabs", "quick ride", "irctc", "indian railways", "redbus", "makemytrip", "mmt", "goibibo",
            "cleartrip", "ixigo", "easemytrip", "abhibus", "confirmtkt", "trainman", "indigo", "interglobe aviation",
            "air india", "air india express", "akasa", "akasa air", "vistara", "spicejet", "alliance air", "star air",
            "go first", "goair", "bmtc", "best bus", "best undertaking", "namma metro", "bmrcl", "dmrc", "delhi metro",
            "mumbai metro", "mmrcl", "mmopl", "chennai metro", "cmrl", "hyderabad metro", "kochi metro", "pune metro",
            "metro card", "metro recharge", "fastag", "toll plaza", "nhai", "ksrtc", "apsrtc", "tsrtc", "tgsrtc",
            "msrtc", "gsrtc", "upsrtc", "rsrtc", "kkrtc", "oyo", "airbnb", "booking.com", "agoda", "treebo",
            "fabhotels", "zostel", "yulu", "zoomcar", "revv", "drivezy", "parking", "uber india", "railyatri",
            "travelyaari", "thomas cook", "yatra.com", "intrcity", "zingbus", "flixbus", "uts mobile")
        word("Travel", "yatra", "toll", "airport")

        // Fuel
        add("Fuel", "indian oil", "iocl", "hpcl", "hindustan petroleum", "bpcl", "bharat petroleum", "nayara",
            "jio-bp", "reliance bp", "essar petrol", "petrol pump", "petrol", "fuel", "filling station",
            "petroleum", "hp petrol pump", "hp pay", "indianoil fuel", "fuel station")
        word("Fuel", "shell", "fuels")

        // Shopping
        add("Shopping", "amazon", "amazon pay", "amazon.in", "flipkart", "myntra", "ajio", "meesho", "croma",
            "reliance digital", "reliance trends", "decathlon", "ikea", "tata cliq", "lenskart", "pepperfry",
            "urban ladder", "firstcry", "snapdeal", "shopclues", "nykaa fashion", "vijay sales", "poorvika mobiles",
            "sangeetha mobiles", "bajaj electronics", "lifestyle stores", "shoppers stop", "pantaloons", "westside",
            "max fashion", "zudio", "uniqlo", "zara", "levi's", "puma", "nike", "adidas", "bata", "metro shoes",
            "titan", "tanishq", "caratlane", "bluestone", "samsung", "oneplus", "xiaomi", "realme", "dell",
            "lenovo", "fabindia", "biba", "manyavar", "hamleys", "crossword", "vishal mega mart", "boat lifestyle",
            "h&m india", "marks and spencer", "skechers", "woodland", "liberty shoes", "mochi footwear",
            "apple store", "imagine store", "jiomart digital", "tata neu", "tata croma", "nykaa man", "the souled store",
            "bewakoof", "snitch", "souled store", "cult sport", "wrogn", "peter england", "allen solly",
            "van heusen", "louis philippe", "us polo", "jack and jones", "only stores", "trends footwear",
            "home centre", "home town", "wakefit", "sleepyhead", "duroflex", "sleepwell", "ebay", "aliexpress",
            "shein", "pothys", "nalli", "kalyan jewellers", "malabar gold", "joyalukkas", "reliance jewels",
            "mi store", "boat", "fire boltt")
        word("Shopping", "noise")

        // Bills & utilities
        add("Bills", "bescom", "hescom", "gescom", "mescom", "cesc", "msedcl", "mahadiscom", "mahavitaran",
            "tpddl", "tata power", "bses", "bses rajdhani", "bses yamuna", "tneb", "tangedco", "tnpdcl", "kseb",
            "adani electricity", "torrent power", "apspdcl", "apepdcl", "tsspdcl", "tgspdcl", "tsnpdcl", "uppcl",
            "pspcl", "dhbvn", "uhbvn", "jvvnl", "avvnl", "jdvvnl", "mpez", "mppkvvcl", "wbsedcl", "cspdcl",
            "tpcodl", "nbpdcl", "sbpdcl", "best electricity", "electricity", "airtel", "jio", "jio fiber",
            "jio postpaid", "jio recharge", "vi", "vodafone idea", "vodafone", "bsnl", "mtnl", "act fibernet",
            "hathway", "excitel", "tata play", "tata sky", "dish tv", "d2h", "sun direct", "airtel dth",
            "indane", "hp gas", "bharat gas", "bharatgas", "mahanagar gas", "igl", "indraprastha gas", "mgl",
            "gujarat gas", "adani gas", "adani total gas", "bwssb", "delhi jal board", "djb", "mcgm water",
            "hmwssb", "cmwssb", "water board", "bbmp", "property tax", "bbps", "bharat bill", "billdesk",
            "lic", "lic of india", "policybazaar", "acko", "digit insurance", "hdfc ergo", "icici lombard",
            "bajaj allianz", "insurance", "mygate", "nobrokerhood", "maintenance", "gas bill", "broadband",
            "you broadband", "spectra", "tikona", "piped gas", "credit card bill", "cred bill")

        // Subscriptions
        add("Subscriptions", "netflix", "spotify", "prime video", "amazon prime", "primevideo", "hotstar",
            "disney hotstar", "jiocinema", "jiohotstar", "youtube premium", "youtube", "google one", "google play",
            "google storage", "google workspace", "google cloud", "itunes", "icloud", "apple services",
            "microsoft", "office 365", "xbox game pass", "zee5", "sonyliv", "sony liv", "openai", "chatgpt",
            "anthropic", "claude.ai", "voot", "altbalaji", "mx player", "sun nxt", "eros now", "gaana", "jiosaavn",
            "saavn", "wynk", "audible", "kindle unlimited", "linkedin", "canva", "dropbox", "adobe",
            "github", "figma", "times prime", "lionsgate play", "hoichoi", "aha video", "discovery plus",
            "apple music", "apple tv", "youtube music", "perplexity", "midjourney", "grammarly", "1password",
            "nordvpn", "expressvpn", "substack", "patreon", "tinder", "bumble", "zoom video")
        word("Subscriptions", "apple", "claude", "notion", "hinge")

        // Health & fitness
        add("Health", "apollo", "apollo pharmacy", "apollo 24|7", "pharmeasy", "1mg", "tata 1mg", "netmeds",
            "practo", "cult.fit", "cultfit", "curefit", "medplus", "wellness forever", "truemeds", "healthkart",
            "thyrocare", "dr lal pathlabs", "lal pathlabs", "metropolis healthcare", "redcliffe labs",
            "manipal hospital", "fortis", "max healthcare", "narayana health", "aster dm", "cloudnine", "pristyn care",
            "mfine", "healthifyme", "medlife", "frank ross", "guardian pharmacy", "star health", "niva bupa",
            "care health", "hospital", "pharmacy", "chemist", "diagnostic", "clinic", "medical store", "medicals",
            "dental", "gold's gym", "anytime fitness", "fitpass", "cure fit", "sugar.fit", "orange health",
            "tata aig health", "hospitals", "pathlab", "eye care", "dawaa dost", "davaindia", "jan aushadhi")

        // Fun & entertainment
        add("Fun", "bookmyshow", "pvr", "inox", "pvr inox", "cinepolis", "carnival cinemas", "district app",
            "district by zomato", "steam games", "playstation", "sony playstation", "xbox", "nintendo",
            "wonderla", "imagica", "smaaash", "dream11", "my11circle", "rummycircle", "paytm insider",
            "ticketnew", "kidzania", "epic games", "riot games", "valve", "timezone", "fun city", "zomaland",
            "skillbox", "mpl", "winzo", "a23", "cinema", "cinemas", "multiplex", "bowling", "escape room",
            "snow world", "ezone", "miraj cinemas", "mukta a2", "sathyam cinemas", "spi cinemas", "insider.in")
        word("Fun", "steam")

        // Personal care
        add("Personal care", "urban company", "urbanclap", "nykaa", "lakme", "lakme salon", "naturals salon",
            "green trends", "jawed habib", "bodycraft", "enrich salon", "toni and guy", "looks salon", "yes madam",
            "purplle", "mamaearth", "the man company", "bombay shaving", "beardo", "salon", "spa", "parlour",
            "myglamm", "sugar cosmetics", "forest essentials", "kama ayurveda", "bath and body works",
            "the body shop", "plum goodness", "minimalist", "wow skin", "foxtale", "dot and key", "barber",
            "unisex salon", "beauty parlour", "tira beauty", "sephora", "mac cosmetics", "o2 spa", "four fountains")

        // Education
        add("Education", "udemy", "coursera", "byju's", "unacademy", "vedantu", "upgrad", "simplilearn",
            "great learning", "physicswallah", "physics wallah", "toppr", "whitehat jr", "scaler academy",
            "edx", "skillshare", "masterclass", "duolingo", "khan academy", "allen career", "aakash institute",
            "aakash educational", "fiitjee", "school fees", "college fees", "tuition", "university", "vidyalaya",
            "leetcode", "pluralsight", "codecademy", "datacamp", "oreilly", "linkedin learning", "exam fee",
            "nptel", "testbook", "adda247", "gradeup", "cuemath", "planet spark", "brightchamps", "eruditus",
            "emeritus", "internshala", "great lakes", "nmims", "manipal university")
        word("Education", "school", "college", "academy", "amity")

        // Investments
        add("Investments", "zerodha", "zerodha coin", "groww", "upstox", "kuvera", "paytm money", "angel one",
            "angel broking", "icicidirect", "icici securities", "hdfc securities", "kotak securities", "5paisa",
            "motilal oswal", "sharekhan", "dhan hq", "smallcase", "indmoney", "scripbox", "et money", "fisdom",
            "bse star mf", "bsestarmf", "indian clearing corp", "indian clearing corporation", "iccl",
            "nse clearing", "nsccl", "cams", "camsonline", "kfintech", "karvy", "mutual fund", "mf utilities",
            "sip", "nps", "ppf", "safegold", "augmont", "mmtc pamp", "wint wealth", "gullak", "jar app",
            "stable money", "goldenpi", "grip invest", "cdsl", "nps trust", "sbi mutual fund", "hdfc mutual fund",
            "icici prudential mf", "axis mutual fund", "nippon india mf", "parag parikh", "ppfas", "zerodha broking",
            "fyers")

        // Rent & housing
        add("Rent", "nobroker", "nestaway", "rent", "house rent", "room rent", "rent payment", "zolo",
            "stanza living", "colive", "redgiraffe", "cred rent", "housing.com", "flat rent", "pg rent",
            "hostel fee", "your space", "isthara")

        // Gifts
        add("Gifts", "ferns n petals", "fernsnpetals", "fnp", "igp", "igp.com", "archies", "winni", "floweraura",
            "bloomsvilla", "gift card", "giftcard", "gift voucher", "giftxoxo", "woohoo", "gifting", "oyegifts",
            "send gifts")

        byNorm.values.sortedByDescending { it.norm.length }
    }

    /** Number of distinct (normalised) keys in the dictionary. */
    val size: Int get() = keys.size

    /** Category for a payee string, or null when the payee is not a known merchant. */
    fun categoryFor(payee: String?): String? {
        if (payee.isNullOrBlank()) return null
        val tokens = payee.lowercase().split(nonAlnum).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val norm = tokens.joinToString("")
        // Every run of consecutive whole words, joined: "OLA CABS" -> ola, cabs, olacabs.
        val words = HashSet<String>()
        for (i in tokens.indices) {
            val sb = StringBuilder()
            for (j in i until minOf(tokens.size, i + 5)) {
                sb.append(tokens[j])
                words.add(sb.toString())
            }
        }
        return keys.firstOrNull { if (it.wordOnly) it.norm in words else norm.contains(it.norm) }?.category
    }
}
