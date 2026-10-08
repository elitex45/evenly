package com.x45.expense.sms

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the parser over a real SMS export and writes what it made of each text.
 * Only runs when sms-export/work/corpus.tsv exists (it is private, never in the repo).
 * Input: who \t sender \t date \t body (newlines as \n). Output: corpus-out.tsv next to it.
 */
class CorpusReport {
    @Test fun report() {
        val inp = File("../sms-export/work/corpus.tsv")
        assumeTrue(inp.exists())
        fun unesc(s: String) = s.replace("\\n", "\n").replace("\\\\", "\\")
        fun esc(s: String?) = (s ?: "").replace("\\", "\\\\").replace("\n", "\\n").replace("\t", " ")
        File(inp.parentFile, "corpus-out.tsv").bufferedWriter().use { w ->
            inp.forEachLine { line ->
                val f = line.split('\t')
                val sender = f[1]
                val body = unesc(f[3])
                val out = when (val r = SmsParser.parse(sender, body)) {
                    is ParseResult.Debit -> listOf(if (Classify.isTransfer(r.txn.payee, body)) "TRANSFER" else "DEBIT", "%d.%02d".format(r.txn.amountPaise / 100, r.txn.amountPaise % 100),
                        r.txn.payee, r.txn.bank, r.txn.account, r.txn.mode, r.txn.ref)
                    ParseResult.Unreadable -> listOf("UNREADABLE", "", "", Banks.bankForSender(sender), "", "", "")
                    ParseResult.Ignore -> listOf("IGNORE", "", "", Banks.bankForSender(sender), "", "", "")
                }
                w.write((listOf(f[0], sender, f[2]) + out + body).joinToString("\t") { esc(it) } + "\n")
            }
        }
    }
}
