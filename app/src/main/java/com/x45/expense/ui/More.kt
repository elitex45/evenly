package com.x45.expense.ui

import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x45.expense.Notify
import com.x45.expense.data.Backup
import com.x45.expense.data.Category
import com.x45.expense.data.Contact
import com.x45.expense.data.OWN_ACCOUNT
import com.x45.expense.data.Stats
import com.x45.expense.sms.Banks
import com.x45.expense.sms.Ingest
import com.x45.expense.ui.theme.Ink
import com.x45.expense.ui.theme.Type
import com.x45.expense.ui.theme.tap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

@Composable
private fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Ink.Faint)

@Composable
fun MoreScreen() {
    val db = rememberDb()
    val v = dbVersion(db)
    val nav = LocalNav.current
    val toast = LocalToast.current
    val ctx = LocalContext.current
    val pc = LocalPerms.current
    val unread = remember(v) { db.unread().size }
    val contacts = remember(v) { db.contacts().size }
    val accounts = remember(v) { db.accounts() }
    val remind = remember(v) { Notify.reminderMinutes(ctx) }
    val locked = remember(v) { db.setting("lock") == "1" }
    var busy by remember { mutableStateOf(false) }
    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { Backup.exportCsv(ctx, uri) }.onSuccess { toast.show("Exported ${plural(it, "row")}") }.onFailure { toast.show("Export failed: ${it.message}") }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { Backup.save(ctx, uri) }.onSuccess { toast.show("Backup saved") }.onFailure { toast.show("Backup failed: ${it.message}") }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { Backup.restore(ctx, uri) }.onSuccess { toast.show("Backup restored") }.onFailure { toast.show(it.message ?: "Restore failed") }
    }

    Page("More", eyebrow = "Make it yours") {
        item { SectionLabel("Money") }
        item {
            Card(padding = 8.dp) {
                SettingRow("Budgets", db.monthlyBudget?.let { "${rupees(it)} a month" } ?: "Set a limit, get a heads-up at 80%", "🎯", { nav.go(Route.Budgets) }) { Chevron() }
                SettingRow("Accounts & cards", "${plural(accounts.size, "account")} found in your texts", "💳", { nav.go(Route.Accounts) }) { Chevron() }
                SettingRow("People & shops", if (contacts > 0) "${plural(contacts, "rule")}. Pay them, it's tagged for you." else "\"Lakshmi = Flowers\". Auto-tag who you pay.", "👥", { nav.go(Route.People) }) { Chevron() }
                SettingRow("Categories", "Add your own, pick emoji & colour", "🏷", { nav.go(Route.Categories) }) { Chevron() }
                if (unread > 0) SettingRow("Texts I couldn't read", "${plural(unread, "bank text")} to check", "📩", { nav.go(Route.Unread) }) { Chevron() }
            }
        }
        item { SectionLabel("Habits") }
        item {
            Card(padding = 8.dp) {
                SettingRow("Nightly review reminder", "%d:%02d %s".format(((remind / 60) + 11) % 12 + 1, remind % 60, if (remind >= 720) "PM" else "AM"), "🔔", {
                    TimePickerDialog(ctx, { _, h, m ->
                        db.putSetting("remind_at", (h * 60 + m).toString()); Notify.scheduleDaily(ctx); toast.show("Reminder moved")
                    }, remind / 60, remind % 60, false).show()
                }) { Chevron() }
                SettingRow("App lock", "Fingerprint or phone PIN to open", "🔒", null) {
                    Toggle(locked) { on ->
                        if (on && BiometricManager.from(ctx).canAuthenticate(LOCK_AUTH) != BiometricManager.BIOMETRIC_SUCCESS) {
                            toast.show("Set a screen lock on your phone first")
                        } else db.putSetting("lock", if (on) "1" else null)
                    }
                }
            }
        }
        item { SectionLabel("Permissions") }
        item {
            Card(padding = 8.dp) {
                PermRow("Read bank texts", "Finds spends by itself", pc.perms.sms, pc.askSms)
                PermRow("Location", "Remembers where you paid", pc.perms.location, pc.askLocation)
                PermRow("Location while closed", "Texts arrive when the app is closed", pc.perms.bgLocation, pc.askBg)
                PermRow("Notifications", "Tag in one tap, nightly nudge", pc.perms.notify, pc.askNotify)
            }
        }
        item { SectionLabel("Your data") }
        item {
            Card(padding = 8.dp) {
                SettingRow("Scan old texts again", if (busy) "Scanning…" else "Picks up anything missed (last 90 days)", "🔄", {
                    if (!busy && pc.perms.sms) {
                        busy = true
                        thread { val n = Ingest.importInbox(ctx, 90); busy = false; toast.show(if (n > 0) "Found ${plural(n, "new spend")}" else "Nothing new") }
                    }
                })
                SettingRow("Export to spreadsheet", "CSV file for Excel / Sheets", "📤", { csv.launch("autolog-$stamp.csv") })
                SettingRow("Back up", "Save everything to one file", "💾", { save.launch("autolog-backup-$stamp.db") })
                SettingRow("Restore a backup", "Replaces what's here now", "♻️", { restore.launch(arrayOf("*/*")) })
            }
        }
        item {
            Text(
                "Your texts and spends never leave this phone. No account, no server, no ads. Knows ${Banks.count} banks & cards.",
                style = Type.Small.copy(color = Ink.Faint), modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}

const val LOCK_AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

@Composable
fun PermRow(title: String, why: String, granted: Boolean, ask: () -> Unit) {
    SettingRow(title, why, if (granted) "✅" else "⚪️", if (granted) null else ask) {
        if (granted) Text("On", style = Type.Small.copy(color = Ink.Mint, fontWeight = FontWeight(700)))
        else Text("Allow", style = Type.Small.copy(color = Ink.Gold, fontWeight = FontWeight(800)))
    }
}

// ---------- budgets ----------

@Composable
fun BudgetsScreen() {
    val db = rememberDb()
    val v = dbVersion(db)
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val cats = remember(v) { db.categories() }
    val month = remember(v) { db.spendsBetween(startOfMonth(0), startOfMonth(1)) }
    val last = remember(v) { db.spendsBetween(startOfMonth(-1), startOfMonth(0)) }
    val overall = remember(v) { db.monthlyBudget }
    var editing by remember { mutableStateOf<String?>(null) } // "" = overall

    Page("Budgets", onBack = { nav.back() }, eyebrow = monthOf(startOfMonth(0))) {
        item {
            Card(onClick = { editing = "" }, padding = 22.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MONTHLY BUDGET", style = Type.Label)
                        Text(overall?.let { rupees(it) } ?: "Not set", style = Type.Title.copy(color = if (overall == null) Ink.Faint else Ink.Text))
                        Text(
                            overall?.let { "${rupees(Stats.total(month))} spent · tap to change" } ?: "Tap to set. Last month you spent ${rupees(Stats.total(last))}.",
                            style = Type.Small,
                        )
                    }
                    if (overall != null && overall > 0) Ring(Stats.total(month).toFloat() / overall, Modifier.size(70.dp)) {
                        Text("${Stats.total(month) * 100 / overall}%", style = Type.Small.copy(color = Ink.Text, fontWeight = FontWeight(800)))
                    }
                }
            }
        }
        item { SectionLabel("Per category") }
        item {
            Card {
                cats.forEach { c ->
                    val spent = month.filter { it.category == c.name }.sumOf { it.amountPaise }
                    Column(Modifier.fillMaxWidth().tap { editing = c.name }.padding(vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CatIcon(c, size = 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(c.name, style = Type.Body, modifier = Modifier.weight(1f))
                            Text(
                                if (c.budgetPaise != null) "${rupees(spent)} / ${rupees(c.budgetPaise)}" else if (spent > 0) rupees(spent) else "Set",
                                style = Type.Small.copy(color = if (c.budgetPaise == null && spent == 0L) Ink.Gold else Ink.Text),
                            )
                        }
                        if (c.budgetPaise != null && c.budgetPaise > 0) {
                            Spacer(Modifier.height(8.dp))
                            val f = spent.toFloat() / c.budgetPaise
                            Bar(f, if (f >= 1f) Ink.Coral else if (f >= 0.8f) Ink.Gold else Color(c.color), height = 5.dp)
                        }
                    }
                }
            }
        }
        item { Text("You'll get one heads-up at 80% and one at 100%, each month.", style = Type.Small.copy(color = Ink.Faint), modifier = Modifier.padding(6.dp)) }
    }

    editing?.let { key ->
        val cur = if (key == "") overall else cats.firstOrNull { it.name == key }?.budgetPaise
        var text by remember(key) { mutableStateOf(cur?.let { (it / 100).toString() } ?: "") }
        Sheet({ editing = null }) {
            Text(if (key == "") "Monthly budget" else "$key budget", style = Type.Title)
            Text("How much do you want to spend at most each month?", style = Type.Small)
            Text("₹" + text.ifEmpty { "0" }, style = Type.Hero.copy(color = Ink.Gold))
            if (key == "") {
                val base = Stats.total(last).coerceAtLeast(Stats.total(month))
                if (base > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(90, 100, 110).forEach { pct ->
                        val s = ((base * pct / 100) / 100_000 + 1) * 1000
                        Pill(short(s * 100), false, { text = s.toString() })
                    }
                }
            }
            Numpad({ k -> if (k != '.') text = typeAmount(text, k) })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (cur != null) GhostButton("Remove", {
                    if (key == "") db.monthlyBudget = null else db.setBudget(key, null); editing = null
                }, Modifier.weight(1f), color = Ink.Coral)
                GoldButton("Save", {
                    val p = parsePaise(text)
                    if (key == "") db.monthlyBudget = p else db.setBudget(key, p)
                    editing = null
                    thread { Notify.budgetCheck(ctx) }
                }, Modifier.weight(1f), enabled = parsePaise(text) != null)
            }
        }
    }
}

// ---------- accounts ----------

@Composable
fun AccountsScreen() {
    val db = rememberDb()
    val v = dbVersion(db)
    val nav = LocalNav.current
    val accounts = remember(v) { db.accounts() }
    Page("Accounts & cards", onBack = { nav.back() }) {
        item { Text("Found in your bank texts. Tap Personal to make a work card Company: its spends get their own totals. Turn one off and its spends stop counting.", style = Type.Small) }
        if (accounts.isEmpty()) item { Card { Empty("💳", "None yet", "Accounts show up after the first bank text.") } }
        else item {
            Card(padding = 8.dp) {
                accounts.forEach { a ->
                    SettingRow(
                        listOfNotNull(a.bank ?: "Unknown bank", a.account?.let { "••$it" }).joinToString(" "),
                        "${plural(a.count, "payment")} · ${rupees(a.totalPaise)} counted",
                        "🏦",
                    ) {
                        Pill(if (a.company) "Company" else "Personal", a.company, { db.setAccountCompany(a.key, !a.company) }, leading = if (a.company) "💼" else "🙂")
                        Spacer(Modifier.width(8.dp))
                        Toggle(a.tracked) { db.setTracked(a.key, it) }
                    }
                }
            }
        }
    }
}

// ---------- people & shops ----------

@Composable
fun PeopleScreen() {
    val db = rememberDb()
    val v = dbVersion(db)
    val nav = LocalNav.current
    val toast = LocalToast.current
    val contacts = remember(v) { db.contacts() }
    val cats = remember(v) { db.categories() }
    var editing by remember { mutableStateOf<Contact?>(null) }
    var adding by remember { mutableStateOf(false) }

    Page("People & shops", onBack = { nav.back() }, trailing = { GhostButton("+ Add", { adding = true }, color = Ink.Gold) }) {
        item { Text("Pay someone often? Add them once. Every payment to them gets their name and category by itself.", style = Type.Small) }
        if (contacts.isEmpty()) item {
            Card { Empty("👥", "No one yet", "Example: Lakshmi sells you flowers. Add \"Lakshmi\" → Flowers.", "Add person or shop") { adding = true } }
        } else item {
            Card(padding = 8.dp) {
                contacts.forEach { c ->
                    val cat = cats.firstOrNull { it.name == c.category }
                    Row(Modifier.fillMaxWidth().tap { editing = c }.padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CatIcon(cat, size = 38.dp, kind = if (c.category == OWN_ACCOUNT) com.x45.expense.data.KIND_TRANSFER else null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = Type.Body.copy(fontWeight = FontWeight(700)))
                            Text("matches: " + c.keywords.joinToString(", "), style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (c.category == OWN_ACCOUNT) "My account" else c.category ?: "No category", style = Type.Small.copy(color = Ink.Gold))
                    }
                }
            }
        }
    }

    if (adding || editing != null) ContactSheet(editing, cats, onDismiss = { adding = false; editing = null }, onDelete = { id ->
        db.deleteContact(id); adding = false; editing = null; toast.show("Removed")
    }) { id, name, words, cat ->
        db.saveContact(id, name, words, cat)
        db.applyContactsToUntagged()
        adding = false; editing = null
        toast.show("Saved $name")
    }
}

@Composable
private fun ContactSheet(
    c: Contact?, cats: List<Category>, onDismiss: () -> Unit, onDelete: (Long) -> Unit,
    onSave: (Long?, String, List<String>, String?) -> Unit,
) {
    var name by remember { mutableStateOf(c?.name ?: "") }
    var words by remember { mutableStateOf(c?.keywords?.joinToString(", ") ?: "") }
    var cat by remember { mutableStateOf(c?.category) }
    Sheet(onDismiss) {
        Text(if (c == null) "Add person or shop" else "Edit ${c.name}", style = Type.Title)
        Field(name, { name = it }, "Name (e.g. Lakshmi)")
        Field(words, { words = it }, "Match words, comma separated")
        Text("Any payment whose name contains one of these words is theirs. Leave empty to use the name.", style = Type.Small)
        Text("ALWAYS TAG AS", style = Type.Label)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(cats) { k -> Pill(k.name, cat == k.name, { cat = if (cat == k.name) null else k.name }, leading = k.emoji) }
        }
        SettingRow("This is me", "My other account or card. Payments to it are transfers, not spends.", right = {
            Toggle(cat == OWN_ACCOUNT) { cat = if (it) OWN_ACCOUNT else null }
        })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (c != null) GhostButton("Delete", { onDelete(c.id) }, Modifier.weight(1f), color = Ink.Coral)
            GoldButton("Save", {
                onSave(c?.id, name.trim(), words.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(name.trim()) }, cat)
            }, Modifier.weight(1f), enabled = name.isNotBlank())
        }
    }
}

// ---------- categories ----------

private val PALETTE = listOf(0xFFFF9F6B, 0xFF9BD67A, 0xFF6BB8FF, 0xFFFFC857, 0xFFE58BFF, 0xFF7FE0D4, 0xFFB49BFF, 0xFFFF7B9C, 0xFFC9A27A, 0xFF7FE0B2)

@Composable
fun CategoriesScreen() {
    val db = rememberDb()
    val v = dbVersion(db)
    val nav = LocalNav.current
    val toast = LocalToast.current
    val cats = remember(v) { db.categories() }
    var adding by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Category?>(null) }

    Page("Categories", onBack = { nav.back() }, trailing = { GhostButton("+ Add", { adding = true }, color = Ink.Gold) }) {
        item {
            Card(padding = 8.dp) {
                cats.forEach { c ->
                    SettingRow(c.name, c.budgetPaise?.let { "Budget ${rupees(it)}" }, c.emoji, { confirm = c }) {
                        Text("Remove", style = Type.Small.copy(color = Ink.Faint))
                    }
                }
            }
        }
    }
    confirm?.let { c ->
        Sheet({ confirm = null }) {
            Row(verticalAlignment = Alignment.CenterVertically) { CatIcon(c, 48.dp); Spacer(Modifier.width(12.dp)); Text(c.name, style = Type.Title) }
            Text("Remove this category? Its spends become untagged, so you can re-tag them. Nothing is deleted.", style = Type.Small)
            GhostButton("Remove ${c.name}", { db.deleteCategory(c.name); confirm = null; toast.show("Removed ${c.name}") }, Modifier.fillMaxWidth(), color = Ink.Coral)
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var emoji by remember { mutableStateOf("") }
        var color by remember { mutableStateOf(PALETTE[0]) }
        Sheet({ adding = false }) {
            Text("New category", style = Type.Title)
            Field(name, { name = it }, "Name (e.g. Pets)")
            Field(emoji, { emoji = it.take(2) }, "Emoji (e.g. 🐶)")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PALETTE.take(8).forEach { p ->
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color(p))
                            .border(2.dp, if (p == color) Ink.Text else Color.Transparent, CircleShape).tap { color = p },
                    )
                }
            }
            GoldButton("Add", { db.addCategory(name, emoji, color); adding = false; toast.show("Added $name") }, enabled = name.isNotBlank())
        }
    }
}

// ---------- unread texts ----------

@Composable
fun UnreadScreen() {
    val db = rememberDb()
    val v = dbVersion(db)
    val nav = LocalNav.current
    val list = remember(v) { db.unread() }
    Page("Texts I couldn't read", onBack = { nav.back() }) {
        item { Text("These look like bank texts, but I couldn't find the amount or payee. If one was a spend, add it by hand.", style = Type.Small) }
        if (list.isEmpty()) item { Card { Empty("📭", "All clear", "Nothing here.") } }
        list.forEach { u ->
            item(key = u.id) {
                Card {
                    Text(u.sender + " · " + dayLabel(u.ts) + ", " + timeOf(u.ts), style = Type.Label)
                    Spacer(Modifier.height(6.dp))
                    Text(u.body, style = Type.Body.copy(fontSize = 13.sp))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("Add as spend", { db.dismissUnread(u.id); nav.go(Route.Add()) }, Modifier.weight(1f), color = Ink.Gold)
                        GhostButton("Not a spend", { db.dismissUnread(u.id) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
