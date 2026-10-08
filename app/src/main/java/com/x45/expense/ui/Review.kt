package com.x45.expense.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x45.expense.Notify
import com.x45.expense.data.Category
import com.x45.expense.data.Db
import com.x45.expense.data.KIND_IGNORED
import com.x45.expense.data.KIND_SPEND
import com.x45.expense.data.Txn
import com.x45.expense.ui.theme.Ink
import com.x45.expense.ui.theme.Type
import com.x45.expense.ui.theme.tap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Nightly review: one spend at a time, one tap to categorise. */
@Composable
fun ReviewScreen() {
    val db = rememberDb()
    val nav = LocalNav.current
    val toast = LocalToast.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Snapshot the queue so the list doesn't shift while you work through it.
    val queue = remember { db.untagged().map { it.id } }
    var i by remember { mutableIntStateOf(0) }
    val cats = remember { db.categories() }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 12.dp)) {
        Header("Review", onBack = { nav.back() }, eyebrow = if (i < queue.size) "${i + 1} of ${queue.size}" else "Done")
        Spacer(Modifier.height(8.dp))
        Bar(if (queue.isEmpty()) 1f else i.toFloat() / queue.size, Ink.Gold, height = 4.dp)
        Spacer(Modifier.height(16.dp))
        AnimatedContent(
            targetState = i,
            transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut()) },
            label = "review",
        ) { idx ->
            if (idx >= queue.size) {
                Done(remember { streak(db) }) { nav.back() }
            } else {
                val t = remember(idx) { db.txn(queue[idx]) }
                if (t == null) {
                    Text("This spend was removed.", style = Type.Small)
                } else {
                    ReviewCard(t, cats, db,
                        onTag = { cat, keep ->
                            db.tag(t.id, cat, t.note)
                            if (keep) {
                                db.saveContact(null, t.payee ?: t.payeeRaw ?: cat, listOfNotNull(t.payeeRaw ?: t.payee), cat)
                                db.applyContactsToUntagged()
                                toast.show("Got it. ${t.payee} will always be $cat.")
                            } else {
                                db.learn(t, cat)?.let { toast.show("Learned: $it is always $cat.") }
                            }
                            Notify.budgetCheck(ctx)
                            i++
                        },
                        onSkip = { i++ },
                        onHide = {
                            db.setKind(t.id, KIND_IGNORED)
                            toast.show("Hidden. It won't count.") { db.setKind(t.id, KIND_SPEND) }
                            i++
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(t: Txn, cats: List<Category>, db: Db, onTag: (String, Boolean) -> Unit, onSkip: () -> Unit, onHide: () -> Unit) {
    var keep by remember(t.id) { mutableStateOf(false) }
    val suggested = remember(t.id) { db.categoriesFor(t.payeeRaw).toSet() }
    val ordered = cats.sortedByDescending { it.name in suggested }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Card(padding = 22.dp) {
            Text(rupees(t.amountPaise), style = Type.Hero)
            Text(t.payee ?: "Unknown payee", style = Type.H2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(dayLabel(t.ts) + ", " + timeOf(t.ts), t.place, t.bank, t.mode.takeIf { it != "Other" }).joinToString(" · "),
                style = Type.Small,
            )
            if (t.body.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(t.body, style = Type.Small.copy(color = Ink.Faint, fontSize = 11.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("WHAT WAS IT?", style = Type.Label)
        Spacer(Modifier.height(12.dp))
        ordered.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    Column(
                        Modifier.weight(1f).tap { onTag(c.name, keep) }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            CatIcon(c, size = 54.dp)
                            if (c.name in suggested) Text("★", color = Ink.Gold, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopEnd))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(c.name, style = Type.Small.copy(color = Ink.Text, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        SettingRow("Always use this for ${t.payee ?: "this payee"}", "Next time it's tagged for you", right = { Toggle(keep) { keep = it } })
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Skip", onSkip, Modifier.weight(1f))
            GhostButton("Not a spend", onHide, Modifier.weight(1f), color = Ink.Coral)
        }
    }
}

@Composable
private fun Done(streak: Int, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🎉", fontSize = 64.sp)
        Spacer(Modifier.height(12.dp))
        Text("All caught up", style = Type.Title)
        Spacer(Modifier.height(6.dp))
        Text("Every spend has a category.", style = Type.Small)
        if (streak > 1) {
            Spacer(Modifier.height(18.dp))
            Text("🔥 $streak-day streak", style = Type.H2.copy(color = Ink.Gold, fontWeight = FontWeight(800)))
        }
        Spacer(Modifier.height(32.dp))
        Row(Modifier.width(220.dp)) { GoldButton("Done", onClose) }
    }
}

/** Days in a row you've finished the review. */
private fun streak(db: Db): Int {
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    val today = fmt.format(Date())
    val yesterday = fmt.format(Date(System.currentTimeMillis() - 86_400_000L))
    val last = db.setting("streak_last")
    val n = db.setting("streak_n")?.toIntOrNull() ?: 0
    val next = when (last) { today -> n; yesterday -> n + 1; else -> 1 }
    db.putSetting("streak_last", today)
    db.putSetting("streak_n", next.toString())
    return next
}
