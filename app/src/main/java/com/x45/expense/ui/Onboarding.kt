package com.x45.expense.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x45.expense.data.Stats
import com.x45.expense.sms.Ingest
import com.x45.expense.ui.theme.Ink
import com.x45.expense.ui.theme.Type
import kotlin.concurrent.thread

/** First run: what it does → why each permission → what it found → optional budget. */
@Composable
fun Onboarding(onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    AnimatedContent(step, transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut() }, label = "onb") { s ->
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 32.dp, bottom = 20.dp)) {
            when (s) {
                0 -> Promise { step = 1 }
                1 -> Permissions { step = 2 }
                2 -> Import { step = 3 }
                else -> FirstBudget(onDone)
            }
        }
    }
}

@Composable
private fun Dots(i: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(4) { k -> Text("●", fontSize = 8.sp, color = if (k == i) Ink.Gold else Ink.Stroke) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Promise(next: () -> Unit) {
    Dots(0)
    Spacer(Modifier.weight(1f))
    Text("₹", style = Type.Hero.copy(fontSize = 72.sp, color = Ink.Gold))
    Text("Your spending,\nwritten down\nfor you.", style = Type.Hero.copy(fontSize = 42.sp, lineHeight = 48.sp))
    Spacer(Modifier.height(20.dp))
    Text("You pay. Your bank texts you. Evenly reads that text and notes it down. You never type a thing.", style = Type.Body.copy(color = Ink.Muted))
    Spacer(Modifier.height(28.dp))
    listOf(
        "📩" to "Reads bank texts from ${com.x45.expense.sms.Banks.count}+ banks & cards",
        "📍" to "Remembers where you paid",
        "✍️" to "One minute at night to tag them",
        "🔒" to "Stays on your phone. No account, no server.",
    ).forEach { (e, t) ->
        Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(e, fontSize = 18.sp)
            Spacer(Modifier.width(14.dp))
            Text(t, style = Type.Body)
        }
    }
    Spacer(Modifier.weight(1f))
    GoldButton("Get started", next)
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Permissions(next: () -> Unit) {
    val pc = LocalPerms.current
    val p = pc.perms
    Dots(1)
    Spacer(Modifier.height(28.dp))
    Text("Three things,\nand why", style = Type.Title.copy(fontSize = 34.sp, lineHeight = 40.sp))
    Spacer(Modifier.height(8.dp))
    Text("Each one has a job. Change them any time in More.", style = Type.Small)
    Spacer(Modifier.height(20.dp))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PermCard("📩", "Read bank texts", "So spends appear by themselves. Only bank texts are used. Nothing leaves your phone.", p.sms, "Allow", pc.askSms)
        PermCard("📍", "Location", "So you remember where you paid: \"₹450 in Indiranagar\".", p.location, "Allow", pc.askLocation)
        if (p.location) PermCard("🌙", "Location when closed", "Bank texts come when the app is closed. Pick \"Allow all the time\".", p.bgLocation, "Open settings", pc.askBg)
        PermCard("🔔", "Notifications", "Tag a spend in one tap, and a gentle nudge at night.", p.notify, "Allow", pc.askNotify)
    }
    Spacer(Modifier.height(12.dp))
    GoldButton(if (p.sms) "Continue" else "Continue without texts", next)
}

@Composable
private fun PermCard(emoji: String, title: String, why: String, granted: Boolean, action: String, ask: () -> Unit) {
    Card(onClick = if (granted) null else ask) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.H2.copy(fontSize = 16.sp))
                Text(why, style = Type.Small)
            }
            Spacer(Modifier.width(10.dp))
            Text(if (granted) "✓ On" else action, style = Type.Small.copy(color = if (granted) Ink.Mint else Ink.Gold, fontWeight = FontWeight(800)))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Import(next: () -> Unit) {
    val db = rememberDb()
    val ctx = LocalContext.current
    val sms = LocalPerms.current.perms.sms
    var done by remember { mutableStateOf(!sms) }
    LaunchedEffect(Unit) {
        if (sms && db.setting("imported") == null) thread {
            Ingest.importInbox(ctx, 90)
            db.putSetting("imported", "1")
            done = true
        } else done = true
    }
    val spends = remember(done) { if (done) db.spendsBetween(0, Long.MAX_VALUE) else emptyList() }
    val accounts = remember(done) { if (done) db.accounts().size else 0 }
    val tagged = spends.count { it.category != null }
    Dots(2)
    Spacer(Modifier.weight(1f))
    if (!done) {
        CircularProgressIndicator(color = Ink.Gold)
        Spacer(Modifier.height(20.dp))
        Text("Reading the last\n90 days of texts…", style = Type.Title)
    } else if (spends.isEmpty()) {
        Text("📭", fontSize = 48.sp)
        Text("A fresh start", style = Type.Title)
        Spacer(Modifier.height(8.dp))
        Text(if (sms) "No bank spends in your recent texts. New ones will show up by themselves." else "Without texts, add spends with the gold + button.", style = Type.Body.copy(color = Ink.Muted))
    } else {
        Text("FOUND IN YOUR TEXTS", style = Type.Label)
        Money(Stats.total(spends), Type.Hero.copy(fontSize = 52.sp, color = Ink.Gold))
        Spacer(Modifier.height(10.dp))
        Text("${plural(spends.size, "spend")} across ${plural(accounts, "account")}", style = Type.H2)
        Spacer(Modifier.height(6.dp))
        Text(
            if (tagged > 0) "$tagged already sorted for you (Swiggy → Food, Uber → Travel). The rest take a tap each."
            else "Tag them at your own pace. Most shops get sorted by themselves.",
            style = Type.Body.copy(color = Ink.Muted),
        )
    }
    Spacer(Modifier.weight(1f))
    GoldButton("Continue", next, enabled = done)
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.FirstBudget(onDone: () -> Unit) {
    val db = rememberDb()
    var text by remember { mutableStateOf("") }
    val last = remember { Stats.total(db.spendsBetween(startOfMonth(-1), startOfMonth(0))) }
    Dots(3)
    Spacer(Modifier.height(28.dp))
    Text("A monthly\nlimit?", style = Type.Title.copy(fontSize = 34.sp, lineHeight = 40.sp))
    Spacer(Modifier.height(8.dp))
    Text(
        if (last > 0) "Last month you spent ${rupees(last)}. You'll see what's left each day and get a heads-up at 80%."
        else "You'll see what's left each day and get a heads-up at 80%. Optional.",
        style = Type.Small,
    )
    Spacer(Modifier.weight(1f))
    Text("₹" + text.ifEmpty { "0" }, style = Type.Hero.copy(fontSize = 52.sp, color = if (text.isEmpty()) Ink.Faint else Ink.Gold), modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.dp))
    Numpad({ k -> if (k != '.') text = typeAmount(text, k) })
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("Skip", onDone, Modifier.weight(1f))
        GoldButton("Set budget", { db.monthlyBudget = parsePaise(text); onDone() }, Modifier.weight(2f), enabled = parsePaise(text) != null)
    }
}
