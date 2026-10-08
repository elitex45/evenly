package com.x45.expense.ui

import com.x45.expense.data.BOOK_COMPANY
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x45.expense.Notify
import com.x45.expense.data.KIND_SPEND
import com.x45.expense.data.MANUAL
import com.x45.expense.data.Places
import com.x45.expense.data.Txn
import com.x45.expense.ui.theme.Ink
import com.x45.expense.ui.theme.Type
import java.util.Calendar
import kotlin.concurrent.thread

/** Add a cash spend (or edit one). Amount first, because that's the one thing you always know. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddScreen(editId: Long?) {
    val db = rememberDb()
    val nav = LocalNav.current
    val toast = LocalToast.current
    val perms = LocalPerms.current.perms
    val ctx = LocalContext.current
    val old = remember { editId?.let { db.txn(it) } }
    val cats = remember { db.categories() }

    var amount by remember { mutableStateOf(old?.let { (it.amountPaise / 100.0).let { r -> if (r % 1.0 == 0.0) "%.0f".format(r) else "%.2f".format(r) } } ?: "") }
    var payee by remember { mutableStateOf(old?.payee ?: "") }
    var category by remember { mutableStateOf(old?.category) }
    var mode by remember { mutableStateOf(old?.mode ?: "Cash") }
    var note by remember { mutableStateOf(old?.note ?: "") }
    var ts by remember { mutableLongStateOf(old?.ts ?: System.currentTimeMillis()) }
    var saveLocation by remember { mutableStateOf(old == null && perms.location) }
    // New spends start as whatever you're looking at; company only if you're on the Company view.
    var company by remember { mutableStateOf(old?.company ?: (db.hasCompany() && db.book == BOOK_COMPANY)) }
    val suggestions = remember(payee) { if (payee.isBlank()) db.payeeSuggestions("", 5) else db.payeeSuggestions(payee.trim(), 5).filter { it.first != payee } }
    val paise = parsePaise(amount)

    fun pickDate() {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        DatePickerDialog(ctx, { _, y, m, d ->
            TimePickerDialog(ctx, { _, h, min ->
                ts = Calendar.getInstance().apply { set(y, m, d, h, min, 0) }.timeInMillis
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    fun save() {
        val p = paise ?: return
        val name = payee.trim().ifBlank { null }
        if (old != null) {
            db.update(old.copy(amountPaise = p, payee = name, category = category, note = note.trim().ifBlank { null }, ts = ts, mode = mode,
                companyOverride = if (company != old.company) company else old.companyOverride))
            toast.show("Saved")
        } else {
            val id = db.insertTxn(
                Txn(0, p, name, name, null, null, null, mode, ts, null, null, null, category, note.trim().ifBlank { null }, MANUAL, "", KIND_SPEND, companyOverride = company)
            )
            if (id != null && saveLocation) thread {
                Places.current(ctx, 6)?.let { (lat, lng) -> db.setLocation(id, lat, lng, Places.name(ctx, lat, lng)) }
            }
            toast.show("Added ${rupees(p)}" + (category?.let { " to $it" } ?: ""))
        }
        thread { Notify.budgetCheck(ctx) }
        nav.back()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp)) {
        Header(if (old == null) "Add spend" else "Edit spend", onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "₹" + amount.ifEmpty { "0" },
                style = Type.Hero.copy(fontSize = 56.sp, color = if (paise != null) Ink.Gold else Ink.Faint),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
            Field(payee, { payee = it }, "Paid to (e.g. Auto, Lakshmi)")
            if (suggestions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(suggestions) { (name, cat) ->
                        Pill(name, false, { payee = name; if (cat != null) category = cat })
                    }
                }
            }
            Text("CATEGORY", style = Type.Label)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                items(cats) { c -> Pill(c.name, category == c.name, { category = if (category == c.name) null else c.name }, leading = c.emoji) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Cash", "UPI", "Card").forEach { m -> Pill(m, mode == m, { mode = m }) }
                Spacer(Modifier.weight(1f))
                Pill(dayLabel(ts) + ", " + timeOf(ts), false, { pickDate() }, leading = "🗓")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Personal", !company, { company = false }, leading = "🙂")
                Pill("Company", company, { company = true }, leading = "💼")
            }
            Field(note, { note = it }, "Note (optional)")
            if (old == null && perms.location) {
                SettingRow("Save where I am", "So you remember where this was", right = { Toggle(saveLocation) { saveLocation = it } })
            }
        }
        AnimatedVisibility(!WindowInsets.isImeVisible) {
            Column {
                Spacer(Modifier.height(10.dp))
                Numpad({ amount = typeAmount(amount, it) })
                Spacer(Modifier.height(12.dp))
            }
        }
        GoldButton(if (old == null) "Save spend" else "Save changes", { save() }, enabled = paise != null)
    }
}
