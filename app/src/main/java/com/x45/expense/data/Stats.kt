package com.x45.expense.data

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure number crunching for the Home and Insights screens. Input is already filtered to counted spends. */
object Stats {

    data class Slice(val key: String, val paise: Long, val count: Int)
    data class Recurring(val payee: String, val paise: Long, val months: Int, val lastTs: Long, val nextTs: Long)

    fun total(t: List<Txn>) = t.sumOf { it.amountPaise }

    /** Untagged spends are grouped under "Untagged". Biggest first. */
    fun byCategory(t: List<Txn>): List<Slice> = group(t) { it.category ?: UNTAGGED }

    fun byPayee(t: List<Txn>): List<Slice> = group(t.filter { it.payee != null }) { it.payee!! }

    fun byBank(t: List<Txn>): List<Slice> = group(t) { x ->
        when {
            x.manual -> "Cash / manual"
            else -> listOfNotNull(x.bank, x.account?.let { "••$it" }).joinToString(" ").ifBlank { "Unknown" }
        }
    }

    fun byPlace(t: List<Txn>): List<Slice> = group(t.filter { it.place != null }) { it.place!! }

    private fun group(t: List<Txn>, key: (Txn) -> String) =
        t.groupBy(key).map { (k, v) -> Slice(k, v.sumOf { it.amountPaise }, v.size) }.sortedByDescending { it.paise }

    /** Spend per day for [days] days starting at [from] (local midnight). */
    fun perDay(t: List<Txn>, from: Long, days: Int): LongArray {
        val out = LongArray(days)
        val cal = Calendar.getInstance()
        t.forEach { x ->
            cal.timeInMillis = from
            var i = 0
            // Walk calendar days (DST-safe enough; India has no DST anyway).
            while (i < days) {
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                if (x.ts >= start && x.ts < cal.timeInMillis) { out[i] += x.amountPaise; break }
                i++
            }
        }
        return out
    }

    /** Spend per weekday, index 0 = Monday. */
    fun perWeekday(t: List<Txn>): LongArray {
        val out = LongArray(7)
        val cal = Calendar.getInstance()
        t.forEach { cal.timeInMillis = it.ts; out[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7] += it.amountPaise }
        return out
    }

    /** Percentage change, or null when there is nothing to compare against. */
    fun change(now: Long, before: Long): Int? = if (before <= 0) null else (((now - before) * 100.0) / before).roundToInt()

    /** Where the month will end at today's pace. */
    fun projected(spent: Long, dayOfMonth: Int, daysInMonth: Int): Long =
        if (dayOfMonth <= 0) spent else spent * daysInMonth / dayOfMonth

    /** How much you can spend per day for the rest of the month and still hit your budget. */
    fun safePerDay(budget: Long, spent: Long, daysLeftIncludingToday: Int): Long =
        if (daysLeftIncludingToday <= 0) 0 else maxOf(0, budget - spent) / daysLeftIncludingToday

    /**
     * Payments that repeat monthly: same payee, similar amount (within 10%), in at least 2 different
     * months, about a month apart (25-35 days). Netflix, rent, gym, SIPs.
     */
    fun recurring(all: List<Txn>): List<Recurring> {
        val cal = Calendar.getInstance()
        return all.filter { it.payee != null }.groupBy { it.payee!!.lowercase() }.mapNotNull { (_, list) ->
            val sorted = list.sortedBy { it.ts }
            val last = sorted.last()
            val similar = sorted.filter { abs(it.amountPaise - last.amountPaise) <= last.amountPaise / 10 }
            if (similar.size < 2) return@mapNotNull null
            val months = similar.map { cal.timeInMillis = it.ts; cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH) }.distinct()
            if (months.size < 2) return@mapNotNull null
            val gaps = similar.zipWithNext { a, b -> (b.ts - a.ts) / DAY }
            if (gaps.none { it in 25..35 }) return@mapNotNull null
            Recurring(last.payee!!, last.amountPaise, months.size, last.ts, last.ts + 30 * DAY)
        }.sortedByDescending { it.paise }
    }

    /** Short plain-English observations, most useful first. [prev] is the same length period just before. */
    fun insights(cur: List<Txn>, prev: List<Txn>, periodWord: String): List<String> {
        val out = mutableListOf<String>()
        val now = total(cur)
        val before = total(prev)
        change(now, before)?.let { c ->
            when {
                c >= 10 -> out += "You spent $c% more than last $periodWord."
                c <= -10 -> out += "You spent ${-c}% less than last $periodWord. Nice."
                else -> out += "About the same as last $periodWord."
            }
        }
        val prevCats = byCategory(prev).associate { it.key to it.paise }
        byCategory(cur).filter { it.key != UNTAGGED }.mapNotNull { s ->
            val b = prevCats[s.key] ?: return@mapNotNull null
            val c = change(s.paise, b) ?: return@mapNotNull null
            if (c >= 30 && s.paise - b >= 50_000) Triple(s.key, c, s.paise - b) else null
        }.maxByOrNull { it.third }?.let { (cat, c, _) -> out += "$cat is up $c% vs last $periodWord." }
        cur.maxByOrNull { it.amountPaise }?.let { big ->
            if (cur.size >= 3) out += "Biggest spend: ${money(big.amountPaise)} at ${big.payee ?: "unknown"}."
        }
        if (cur.size >= 7) {
            val wd = perWeekday(cur)
            val top = wd.indices.maxBy { wd[it] }
            if (wd[top] * 7 > total(cur) * 2) out += "${WEEKDAYS[top]}s are your big spending days."
        }
        byPayee(cur).firstOrNull()?.let { p -> if (p.count >= 4) out += "You paid ${p.key} ${p.count} times." }
        return out
    }

    private fun money(paise: Long) = "₹" + String.format(java.util.Locale("en", "IN"), "%,d", paise / 100)

    const val UNTAGGED = "Untagged"
    private const val DAY = 86_400_000L
    val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
}
