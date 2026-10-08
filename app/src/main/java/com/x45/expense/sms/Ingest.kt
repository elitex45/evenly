package com.x45.expense.sms

import android.content.Context
import com.x45.expense.data.ContactMatcher
import com.x45.expense.data.Db
import com.x45.expense.data.KIND_IGNORED
import com.x45.expense.data.KIND_SPEND
import com.x45.expense.data.KIND_TRANSFER
import com.x45.expense.data.OWN_ACCOUNT
import com.x45.expense.data.Txn

/** Turns one SMS into a stored spend (or an "unread" entry). Shared by the live receiver and the inbox import. */
object Ingest {

    /** Returns the new txn id when a row was stored. */
    fun handle(ctx: Context, sender: String, body: String, ts: Long): Long? {
        val db = Db.get(ctx)
        return when (val r = SmsParser.parse(sender, body)) {
            is ParseResult.Ignore -> null
            is ParseResult.Unreadable -> { db.insertUnread(sender, body, ts); null }
            is ParseResult.Debit -> {
                val p = r.txn
                val contact = ContactMatcher.match(db.contacts(), p.payee)
                val kind = when {
                    !db.isTracked(p.bank, p.account) -> KIND_IGNORED
                    contact?.category == OWN_ACCOUNT -> KIND_TRANSFER
                    Classify.isTransfer(p.payee, body) -> KIND_TRANSFER
                    else -> KIND_SPEND
                }
                // Your own rule wins; otherwise a well-known shop (Swiggy -> Food) is tagged for you.
                val known = db.categories().map { it.name }.toSet()
                val category = contact?.category?.takeIf { it in known }
                    ?: Merchants.categoryFor(p.payee)?.takeIf { it in known }
                db.insertTxn(
                    Txn(
                        id = 0, amountPaise = p.amountPaise,
                        payee = contact?.name ?: p.payee, payeeRaw = p.payee,
                        bank = p.bank, account = p.account, ref = p.ref, mode = p.mode, ts = ts,
                        lat = null, lng = null, place = null,
                        category = category, note = null, sender = sender, body = body, kind = kind,
                    )
                )
            }
        }
    }

    /** Reads the SMS inbox once so past spends show up. Returns how many rows were added. */
    fun importInbox(ctx: Context, days: Int = 90): Int {
        val since = System.currentTimeMillis() - days * 86_400_000L
        var added = 0
        ctx.contentResolver.query(
            android.provider.Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf("address", "body", "date"), "date>=?", arrayOf(since.toString()), "date ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(0) ?: continue
                val body = c.getString(1) ?: continue
                if (handle(ctx, sender, body, c.getLong(2)) != null) added++
            }
        }
        return added
    }
}
