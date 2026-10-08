package com.x45.expense.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x45.expense.data.BOOK_ALL
import com.x45.expense.data.Stats
import com.x45.expense.data.inBook
import com.x45.expense.ui.theme.Ink
import com.x45.expense.ui.theme.Type
import com.x45.expense.ui.theme.tap
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class Period(val word: String) { Week("week"), Month("month"), Year("year") }

/** Start of the period [offset] steps from the current one. */
private fun periodStart(p: Period, offset: Int): Long = Calendar.getInstance().apply {
    timeInMillis = startOfDay()
    when (p) {
        Period.Week -> { firstDayOfWeek = Calendar.MONDAY; while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) add(Calendar.DAY_OF_YEAR, -1); add(Calendar.WEEK_OF_YEAR, offset) }
        Period.Month -> { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, offset) }
        Period.Year -> { set(Calendar.DAY_OF_YEAR, 1); add(Calendar.YEAR, offset) }
    }
}.timeInMillis

private fun periodTitle(p: Period, offset: Int, from: Long): String = when {
    offset == 0 -> "This ${p.word}"
    offset == -1 -> "Last ${p.word}"
    p == Period.Week -> "Week of " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(from))
    p == Period.Month -> monthOf(from)
    else -> SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(from))
}

@Composable
fun InsightsScreen() {
    val db = rememberDb()
    val v = dbVersion(db)
    val nav = LocalNav.current
    var period by rememberSaveable { mutableStateOf(Period.Month) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val from = periodStart(period, offset)
    val to = periodStart(period, offset + 1)
    val prevFrom = periodStart(period, offset - 1)
    val book = shownBook(db, v)
    val cur = remember(v, period, offset, book) { db.spendsBetween(from, to).inBook(book ?: BOOK_ALL) }
    val prev = remember(v, period, offset, book) { db.spendsBetween(prevFrom, from).inBook(book ?: BOOK_ALL) }
    val cats = remember(v) { db.categories().associateBy { it.name } }
    val recurring = remember(v) { Stats.recurring(db.spendsBetween(System.currentTimeMillis() - 200 * 86_400_000L, Long.MAX_VALUE).inBook(book ?: BOOK_ALL)) }
    val total = Stats.total(cur)
    val days = ((minOf(to, System.currentTimeMillis()) - from) / 86_400_000L + 1).coerceAtLeast(1)

    Page("Insights", eyebrow = "Where your money goes") {
        if (book != null) item { BookSwitch(db, book) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { p -> Pill(p.name, period == p, { period = p; offset = 0 }) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous", { offset-- }, size = 40.dp)
                Text(periodTitle(period, offset, from), style = Type.H2, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                RoundIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", { if (offset < 0) offset++ }, size = 40.dp, tint = if (offset < 0) Ink.Text else Ink.Faint)
            }
        }
        item {
            Card(padding = 22.dp) {
                Text("TOTAL SPENT", style = Type.Label)
                Money(total, Type.Hero)
                val ch = Stats.change(total, Stats.total(prev))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ch != null) {
                        Text(
                            (if (ch >= 0) "▲ $ch%" else "▼ ${-ch}%") + " vs last ${period.word}",
                            style = Type.Small.copy(color = if (ch > 0) Ink.Coral else Ink.Mint, fontWeight = FontWeight(700)),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text("${rupees(total / days)}/day avg · ${plural(cur.size, "spend")}", style = Type.Small)
                }
                Spacer(Modifier.height(20.dp))
                val (vals, labels, hi) = bars(period, from, cur, offset == 0)
                Bars(vals, labels, highlight = hi)
            }
        }

        if (cur.isEmpty()) {
            item { Card { Empty("📊", "Nothing to show yet", "Once a few spends come in, you'll see where your money goes.") } }
            return@Page
        }

        val byCat = Stats.byCategory(cur)
        item { SectionLabel("By category") }
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Donut(byCat.map { cats[it.key].tint() to it.paise }, Modifier.size(150.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(byCat.size.toString(), style = Type.Title)
                            Text("CATEGORIES", style = Type.Label.copy(fontSize = 9.sp))
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        byCat.take(5).forEach { s ->
                            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(cats[s.key].tint()))
                                Spacer(Modifier.width(8.dp))
                                Text(s.key, style = Type.Small.copy(color = Ink.Text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${s.paise * 100 / total.coerceAtLeast(1)}%", style = Type.Small)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                byCat.forEach { s ->
                    Row(Modifier.fillMaxWidth().tap { nav.tab(Route.Spends(s.key)) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        CatIcon(cats[s.key], size = 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.key, style = Type.Body)
                            Text(plural(s.count, "spend"), style = Type.Small)
                        }
                        Text(rupees(s.paise), style = Type.Money)
                    }
                }
            }
        }

        val tips = Stats.insights(cur, prev, period.word)
        if (tips.isNotEmpty()) {
            item { SectionLabel("Worth knowing") }
            item {
                Card {
                    tips.forEach { tip ->
                        Row(Modifier.padding(vertical = 6.dp)) {
                            Text("💡", fontSize = 14.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(tip, style = Type.Body.copy(fontSize = 14.sp))
                        }
                    }
                }
            }
        }

        item { SectionLabel("Top payees") }
        item {
            Card {
                Stats.byPayee(cur).take(5).forEachIndexed { i, s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = Type.Title.copy(fontSize = 20.sp, color = Ink.Gold), modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.key, style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(plural(s.count, "time"), style = Type.Small)
                        }
                        Text(rupees(s.paise), style = Type.Money)
                    }
                }
            }
        }

        val located = cur.filter { it.lat != null }
        if (located.isNotEmpty()) {
            item { SectionLabel("Where you spent") }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))) {
                    SpendMap(located, Modifier.fillMaxWidth().height(220.dp))
                }
            }
            val places = Stats.byPlace(cur).take(4)
            if (places.isNotEmpty()) item {
                Card {
                    places.forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                            Text("📍 " + s.key, style = Type.Body.copy(fontSize = 14.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(rupees(s.paise), style = Type.Body.copy(fontWeight = FontWeight(700)))
                        }
                    }
                }
            }
        }

        if (recurring.isNotEmpty()) {
            item { SectionLabel("Repeats every month") }
            item {
                Card {
                    recurring.take(6).forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🔁", fontSize = 16.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.payee, style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Next around ${dayOf(r.nextTs)}", style = Type.Small)
                            }
                            Text(rupees(r.paise), style = Type.Money)
                        }
                    }
                    Text("Total ${rupees(recurring.sumOf { it.paise })} a month on repeat", style = Type.Small.copy(color = Ink.Gold), modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        item { SectionLabel("By account & card") }
        item {
            Card {
                Stats.byBank(cur).forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text(s.key, style = Type.Body.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
                        Text(rupees(s.paise), style = Type.Body.copy(fontWeight = FontWeight(700)))
                    }
                }
            }
        }
    }
}

/** Bar values, labels and which bar is "now" for the selected period. */
private fun bars(p: Period, from: Long, cur: List<com.x45.expense.data.Txn>, isCurrent: Boolean): Triple<List<Long>, List<String>, Int?> {
    val cal = Calendar.getInstance()
    return when (p) {
        Period.Week -> {
            val d = Stats.perDay(cur, from, 7)
            val today = ((startOfDay() - from) / 86_400_000L).toInt()
            Triple(d.toList(), listOf("M", "T", "W", "T", "F", "S", "S"), if (isCurrent) today else null)
        }
        Period.Month -> {
            cal.timeInMillis = from
            val n = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val d = Stats.perDay(cur, from, n)
            val today = Calendar.getInstance().get(Calendar.DAY_OF_MONTH) - 1
            Triple(d.toList(), (1..n).map { if (it == 1 || it % 5 == 0) "$it" else "" }, if (isCurrent) today else null)
        }
        Period.Year -> {
            val m = LongArray(12)
            cur.forEach { cal.timeInMillis = it.ts; m[cal.get(Calendar.MONTH)] += it.amountPaise }
            Triple(m.toList(), listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"), if (isCurrent) Calendar.getInstance().get(Calendar.MONTH) else null)
        }
    }
}
