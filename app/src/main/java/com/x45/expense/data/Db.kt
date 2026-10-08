package com.x45.expense.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * kind:
 *  spend    - counts in totals
 *  transfer - money moved between your own accounts / card bill payment (not counted)
 *  ignored  - you said "not a spend", or the account isn't tracked (not counted, can be restored)
 *  parent   - a spend that was split; its children (parentId = this id) are counted instead
 */
data class Txn(
    val id: Long,
    val amountPaise: Long,
    val payee: String?,        // display name (contact name if a contact matched)
    val payeeRaw: String?,     // what the SMS said
    val bank: String?,
    val account: String?,
    val ref: String?,
    val mode: String,
    val ts: Long,
    val lat: Double?,
    val lng: Double?,
    val place: String?,
    val category: String?,
    val note: String?,
    val sender: String,
    val body: String,
    val kind: String = KIND_SPEND,
    val parentId: Long? = null,
    val companyOverride: Boolean? = null, // set on this one spend; null = follow its account
    val company: Boolean = companyOverride ?: false, // what it really is (filled in when read)
) {
    val manual get() = sender == MANUAL
    val counted get() = kind == KIND_SPEND
}

const val KIND_SPEND = "spend"
const val KIND_TRANSFER = "transfer"
const val KIND_IGNORED = "ignored"
const val KIND_PARENT = "parent"
const val MANUAL = "manual"

/** Which spends a screen shows: everything, only personal, or only company (work cards). */
const val BOOK_ALL = "all"
const val BOOK_PERSONAL = "personal"
const val BOOK_COMPANY = "company"

fun List<Txn>.inBook(book: String): List<Txn> = when (book) {
    BOOK_PERSONAL -> filter { !it.company }
    BOOK_COMPANY -> filter { it.company }
    else -> this
}

/** Contact category meaning "this payee is me" (my other account / my card): payments to it are transfers. */
const val OWN_ACCOUNT = "__own__"

data class Unread(val id: Long, val sender: String, val body: String, val ts: Long)

/** A person/shop you pay often. Any keyword found in the payee text maps to this contact. */
data class Contact(val id: Long, val name: String, val keywords: List<String>, val category: String?)

data class Category(val name: String, val emoji: String, val color: Long, val budgetPaise: Long?)

/** A bank account or card seen in your texts. Untracked ones (e.g. a shared/business card) are hidden from totals. */
data class Account(val key: String, val bank: String?, val account: String?, val tracked: Boolean, val count: Int, val totalPaise: Long, val company: Boolean = false)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, 3) {

    /** Bumped on every write so screens can refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    override fun onCreate(db: SQLiteDatabase) {
        // v1 schema, then every upgrade step, so new and old installs end up identical.
        db.execSQL(
            """CREATE TABLE txn(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount_paise INTEGER NOT NULL, payee TEXT, payee_raw TEXT, bank TEXT, account TEXT,
                ref TEXT, mode TEXT NOT NULL, ts INTEGER NOT NULL, lat REAL, lng REAL, place TEXT,
                category TEXT, note TEXT, sender TEXT NOT NULL, body TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX txn_ts ON txn(ts)")
        db.execSQL("CREATE TABLE unread(id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT, body TEXT, ts INTEGER, UNIQUE(sender, body, ts))")
        db.execSQL("CREATE TABLE category(name TEXT PRIMARY KEY, pos INTEGER)")
        db.execSQL("CREATE TABLE contact(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, keywords TEXT NOT NULL, category TEXT)")
        listOf("Food", "Groceries", "Travel", "Fuel", "Shopping", "Bills", "Flowers", "Health", "Fun", "Rent", "Other")
            .forEachIndexed { i, c -> db.insert("category", null, ContentValues().apply { put("name", c); put("pos", i) }) }
        onUpgrade(db, 1, 3)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE txn ADD COLUMN kind TEXT NOT NULL DEFAULT 'spend'")
            db.execSQL("ALTER TABLE txn ADD COLUMN parent_id INTEGER")
            db.execSQL("ALTER TABLE category ADD COLUMN emoji TEXT")
            db.execSQL("ALTER TABLE category ADD COLUMN color INTEGER")
            db.execSQL("ALTER TABLE category ADD COLUMN budget_paise INTEGER")
            db.execSQL("CREATE TABLE account(key TEXT PRIMARY KEY, bank TEXT, account TEXT, tracked INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("CREATE TABLE setting(key TEXT PRIMARY KEY, value TEXT)")
            DEFAULT_CATEGORIES.forEachIndexed { i, c ->
                db.insertWithOnConflict("category", null, ContentValues().apply { put("name", c.name); put("pos", i) }, SQLiteDatabase.CONFLICT_IGNORE)
                db.update("category", ContentValues().apply { put("emoji", c.emoji); put("color", c.color) }, "name=?", arrayOf(c.name))
            }
            // Accounts already seen in v1 data.
            db.execSQL("INSERT OR IGNORE INTO account(key, bank, account) SELECT DISTINCT IFNULL(bank,'')||'|'||IFNULL(account,''), bank, account FROM txn WHERE sender<>'manual'")
        }
        if (oldVersion < 3) {
            // Company vs personal: set per account (work card), and optionally per spend.
            db.execSQL("ALTER TABLE account ADD COLUMN company INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE txn ADD COLUMN company INTEGER")
        }
    }

    private fun changed() { _version.value++ }

    // ---------- transactions ----------

    /**
     * Inserts unless it's a duplicate. One payment often sends two texts (bank + UPI app),
     * so: same ref = duplicate; no ref but same amount within 3 minutes = duplicate.
     * Manual spends are never de-duplicated. Returns the new id, or null if it was a duplicate.
     */
    fun insertTxn(t: Txn): Long? {
        val db = writableDatabase
        val dupe = when {
            t.manual -> false
            // Same ref = same spend. Banks often send a second text (one with a ref, one without),
            // so same amount within 3 min also counts when either side has no ref.
            t.ref != null -> db.rawQuery(
                "SELECT 1 FROM txn WHERE ref=? OR (ref IS NULL AND amount_paise=? AND ABS(ts-?)<180000 AND sender<>'manual') LIMIT 1",
                arrayOf(t.ref, t.amountPaise.toString(), t.ts.toString())
            ).use { it.moveToFirst() }
            else -> db.rawQuery(
                "SELECT 1 FROM txn WHERE amount_paise=? AND ABS(ts-?)<180000 AND sender<>'manual' LIMIT 1",
                arrayOf(t.amountPaise.toString(), t.ts.toString())
            ).use { it.moveToFirst() }
        }
        if (dupe) return null
        val id = db.insert("txn", null, t.values())
        if (!t.manual) db.insertWithOnConflict("account", null, ContentValues().apply {
            put("key", accountKey(t.bank, t.account)); put("bank", t.bank); put("account", t.account)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        changed()
        return id
    }

    private fun Txn.values() = ContentValues().apply {
        put("amount_paise", amountPaise); put("payee", payee); put("payee_raw", payeeRaw)
        put("bank", bank); put("account", account); put("ref", ref); put("mode", mode)
        put("ts", ts); put("lat", lat); put("lng", lng); put("place", place)
        put("category", category); put("note", note); put("sender", sender); put("body", body)
        put("kind", kind); put("parent_id", parentId)
        put("company", companyOverride?.let { if (it) 1 else 0 })
    }

    /** Saves edits to an existing spend (amount, payee, category, note, time, mode, kind). */
    fun update(t: Txn) {
        writableDatabase.update("txn", t.values(), "id=?", arrayOf(t.id.toString()))
        changed()
    }

    fun setLocation(id: Long, lat: Double, lng: Double, place: String?) {
        writableDatabase.update("txn", ContentValues().apply { put("lat", lat); put("lng", lng); put("place", place) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun tag(id: Long, category: String?, note: String?) {
        writableDatabase.update("txn", ContentValues().apply { put("category", category); put("note", note) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Mark just this spend as company or personal (null = follow its account again). */
    fun setCompany(id: Long, company: Boolean?) {
        writableDatabase.update("txn", ContentValues().apply { put("company", company?.let { if (it) 1 else 0 }) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun setKind(id: Long, kind: String) {
        writableDatabase.update("txn", ContentValues().apply { put("kind", kind) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Only manual spends are really deleted; bank spends are hidden (kind=ignored) so they can come back. */
    fun deleteManual(id: Long) {
        writableDatabase.delete("txn", "id=? AND sender='manual'", arrayOf(id.toString()))
        changed()
    }

    /**
     * Split one payment into parts, e.g. ₹900 dinner = ₹300 mine (Food) + ₹600 friends (Other).
     * The original stays as a hidden "parent"; the parts are counted instead.
     */
    fun split(parent: Txn, parts: List<Pair<Long, String?>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("txn", "parent_id=?", arrayOf(parent.id.toString()))
            parts.forEach { (amt, cat) ->
                db.insert("txn", null, parent.copy(amountPaise = amt, category = cat, kind = KIND_SPEND, parentId = parent.id, ref = null).values())
            }
            db.update("txn", ContentValues().apply { put("kind", KIND_PARENT) }, "id=?", arrayOf(parent.id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    fun unsplit(parentId: Long) {
        val db = writableDatabase
        db.delete("txn", "parent_id=?", arrayOf(parentId.toString()))
        db.update("txn", ContentValues().apply { put("kind", KIND_SPEND) }, "id=?", arrayOf(parentId.toString()))
        changed()
    }

    fun children(parentId: Long): List<Txn> = query("SELECT * FROM txn WHERE parent_id=? ORDER BY id", parentId.toString())

    fun txn(id: Long): Txn? = query("SELECT * FROM txn WHERE id=?", id.toString()).firstOrNull()

    /** Every row (any kind) in a time range, newest first. */
    fun txnsBetween(from: Long, to: Long): List<Txn> =
        query("SELECT * FROM txn WHERE ts>=? AND ts<? ORDER BY ts DESC, id DESC", from.toString(), to.toString())

    /** Only what counts as spending. */
    fun spendsBetween(from: Long, to: Long): List<Txn> = txnsBetween(from, to).filter { it.counted }

    fun missingPlaceNames(): List<Txn> = query("SELECT * FROM txn WHERE lat IS NOT NULL AND place IS NULL")

    fun untagged(): List<Txn> = query("SELECT * FROM txn WHERE category IS NULL AND kind='spend' ORDER BY ts DESC")

    fun firstTs(): Long? = readableDatabase.rawQuery("SELECT MIN(ts) FROM txn", null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    /** Payees you've paid before, most used first, for autocomplete. */
    fun payeeSuggestions(prefix: String, limit: Int = 6): List<Pair<String, String?>> =
        readableDatabase.rawQuery(
            """SELECT payee, (SELECT category FROM txn t2 WHERE t2.payee=t.payee AND category IS NOT NULL ORDER BY ts DESC LIMIT 1)
               FROM txn t WHERE payee IS NOT NULL AND payee LIKE ? GROUP BY payee ORDER BY COUNT(*) DESC LIMIT ?""",
            arrayOf("%$prefix%", limit.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to (if (c.isNull(1)) null else c.getString(1))) } }

    /** Categories you used before for this payee, most recent first. */
    fun categoriesFor(payee: String?): List<String> {
        if (payee == null) return emptyList()
        return readableDatabase.rawQuery(
            "SELECT category FROM txn WHERE (payee=? OR payee_raw=?) AND category IS NOT NULL GROUP BY category ORDER BY MAX(ts) DESC LIMIT 3",
            arrayOf(payee, payee)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    /** Most used categories overall. */
    fun topCategories(limit: Int): List<String> =
        readableDatabase.rawQuery("SELECT category FROM txn WHERE category IS NOT NULL GROUP BY category ORDER BY COUNT(*) DESC LIMIT ?", arrayOf(limit.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /**
     * Learning: when you've put the same payee in the same category twice, remember it as a rule.
     * Returns the new contact's name if one was created.
     */
    fun learn(t: Txn, category: String): String? {
        val raw = t.payeeRaw ?: return null
        if (raw.length < 3 || ContactMatcher.match(contacts(), raw) != null) return null
        val n = readableDatabase.rawQuery("SELECT COUNT(*) FROM txn WHERE payee_raw=? AND category=? AND kind='spend'", arrayOf(raw, category))
            .use { c -> c.moveToFirst(); c.getInt(0) }
        if (n < 2) return null
        val name = t.payee ?: raw
        saveContact(null, name, listOf(raw), category)
        applyContactsToUntagged()
        return name
    }

    private fun query(sql: String, vararg args: String): List<Txn> =
        readableDatabase.rawQuery(sql.replaceFirst("SELECT * FROM txn", SELECT_TXN), args).use { c -> buildList { while (c.moveToNext()) add(c.toTxn()) } }

    private fun Cursor.toTxn() = Txn(
        id = getLong(getColumnIndexOrThrow("id")),
        amountPaise = getLong(getColumnIndexOrThrow("amount_paise")),
        payee = str("payee"), payeeRaw = str("payee_raw"), bank = str("bank"), account = str("account"),
        ref = str("ref"), mode = getString(getColumnIndexOrThrow("mode")), ts = getLong(getColumnIndexOrThrow("ts")),
        lat = dbl("lat"), lng = dbl("lng"), place = str("place"), category = str("category"), note = str("note"),
        sender = getString(getColumnIndexOrThrow("sender")), body = getString(getColumnIndexOrThrow("body")),
        kind = getString(getColumnIndexOrThrow("kind")), parentId = getColumnIndexOrThrow("parent_id").let { if (isNull(it)) null else getLong(it) },
        companyOverride = getColumnIndexOrThrow("company").let { if (isNull(it)) null else getInt(it) == 1 },
        company = getInt(getColumnIndexOrThrow("eff_company")) == 1,
    )

    private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.dbl(col: String): Double? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getDouble(it) }

    // ---------- unread texts ----------

    fun insertUnread(sender: String, body: String, ts: Long) {
        writableDatabase.insertWithOnConflict("unread", null, ContentValues().apply {
            put("sender", sender); put("body", body); put("ts", ts)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        changed()
    }

    fun unread(): List<Unread> =
        readableDatabase.rawQuery("SELECT id, sender, body, ts FROM unread ORDER BY ts DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(Unread(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }

    fun dismissUnread(id: Long) {
        writableDatabase.delete("unread", "id=?", arrayOf(id.toString()))
        changed()
    }

    // ---------- categories ----------

    fun categories(): List<Category> =
        readableDatabase.rawQuery("SELECT name, emoji, color, budget_paise FROM category ORDER BY pos, name", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Category(c.getString(0), if (c.isNull(1)) "•" else c.getString(1), if (c.isNull(2)) 0xFFB0ADA6 else c.getLong(2), if (c.isNull(3)) null else c.getLong(3))
                )
            }
        }

    fun addCategory(name: String, emoji: String, color: Long) {
        val n = name.trim()
        if (n.isEmpty()) return
        writableDatabase.insertWithOnConflict("category", null, ContentValues().apply {
            put("name", n); put("pos", 1000); put("emoji", emoji.ifBlank { "•" }); put("color", color)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        changed()
    }

    /** Spends in a deleted category become untagged again (nothing is lost). */
    fun deleteCategory(name: String) {
        val db = writableDatabase
        db.delete("category", "name=?", arrayOf(name))
        db.update("txn", ContentValues().apply { putNull("category") }, "category=?", arrayOf(name))
        changed()
    }

    fun setBudget(category: String, paise: Long?) {
        writableDatabase.update("category", ContentValues().apply { put("budget_paise", paise) }, "name=?", arrayOf(category))
        changed()
    }

    // ---------- accounts ----------

    fun accounts(): List<Account> =
        readableDatabase.rawQuery(
            """SELECT a.key, a.bank, a.account, a.tracked, a.company,
                 (SELECT COUNT(*) FROM txn t WHERE IFNULL(t.bank,'')||'|'||IFNULL(t.account,'')=a.key AND t.sender<>'manual'),
                 (SELECT IFNULL(SUM(amount_paise),0) FROM txn t WHERE IFNULL(t.bank,'')||'|'||IFNULL(t.account,'')=a.key AND t.kind='spend' AND t.sender<>'manual')
               FROM account a ORDER BY 6 DESC""", null
        ).use { c ->
            buildList { while (c.moveToNext()) add(Account(c.getString(0), c.getStringOrNull(1), c.getStringOrNull(2), c.getInt(3) == 1, c.getInt(5), c.getLong(6), c.getInt(4) == 1)) }
        }

    private fun Cursor.getStringOrNull(i: Int) = if (isNull(i)) null else getString(i)

    fun isTracked(bank: String?, account: String?): Boolean =
        readableDatabase.rawQuery("SELECT tracked FROM account WHERE key=?", arrayOf(accountKey(bank, account))).use { c -> !c.moveToFirst() || c.getInt(0) == 1 }

    /** Turning an account off hides its spends; turning it back on restores them. */
    fun setTracked(key: String, tracked: Boolean) {
        val db = writableDatabase
        db.update("account", ContentValues().apply { put("tracked", if (tracked) 1 else 0) }, "key=?", arrayOf(key))
        val (from, to) = if (tracked) KIND_IGNORED to KIND_SPEND else KIND_SPEND to KIND_IGNORED
        db.execSQL(
            "UPDATE txn SET kind=? WHERE kind=? AND sender<>'manual' AND IFNULL(bank,'')||'|'||IFNULL(account,'')=?",
            arrayOf(to, from, key)
        )
        changed()
    }

    /** A work card: all its spends become company spends (except ones you changed by hand). */
    fun setAccountCompany(key: String, company: Boolean) {
        writableDatabase.update("account", ContentValues().apply { put("company", if (company) 1 else 0) }, "key=?", arrayOf(key))
        changed()
    }

    /** True once any card or spend is marked company; until then the Personal/Company switch stays hidden. */
    fun hasCompany(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM account WHERE company=1 UNION SELECT 1 FROM txn WHERE company=1 LIMIT 1", null).use { it.moveToFirst() }

    var book: String
        get() = setting("book") ?: BOOK_ALL
        set(v) = putSetting("book", v)

    // ---------- settings ----------

    fun setting(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM setting WHERE key=?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun putSetting(key: String, value: String?) {
        writableDatabase.insertWithOnConflict("setting", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    var monthlyBudget: Long?
        get() = setting("budget")?.toLongOrNull()
        set(v) = putSetting("budget", v?.toString())

    // ---------- contacts (payee rules) ----------

    fun contacts(): List<Contact> =
        readableDatabase.rawQuery("SELECT id, name, keywords, category FROM contact ORDER BY name COLLATE NOCASE", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Contact(c.getLong(0), c.getString(1), c.getString(2).split('\n').filter { it.isNotBlank() }, if (c.isNull(3)) null else c.getString(3))
                )
            }
        }

    fun saveContact(id: Long?, name: String, keywords: List<String>, category: String?) {
        val cv = ContentValues().apply {
            put("name", name.trim())
            put("keywords", keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n"))
            put("category", category)
        }
        if (id == null) writableDatabase.insert("contact", null, cv) else writableDatabase.update("contact", cv, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun deleteContact(id: Long) {
        writableDatabase.delete("contact", "id=?", arrayOf(id.toString()))
        changed()
    }

    /**
     * After adding or editing a contact, apply it to past untagged spends too,
     * so "Lakshmi -> Flowers" also fixes the ones already in the list.
     */
    fun applyContactsToUntagged() {
        val contacts = contacts()
        untagged().forEach { t ->
            val c = ContactMatcher.match(contacts, t.payeeRaw ?: t.payee) ?: return@forEach
            writableDatabase.update("txn", ContentValues().apply {
                put("payee", c.name)
                when (c.category) {
                    null -> {}
                    OWN_ACCOUNT -> put("kind", KIND_TRANSFER)
                    else -> put("category", c.category)
                }
            }, "id=?", arrayOf(t.id.toString()))
        }
        changed()
    }

    /** Called after a backup is restored over the database file. */
    fun reopen() {
        close()
        changed()
    }

    companion object {
        const val NAME = "expenses.db"
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }
        fun accountKey(bank: String?, account: String?) = "${bank ?: ""}|${account ?: ""}"

        // Every txn read also gets eff_company: its own choice, else its account's, else personal.
        private const val SELECT_TXN = "SELECT t.*, COALESCE(t.company, (SELECT a.company FROM account a WHERE a.key=IFNULL(t.bank,'')||'|'||IFNULL(t.account,'')), 0) AS eff_company FROM txn t"

        val DEFAULT_CATEGORIES = listOf(
            Category("Food", "🍜", 0xFFFF9F6B, null),
            Category("Groceries", "🛒", 0xFF9BD67A, null),
            Category("Travel", "🚕", 0xFF6BB8FF, null),
            Category("Fuel", "⛽", 0xFFFFC857, null),
            Category("Shopping", "🛍️", 0xFFE58BFF, null),
            Category("Bills", "💡", 0xFF7FE0D4, null),
            Category("Subscriptions", "🔁", 0xFFB49BFF, null),
            Category("Health", "💊", 0xFFFF7B9C, null),
            Category("Fun", "🎬", 0xFFFF6BD0, null),
            Category("Personal care", "💈", 0xFFF3B8A0, null),
            Category("Education", "📚", 0xFF8FA8FF, null),
            Category("Rent", "🏠", 0xFFC9A27A, null),
            Category("Gifts", "🎁", 0xFFFF8A8A, null),
            Category("Investments", "📈", 0xFF7FE0B2, null),
            Category("Flowers", "🌸", 0xFFFFB3D1, null),
            Category("Other", "✨", 0xFFB0ADA6, null),
        )
    }
}

object ContactMatcher {
    /** Case-insensitive "contains" on any keyword (or the contact's name if it has no keywords). Longest keyword wins. */
    fun match(contacts: List<Contact>, payee: String?): Contact? {
        if (payee.isNullOrBlank()) return null
        val p = payee.lowercase()
        return contacts
            .flatMap { c -> (c.keywords.ifEmpty { listOf(c.name) }).map { it.lowercase().trim() to c } }
            .filter { (k, _) -> k.isNotEmpty() && p.contains(k) }
            .maxByOrNull { it.first.length }
            ?.second
    }
}
