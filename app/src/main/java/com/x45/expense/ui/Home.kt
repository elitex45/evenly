package com.x45.expense.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x45.expense.data.BOOK_ALL
import com.x45.expense.data.BOOK_COMPANY
import com.x45.expense.data.BOOK_PERSONAL
import com.x45.expense.data.Stats
import com.x45.expense.data.inBook
import com.x45.expense.ui.theme.Ink
import com.x45.expense.ui.theme.Type
import com.x45.expense.ui.theme.neu
import com.x45.expense.ui.theme.tap
import java.util.Calendar

@Composable
fun HomeScreen(importing: Boolean) {
    val db = rememberDb()
    val v = dbVersion(db)
    val nav = LocalNav.current
    val cats = remember(v) { db.categories().associateBy { it.name } }
    val monthStart = startOfMonth(0)
    val book = shownBook(db, v)
    val allMonth = remember(v) { db.spendsBetween(monthStart, startOfMonth(1)) }
    val month = allMonth.inBook(book ?: BOOK_ALL)
    val lastMonth = remember(v, book) { db.spendsBetween(startOfMonth(-1), monthStart).inBook(book ?: BOOK_ALL) }
    val todayStart = startOfDay()
    val today = remember(v) { db.txnsBetween(todayStart, todayStart + 86_400_000L).inBook(book ?: BOOK_ALL) }
    val untagged = remember(v) { db.untagged() }
    val unread = remember(v) { db.unread().size }
    // The budget is for your own money, so it never counts company spends.
    val budget = remember(v) { db.monthlyBudget }.takeIf { book != BOOK_COMPANY }
    val spent = Stats.total(month)
    val budgetSpent = Stats.total(allMonth.inBook(if (book == null) BOOK_ALL else BOOK_PERSONAL))
    val cal = Calendar.getInstance()
    val dom = cal.get(Calendar.DAY_OF_MONTH)
    val dim = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

    Page(title = greeting(), eyebrow = dayOf(System.currentTimeMillis()), trailing = {
        RoundIcon(Icons.Default.Search, "Search", { nav.tab(Route.Spends()) })
    }) {
        if (book != null) item { BookSwitch(db, book) }
        // Hero: this month, against the budget.
        item {
            Card(padding = 22.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (book == BOOK_COMPANY) "COMPANY · " else if (book == BOOK_PERSONAL) "PERSONAL · " else "") + "SPENT IN ${monthName(monthStart).uppercase()}", style = Type.Label)
                        Spacer(Modifier.height(6.dp))
                        Money(spent, Type.Hero)
                        Spacer(Modifier.height(6.dp))
                        val change = Stats.change(Stats.projected(spent, dom, dim), Stats.total(lastMonth))
                        Text(
                            when {
                                month.isEmpty() -> "Spends show up here by themselves."
                                change == null -> "On pace for ${short(Stats.projected(spent, dom, dim))} this month"
                                change > 0 -> "On pace for ${short(Stats.projected(spent, dom, dim))} · $change% above last month"
                                else -> "On pace for ${short(Stats.projected(spent, dom, dim))} · ${-change}% below last month"
                            },
                            style = Type.Small,
                        )
                    }
                    if (budget != null && budget > 0) {
                        Ring(budgetSpent.toFloat() / budget, Modifier.size(84.dp)) {
                            Text("${(budgetSpent * 100 / budget)}%", style = Type.Body.copy(fontWeight = FontWeight(800)))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (budget != null && budget > 0) {
                    val left = budget - budgetSpent
                    Row {
                        Stat(if (left >= 0) "LEFT" else "OVER BY", rupees(kotlin.math.abs(left)), Modifier.weight(1f), if (left >= 0) Ink.Text else Ink.Coral)
                        Stat("SAFE PER DAY", rupees(Stats.safePerDay(budget, budgetSpent, dim - dom + 1) / 100 * 100), Modifier.weight(1f))
                    }
                    if (book == BOOK_ALL) Text("Budget counts personal spends only.", style = Type.Small, modifier = Modifier.padding(top = 8.dp))
                } else if (book != BOOK_COMPANY) {
                    Text("Set a monthly budget →", style = Type.Small.copy(color = Ink.Gold, fontWeight = FontWeight(700)),
                        modifier = Modifier.tap { nav.go(Route.Budgets) })
                }
            }
        }

        // The one daily job: review new spends.
        item {
            if (untagged.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().neu().border(1.dp, Ink.Gold.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                        .tap { nav.go(Route.Review) }.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✍️", fontSize = 26.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${plural(untagged.size, "spend")} to review", style = Type.H2)
                        Text("Pick a category for each. Under a minute.", style = Type.Small)
                    }
                    Text("Start", style = Type.Body.copy(color = Ink.Gold, fontWeight = FontWeight(800)))
                }
            } else if (month.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", color = Ink.Mint, fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("All caught up. Every spend has a category.", style = Type.Small.copy(color = Ink.Mint))
                }
            }
        }

        if (importing) item { Text("Reading your past bank texts…", style = Type.Small.copy(color = Ink.Gold)) }

        if (unread > 0) item {
            Card(onClick = { nav.go(Route.Unread) }) {
                Text("${plural(unread, "bank text")} I couldn't read", style = Type.Body.copy(fontWeight = FontWeight(700)))
                Text("Have a look. Add any that were spends.", style = Type.Small)
            }
        }

        // Today.
        item { SectionLabel("Today · ${rupees(Stats.total(today.filter { it.counted }))}") }
        if (today.isEmpty()) {
            item {
                Card {
                    Empty("🌙", "Nothing spent today", "When your bank texts you about a payment, it shows up here by itself.", "Add a cash spend") { nav.go(Route.Add()) }
                }
            }
        } else {
            item {
                Card(padding = 12.dp) {
                    today.forEach { t -> TxnRow(t, cats[t.category], { nav.go(Route.Detail(t.id)) }) }
                }
            }
        }

        // Where the month went.
        val byCat = Stats.byCategory(month).take(4)
        if (byCat.isNotEmpty()) {
            item { SectionLabel("This month", trailing = "INSIGHTS →", onTrailing = { nav.tab(Route.Insights) }) }
            item {
                Card {
                    byCat.forEach { s ->
                        val c = cats[s.key]
                        Row(Modifier.fillMaxWidth().tap { nav.tab(Route.Spends(s.key)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CatIcon(c, size = 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row {
                                    Text(s.key, style = Type.Body, modifier = Modifier.weight(1f))
                                    Text(rupees(s.paise), style = Type.Body.copy(fontWeight = FontWeight(700)))
                                }
                                Spacer(Modifier.height(6.dp))
                                Bar(s.paise.toFloat() / byCat[0].paise, c.tint(), height = 5.dp)
                            }
                        }
                    }
                }
            }
            Stats.insights(month, lastMonth, "month").firstOrNull()?.let { tip ->
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                        Text("💡", fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(tip, style = Type.Small)
                    }
                }
            }
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Ink.Text) {
    Column(modifier) {
        Text(label, style = Type.Label)
        Text(value, style = Type.Money.copy(fontSize = 20.sp, color = color))
    }
}
