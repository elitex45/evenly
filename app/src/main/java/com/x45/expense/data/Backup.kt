package com.x45.expense.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything stays on the phone. These write/read files you choose (Downloads, Drive app, etc.). */
object Backup {

    fun exportCsv(ctx: Context, uri: Uri): Int {
        val rows = Db.get(ctx).txnsBetween(0, Long.MAX_VALUE).filter { it.kind != KIND_PARENT }
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val t = SimpleDateFormat("HH:mm", Locale.US)
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
            w.write("date,time,amount,payee,category,counted,kind,mode,bank,account,place,note,book\n")
            rows.forEach { x ->
                w.write(listOf(
                    d.format(Date(x.ts)), t.format(Date(x.ts)), "%.2f".format(Locale.US, x.amountPaise / 100.0), q(x.payee), q(x.category),
                    if (x.counted) "yes" else "no", x.kind, q(x.mode), q(x.bank), q(x.account), q(x.place), q(x.note), if (x.company) "company" else "personal",
                ).joinToString(",") + "\n")
            }
        } ?: error("Couldn't open file")
        return rows.size
    }

    /** Copies the whole database to the chosen file. */
    fun save(ctx: Context, uri: Uri) {
        val db = Db.get(ctx)
        db.reopen() // flush & close so the file is complete
        ctx.contentResolver.openOutputStream(uri)?.use { out -> ctx.getDatabasePath(Db.NAME).inputStream().use { it.copyTo(out) } }
            ?: error("Couldn't open file")
    }

    /**
     * Replaces the database with a backup. The current one is kept as before-restore.db first.
     * Throws if the file isn't an Evenly backup.
     */
    fun restore(ctx: Context, uri: Uri) {
        val tmp = File(ctx.cacheDir, "restore.db")
        ctx.contentResolver.openInputStream(uri)?.use { inp -> tmp.outputStream().use { inp.copyTo(it) } } ?: error("Couldn't open file")
        val head = tmp.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        require(String(head, Charsets.US_ASCII).startsWith("SQLite format 3")) { "That isn't an Evenly backup file." }
        val ok = android.database.sqlite.SQLiteDatabase.openDatabase(tmp.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='txn'", null).use { it.moveToFirst() }
        }
        require(ok) { "That isn't an Evenly backup file." }
        val db = Db.get(ctx)
        db.reopen()
        val live = ctx.getDatabasePath(Db.NAME)
        live.copyTo(File(live.parentFile, "before-restore.db"), overwrite = true)
        tmp.copyTo(live, overwrite = true)
        tmp.delete()
        db.reopen()
    }
}
