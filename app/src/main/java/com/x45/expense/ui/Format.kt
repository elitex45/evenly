package com.x45.expense.ui

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val inr = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

/** 125000 paise -> "₹1,250" (paise shown only when non-zero). */
fun rupees(paise: Long): String {
    val s = inr.format(paise / 100.0)
    return if (paise % 100 == 0L) s.removeSuffix(".00") else s
}

fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

fun timeOf(ts: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))
fun dayOf(ts: Long): String = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(ts))
fun monthOf(ts: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(ts))

fun startOfDay(ts: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
    timeInMillis = ts; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun startOfMonth(offset: Int): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.MONTH, offset)
}.timeInMillis

/** Compact money for tight spots: ₹850, ₹12.4k, ₹1.2L, ₹2.1Cr. */
fun short(paise: Long): String {
    val r = paise / 100.0
    fun f(x: Double) = if (x >= 100 || x % 1.0 < 0.05) "%.0f".format(x) else "%.1f".format(x)
    return when {
        r >= 1_00_00_000 -> "₹${f(r / 1_00_00_000)}Cr"
        r >= 1_00_000 -> "₹${f(r / 1_00_000)}L"
        r >= 1_000 -> "₹${f(r / 1_000)}k"
        else -> "₹%.0f".format(r)
    }
}

/** "Today", "Yesterday", or "Wed, 8 Oct". */
fun dayLabel(ts: Long): String {
    val d = startOfDay(ts)
    val today = startOfDay()
    return when {
        d == today -> "Today"
        d == startOfDay(today - 3_600_000L) -> "Yesterday"
        else -> dayOf(ts)
    }
}

fun monthName(ts: Long): String = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date(ts))
fun dateTimeOf(ts: Long): String = SimpleDateFormat("EEE, d MMM yyyy · h:mm a", Locale.getDefault()).format(Date(ts))

fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

/** Parses "1,250.50" into paise; null if it isn't a positive number. */
fun parsePaise(s: String): Long? = s.replace(",", "").trim().toBigDecimalOrNull()
    ?.takeIf { it.signum() > 0 }?.movePointRight(2)?.setScale(0, java.math.RoundingMode.HALF_UP)?.toLong()
