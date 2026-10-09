package com.x45.expense.ui

import com.x45.expense.data.inBook
import com.x45.expense.data.BOOK_ALL
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x45.expense.data.Category
import com.x45.expense.data.KIND_IGNORED
import com.x45.expense.data.KIND_PARENT
import com.x45.expense.data.KIND_SPEND
import com.x45.expense.data.KIND_TRANSFER
import com.x45.expense.data.Stats
import com.x45.expense.data.Txn
import com.x45.expense.ui.theme.Ink
import com.x45.expense.ui.theme.Type
import com.x45.expense.ui.theme.tap

/** Every spend, by month, grouped by day. Search, filter by category, show hidden ones. */
@Composable
fun SpendsScreen(initialCategory: String?) {
    val db = rememberDb()
    val v = dbVersion(db)
    val nav = LocalNav.current
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf(initialCategory) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val cats = remember(v) { db.categories() }
    val byName = cats.associateBy { it.name }
    val from = startOfMonth(offset)
    val book = shownBook(db, v)
    val all = remember(v, offset) { db.txnsBetween(from, startOfMonth(offset + 1)).inBook(book ?: BOOK_ALL) }
    val q = query.trim().lowercase()
    val shown = all.filter { t ->
        (showHidden || t.kind == KIND_SPEND) &&
            (cat == null || (if (cat == Stats.UNTAGGED) t.category == null else t.category == cat)) &&
            (q.isEmpty() || listOfNotNull(t.payee, t.payeeRaw, t.note, t.place, t.category, t.bank).any { it.lowercase().contains(q) } ||
                rupees(t.amountPaise).replace(",", "").contains(q))
    }
    val hiddenCount = all.count { it.kind != KIND_SPEND }
    var sharing by remember { mutableStateOf<Txn?>(null) }

    Page("Spends", eyebrow = plural(shown.count { it.counted }, "spend") + " · " + rupees(Stats.total(shown.filter { it.counted }))) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month", { offset-- }, size = 40.dp)
                Text(monthOf(from), style = Type.H2, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                RoundIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month", { if (offset < 0) offset++ }, size = 40.dp,
                    tint = if (offset < 0) Ink.Text else Ink.Faint)
            }
        }
        if (book != null) item { BookSwitch(db, book) }
        item { Field(query, { query = it }, "Search payee, note, place or amount") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                item { Pill("All", cat == null, { cat = null }) }
                item { Pill(Stats.UNTAGGED, cat == Stats.UNTAGGED, { cat = Stats.UNTAGGED }, leading = "?") }
                items(cats) { c -> Pill(c.name, cat == c.name, { cat = if (cat == c.name) null else c.name }, leading = c.emoji) }
            }
        }
        if (hiddenCount > 0) item {
            Text(
                if (showHidden) "Hide transfers & hidden ($hiddenCount)" else "Show transfers & hidden ($hiddenCount)",
                style = Type.Small.copy(color = Ink.Gold, fontWeight = FontWeight(700)),
                modifier = Modifier.tap { showHidden = !showHidden }.padding(horizontal = 4.dp),
            )
        }
        if (shown.isEmpty()) {
            item {
                Card {
                    if (all.isEmpty()) Empty("🧾", "No spends in ${monthName(from)}", "Bank texts land here by themselves. Cash? Tap the gold + button.")
                    else Empty("🔍", "Nothing matches", "Try another word or category.")
                }
            }
        }
        shown.groupBy { startOfDay(it.ts) }.forEach { (day, list) ->
            item(key = "d$day") {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp)) {
                    Text(dayLabel(day).uppercase(), style = Type.Label, modifier = Modifier.weight(1f))
                    Text(rupees(Stats.total(list.filter { it.counted })), style = Type.Label)
                }
            }
            item(key = "l$day") {
                Card(padding = 12.dp) { list.forEach { t -> ShareSwipe(t, { sharing = it }) { TxnRow(t, byName[t.category], { nav.go(Route.Detail(t.id)) }) } } }
            }
        }
    }
    sharing?.let { t -> ShareSheet(t) { sharing = null } }
}

/** One spend: change category, note, split, hide, see the original text and the map. */
@Composable
fun DetailScreen(id: Long) {
    val db = rememberDb()
    val v = dbVersion(db)
    val nav = LocalNav.current
    val toast = LocalToast.current
    val t = remember(v) { db.txn(id) }
    val cats = remember(v) { db.categories() }
    var note by remember(id) { mutableStateOf(t?.note ?: "") }
    var splitting by remember { mutableStateOf(false) }
    var showSms by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    if (t == null) {
        Page("Spend", onBack = { nav.back() }) { item { Empty("🫥", "Gone", "This spend was deleted.") } }
        return
    }
    val children = remember(v) { if (t.kind == KIND_PARENT) db.children(t.id) else emptyList() }
    val parent = remember(v) { t.parentId?.let { db.txn(it) } }

    Page(null, onBack = { if (note != (t.note ?: "")) db.tag(t.id, t.category, note.trim().ifBlank { null }); nav.back() }) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CatIcon(cats.firstOrNull { it.name == t.category }, size = 64.dp, kind = t.kind)
                Spacer(Modifier.height(12.dp))
                Text(rupees(t.amountPaise), style = Type.Hero.copy(color = if (t.counted) Ink.Text else Ink.Muted))
                Text(t.payee ?: "Unknown payee", style = Type.H2, textAlign = TextAlign.Center)
                Text(dateTimeOf(t.ts), style = Type.Small)
                kindExplain(t)?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = Type.Small.copy(color = Ink.Gold), textAlign = TextAlign.Center)
                }
            }
        }
        if (t.shareId != null || t.fromGroup) item { SharedInfo(t) }
        if (t.kind == KIND_SPEND) {
            item { SectionLabel("Category") }
            item {
                CategoryPicker(cats, t.category) { c ->
                    db.tag(t.id, c, note.trim().ifBlank { null })
                    if (c != null) db.learn(t, c)?.let { toast.show("Learned: $it is always $c.") }
                }
            }
        }
        item { SectionLabel("Note") }
        item { Field(note, { note = it }, "What was it for?", singleLine = false) }

        item { SectionLabel("Whose money") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Personal", !t.company, { if (t.company) db.setCompany(t.id, false) }, leading = "🙂")
                Pill("Company", t.company, { if (!t.company) db.setCompany(t.id, true) }, leading = "💼")
            }
        }

        if (children.isNotEmpty()) {
            item { SectionLabel("Split into") }
            item {
                Card(padding = 12.dp) { children.forEach { c -> TxnRow(c, cats.firstOrNull { it.name == c.category }, { nav.go(Route.Detail(c.id)) }) } }
            }
        }
        if (parent != null) item {
            Card(onClick = { nav.go(Route.Detail(parent.id)) }) {
                Text(if (parent.shareId != null) "Your part of a shared ${rupees(parent.amountPaise)} payment" else "Part of a split ${rupees(parent.amountPaise)} payment", style = Type.Body)
                Text("Open the original →", style = Type.Small.copy(color = Ink.Gold))
            }
        }

        if (t.lat != null && t.lng != null) {
            item { SectionLabel("Where you were") }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))) {
                    SpendMap(listOf(t), Modifier.fillMaxWidth().height(180.dp), zoom = 16.0)
                }
            }
            item { Text("📍 " + (t.place ?: "%.4f, %.4f".format(t.lat, t.lng)), style = Type.Body) }
        }

        item { SectionLabel("Details") }
        item {
            Card {
                if (t.fromGroup) InfoRow("Paid by", t.via ?: "A friend")
                else InfoRow("Paid with", listOfNotNull(t.mode, t.bank, t.account?.let { "••$it" }).joinToString(" · ").ifBlank { "—" })
                if (t.payeeRaw != null && t.payeeRaw != t.payee) InfoRow("Text said", t.payeeRaw)
                if (t.ref != null) InfoRow("Reference", t.ref)
                InfoRow("Source", if (t.manual) "Added by you" else if (t.fromGroup) "Shared in a group" else "Bank text from ${t.sender}")
                if (!t.manual && !t.fromGroup) {
                    Text(if (showSms) "Hide original text" else "Show original text", style = Type.Small.copy(color = Ink.Gold, fontWeight = FontWeight(700)),
                        modifier = Modifier.tap { showSms = !showSms }.padding(top = 8.dp))
                    if (showSms) Text(t.body, style = Type.Small.copy(fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        item { SectionLabel("Actions") }
        item {
            Card(padding = 8.dp) {
                when (t.kind) {
                    KIND_SPEND -> {
                        if (t.shareable) SettingRow("Share to a group", "Friends owe you their part. Or swipe it right.", "👥", { sharing = true })
                        if (t.parentId == null && !t.fromGroup) SettingRow("Split this payment", "Paid for friends too? Count only your part.", "✂️", { splitting = true })
                        if (!t.fromGroup) SettingRow("It's a transfer to myself", "Card bill, own account. Won't count.", "⇄", {
                            db.setKind(t.id, KIND_TRANSFER); toast.show("Marked as transfer") { db.setKind(t.id, KIND_SPEND) }
                        })
                        if (t.manual) {
                            SettingRow("Edit amount, payee, date", null, "✏️", { nav.go(Route.Add(t.id)) })
                            SettingRow("Delete", "Removes this cash spend for good", "🗑", {
                                db.deleteManual(t.id); toast.show("Deleted"); nav.back()
                            })
                        } else {
                            SettingRow("Not a spend", "Refund, wrong text, someone else's. Won't count.", "🚫", {
                                db.setKind(t.id, KIND_IGNORED); toast.show("Hidden") { db.setKind(t.id, KIND_SPEND) }
                            })
                        }
                    }
                    KIND_PARENT -> if (t.shareId != null) SettingRow("Change the split", "Who was there, who owes what", "👥", { sharing = true })
                        else SettingRow("Undo split", "Count the full amount again", "↩️", { db.unsplit(t.id); toast.show("Split removed") })
                    else -> SettingRow("Count this as a spend", "Bring it back into your totals", "↩️", { db.setKind(t.id, KIND_SPEND); toast.show("Restored") })
                }
            }
        }
    }

    if (sharing) ShareSheet(t) { sharing = false }
    if (splitting) SplitSheet(t, cats, { splitting = false }) { parts ->
        db.split(t, parts); splitting = false; toast.show("Split into ${parts.size} parts")
    }
}

private fun kindExplain(t: Txn) = when (t.kind) {
    KIND_TRANSFER -> "Transfer: money moved to yourself (like a card bill). Not counted."
    KIND_IGNORED -> "Hidden: not counted in your totals."
    KIND_PARENT -> if (t.shareId != null) "Shared: only your part counts." else "Split: only the parts below are counted."
    else -> null
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = Type.Small, modifier = Modifier.width(100.dp))
        Text(value, style = Type.Body.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
    }
}

@Composable
fun CategoryPicker(cats: List<Category>, selected: String?, onPick: (String?) -> Unit) {
    // Open scrolled to the picked category, so you can see what it is.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = cats.indexOfFirst { it.name == selected }.coerceAtLeast(0))
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
        items(cats) { c -> Pill(c.name, selected == c.name, { onPick(if (selected == c.name) null else c.name) }, leading = c.emoji) }
    }
}

/** Split a payment: e.g. ₹900 dinner = ₹300 mine (Food) + ₹600 friends (Other). Parts must add up. */
@Composable
private fun SplitSheet(t: Txn, cats: List<Category>, onDismiss: () -> Unit, onSave: (List<Pair<Long, String?>>) -> Unit) {
    val half = t.amountPaise / 2
    val amounts = remember { mutableStateListOf(fmt(half), fmt(t.amountPaise - half)) }
    val catsSel = remember { mutableStateListOf(t.category, "Other".takeIf { o -> cats.any { it.name == o } }) }
    val sum = amounts.sumOf { parsePaise(it) ?: 0L }
    val left = t.amountPaise - sum
    Sheet(onDismiss) {
        Text("Split ${rupees(t.amountPaise)}", style = Type.Title)
        Text("Put your share in one part, the rest in another. Only the parts count.", style = Type.Small)
        amounts.indices.forEach { i ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(amounts[i], { amounts[i] = it }, "Amount", keyboard = KeyboardType.Decimal)
                CategoryPicker(cats, catsSel[i]) { catsSel[i] = it }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when { left == 0L -> "Adds up ✓"; left > 0 -> "${rupees(left)} left to place"; else -> "${rupees(-left)} too much" },
                style = Type.Small.copy(color = if (left == 0L) Ink.Mint else Ink.Coral), modifier = Modifier.weight(1f),
            )
            Text("+ Add part", style = Type.Small.copy(color = Ink.Gold, fontWeight = FontWeight(700)),
                modifier = Modifier.tap { amounts.add(fmt(left.coerceAtLeast(0))); catsSel.add(null) })
        }
        GoldButton("Save split", {
            onSave(amounts.indices.mapNotNull { i -> parsePaise(amounts[i])?.let { it to catsSel[i] } })
        }, enabled = left == 0L && amounts.all { parsePaise(it) != null })
    }
}

private fun fmt(p: Long) = (p / 100.0).let { if (it % 1.0 == 0.0) "%.0f".format(it) else "%.2f".format(it) }
