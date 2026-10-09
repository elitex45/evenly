package com.x45.expense.ui

import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x45.expense.cloud.Cloud
import com.x45.expense.cloud.Debt
import com.x45.expense.cloud.GoogleSignIn
import com.x45.expense.cloud.Group
import com.x45.expense.cloud.Member
import com.x45.expense.cloud.Payback
import com.x45.expense.cloud.Split
import com.x45.expense.data.Db
import com.x45.expense.data.Txn
import com.x45.expense.ui.theme.Ink
import com.x45.expense.ui.theme.Type
import com.x45.expense.ui.theme.tap
import org.json.JSONObject
import kotlin.concurrent.thread

// V2: groups with friends. Bank texts stay on the phone; only spends you share go to the group.

/** My one-line status in a group: "You owe Ravi ₹300", "Ravi owes you ₹600", "All settled". */
fun statusLine(g: Group, me: String?): String {
    if (me == null) return ""
    val owe = Split.iOwe(g.debts, me)
    val owed = Split.owedToMe(g.debts, me)
    return when {
        owe.isEmpty() && owed.isEmpty() -> "All settled"
        owe.size == 1 && owed.isEmpty() -> "You owe ${g.nameOf(owe[0].to, me)} ${rupees(owe[0].paise)}"
        owed.size == 1 && owe.isEmpty() -> "${g.nameOf(owed[0].from, me)} owes you ${rupees(owed[0].paise)}"
        else -> listOfNotNull(
            owe.sumOf { it.paise }.takeIf { it > 0 }?.let { "You owe ${rupees(it)}" },
            owed.sumOf { it.paise }.takeIf { it > 0 }?.let { "you're owed ${rupees(it)}" },
        ).joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
}

/** Everything I owe, per person per group (for the "paying back?" guess). */
fun paybacks(db: Db): List<Payback> {
    val me = Cloud.me(db)?.id ?: return emptyList()
    return Cloud.groups(db).flatMap { g -> Split.iOwe(g.debts, me).mapNotNull { d -> g.member(d.to)?.let { Payback(g.id, it, d.paise) } } }
}

@Composable
fun GroupsScreen(joinCode: String?) {
    val db = rememberDb()
    val v = dbVersion(db)
    val nav = LocalNav.current
    val toast = LocalToast.current
    val signedIn = remember(v) { Cloud.signedIn(db) }
    val me = remember(v) { Cloud.me(db) }
    val groups = remember(v) { Cloud.groups(db) }
    val waiting = remember(v) { db.outbox().size }
    val error = remember(v) { db.setting("cloud_error") }
    val pending = remember(v) { Cloud.pending(db) }
    var creating by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(joinCode) }
    var editServer by remember { mutableStateOf(false) }

    LaunchedEffect(signedIn) { if (signedIn) Cloud.syncAsync(db) }

    Page("Groups", onBack = { nav.back() }, eyebrow = me?.let { "Signed in as ${it.name}" } ?: "Split with friends") {
        if (!signedIn) {
            item { SignInCard() }
        } else {
            if (waiting > 0) item { Banner("⏳", "${plural(waiting, "change")} waiting for internet", "They go up by themselves when you're back online.") }
            if (error != null) item {
                Banner("⚠️", error, "Tap to dismiss", Ink.Coral) { db.putSetting("cloud_error", null) }
            }
            pending.forEach { p -> item { Banner("⏳", "Waiting for ${p.admin.substringBefore(' ')} to let you in", "${p.emoji} ${p.name} · you asked to join") } }
            val owe = groups.sumOf { g -> Split.iOwe(g.debts, me!!.id).sumOf { it.paise } }
            val owed = groups.sumOf { g -> Split.owedToMe(g.debts, me!!.id).sumOf { it.paise } }
            if (groups.isNotEmpty()) item {
                Card(padding = 22.dp) {
                    Row {
                        Stat("YOU OWE", rupees(owe), Modifier.weight(1f), if (owe > 0) Ink.Coral else Ink.Text)
                        Stat("YOU'RE OWED", rupees(owed), Modifier.weight(1f), if (owed > 0) Ink.Mint else Ink.Text)
                    }
                }
            }
            if (groups.isEmpty()) item {
                Card { Empty("👥", "No groups yet", "Make one for your flat, a trip or your family. Then invite them with a code.") }
            } else item {
                Card(padding = 8.dp) {
                    groups.forEach { g ->
                        SettingRow(g.name, "${plural(g.members.size, "person").replace("persons", "people")} · ${statusLine(g, me?.id)}", g.emoji, { nav.go(Route.Group(g.id)) }) {
                            if (g.requests.isNotEmpty()) Badge("${g.requests.size} new")
                            Text("›", style = Type.H2.copy(color = Ink.Faint))
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("+ New group", { creating = true }, Modifier.weight(1f), color = Ink.Gold)
                    GhostButton("Join with a code", { joining = "" }, Modifier.weight(1f))
                }
            }
            item {
                Text("Swipe a spend right to share it. The group sees the amount, name, date and your note. Never your bank, card or texts.",
                    style = Type.Small, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        item { SectionLabel("Server") }
        item {
            Card(padding = 8.dp) {
                SettingRow("Server address", Cloud.server(db), "🌐", { editServer = true })
                if (signedIn) SettingRow("Sign out", "Your spends stay on this phone", "🚪", {
                    thread { Cloud.signOut(db) }; toast.show("Signed out")
                })
            }
        }
    }

    if (creating) NewGroupSheet { creating = false }
    joining?.let { code -> JoinSheet(code) { joining = null } }
    if (editServer) ServerSheet { editServer = false }
}

@Composable
private fun Badge(text: String) {
    Text(text, style = Type.Small.copy(color = Ink.Bg, fontWeight = FontWeight(700)),
        modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(Ink.Gold).padding(horizontal = 10.dp, vertical = 3.dp))
}

@Composable
private fun Banner(emoji: String, title: String, sub: String, color: Color = Ink.Gold, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .let { m -> onClick?.let { m.tap(onClick = it) } ?: m }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = Type.Body.copy(fontWeight = FontWeight(700)))
            Text(sub, style = Type.Small)
        }
    }
}

@Composable
private fun SignInCard() {
    val db = rememberDb()
    val toast = LocalToast.current
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf<JSONObject?>(null) }
    var cfgError by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val server = remember(dbVersion(db)) { Cloud.server(db) }
    LaunchedEffect(server) {
        cfgError = null
        thread { runCatching { Cloud.config(db) }.onSuccess { cfg = it }.onFailure { cfgError = Cloud.why(it) } }
    }

    Card(padding = 22.dp) {
        Text("🤝", fontSize = 34.sp)
        Spacer(Modifier.height(8.dp))
        Text("Split spends with friends", style = Type.H2)
        Spacer(Modifier.height(4.dp))
        Text("Make a group, invite friends, and share a spend with one swipe. Everyone sees who owes whom.", style = Type.Small)
        Spacer(Modifier.height(10.dp))
        Text("🔒 Your bank texts never leave this phone. Only spends you share go to the group.", style = Type.Small.copy(color = Ink.Mint))
        Spacer(Modifier.height(18.dp))
        val clientId = cfg?.optString("googleClientId")?.takeIf { cfg?.isNull("googleClientId") == false && it.isNotBlank() }
        when {
            cfgError != null -> Text("Can't reach the server at $server. Check the address below.", style = Type.Small.copy(color = Ink.Coral))
            cfg == null -> Text("Connecting…", style = Type.Small)
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (clientId != null) GoldButton(if (busy) "Signing in…" else "Continue with Google", {
                    val act = ctx as? androidx.activity.ComponentActivity ?: return@GoldButton
                    busy = true
                    GoogleSignIn.start(act, clientId) { token, err ->
                        if (token == null) { busy = false; toast.show(err ?: "Sign-in cancelled"); return@start }
                        thread { runCatching { Cloud.googleLogin(db, token) }.onFailure { toast.show(Cloud.why(it)) }; busy = false }
                    }
                }, enabled = !busy)
                if (cfg?.optBoolean("devLogin") == true) {
                    Text("TEST LOGIN (this server only)", style = Type.Label)
                    Field(name, { name = it }, "Your name")
                    Field(email, { email = it.trim() }, "Email", keyboard = KeyboardType.Email)
                    GoldButton(if (busy) "Signing in…" else "Sign in", {
                        busy = true
                        thread { runCatching { Cloud.devLogin(db, name.trim(), email) }.onFailure { toast.show(Cloud.why(it)) }; busy = false }
                    }, enabled = !busy && name.isNotBlank() && email.contains('@'))
                }
                if (clientId == null && cfg?.optBoolean("devLogin") != true) Text("This server has no sign-in set up yet.", style = Type.Small.copy(color = Ink.Coral))
            }
        }
    }
}

private val GROUP_EMOJI = listOf("🏠", "🏖", "👨‍👩‍👧", "🍕", "🚗", "🎉", "💼", "👥")

@Composable
private fun NewGroupSheet(onDone: () -> Unit) {
    val db = rememberDb()
    val toast = LocalToast.current
    val nav = LocalNav.current
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf(GROUP_EMOJI[0]) }
    var busy by remember { mutableStateOf(false) }
    Sheet(onDone) {
        Text("New group", style = Type.Title)
        Field(name, { name = it }, "Flat, Goa trip, Family…")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(GROUP_EMOJI) { e -> Pill(e, emoji == e, { emoji = e }) }
        }
        GoldButton(if (busy) "Making…" else "Make group", {
            busy = true
            thread {
                runCatching { Cloud.createGroup(db, name.trim(), emoji) }
                    .onSuccess { g -> toast.show("${g.name} is ready. Invite people with code ${g.code}."); onDone(); nav.go(Route.Group(g.id)) }
                    .onFailure { toast.show(Cloud.why(it)); busy = false }
            }
        }, enabled = name.isNotBlank() && !busy)
    }
}

@Composable
private fun JoinSheet(initial: String, onDone: () -> Unit) {
    val db = rememberDb()
    val toast = LocalToast.current
    val nav = LocalNav.current
    var code by remember { mutableStateOf(initial.uppercase()) }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var wrong by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val signedIn = Cloud.signedIn(db)
    LaunchedEffect(code) {
        preview = null; wrong = null
        if (signedIn && code.length == 6) thread { runCatching { Cloud.invite(db, code) }.onSuccess { preview = it }.onFailure { wrong = Cloud.why(it) } }
    }
    Sheet(onDone) {
        Text("Join a group", style = Type.Title)
        if (!signedIn) {
            Text("Sign in first, then join with code $code.", style = Type.Small)
            GoldButton("OK", onDone)
            return@Sheet
        }
        Field(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) }, "6-letter code", style = Type.Title.copy(letterSpacing = 4.sp))
        preview?.let { p ->
            Text("${p.optString("emoji")} ${p.optString("name")}", style = Type.H2)
            Text("${plural(p.optInt("count"), "person").replace("persons", "people")} · run by ${p.optString("admin")}", style = Type.Small)
            Text("${p.optString("admin").substringBefore(' ')} has to let you in.", style = Type.Small.copy(color = Ink.Faint))
        }
        wrong?.let { Text(it, style = Type.Small.copy(color = Ink.Coral)) }
        GoldButton(if (busy) "Asking…" else "Ask to join", {
            busy = true
            thread {
                runCatching { Cloud.join(db, code) }
                    .onSuccess { g ->
                        if (g == null) { toast.show("Asked. You'll be in once the admin says yes."); onDone() }
                        else { toast.show("You're in ${g.name}"); onDone(); nav.go(Route.Group(g.id)) }
                    }
                    .onFailure { wrong = Cloud.why(it); busy = false }
            }
        }, enabled = code.length == 6 && !busy)
    }
}

@Composable
private fun ServerSheet(onDone: () -> Unit) {
    val db = rememberDb()
    var url by remember { mutableStateOf(Cloud.server(db)) }
    Sheet(onDone) {
        Text("Server address", style = Type.Title)
        Text("Where your groups live. Ask whoever runs it.", style = Type.Small)
        Field(url, { url = it.trim() }, Cloud.DEFAULT_SERVER, keyboard = KeyboardType.Uri)
        GoldButton("Save", {
            db.putSetting("cloud_server", url.trimEnd('/').takeIf { it != Cloud.DEFAULT_SERVER && it.isNotBlank() })
            onDone()
        }, enabled = url.startsWith("http"))
    }
}

// ---------- one group ----------

@Composable
fun GroupScreen(id: String) {
    val db = rememberDb()
    val v = dbVersion(db)
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val g = remember(v) { Cloud.group(db, id) }
    val me = remember(v) { Cloud.me(db)?.id }
    val detail = remember(v) { Cloud.groupDetail(db, id) }
    var settling by remember { mutableStateOf<Debt?>(null) }
    var removing by remember { mutableStateOf<Member?>(null) }
    var newCode by remember { mutableStateOf(false) }

    // Fresh data when the screen opens and whenever something changes.
    LaunchedEffect(v) { thread { runCatching { Cloud.loadGroup(db, id) } } }

    if (g == null) {
        Page("Group", onBack = { nav.back() }) { item { Empty("🫥", "Not here", "You're not in this group any more.") } }
        return
    }
    Page(g.name, onBack = { nav.back() }, eyebrow = "${g.emoji}  ${plural(g.members.size, "member")}") {
        // Hero: where I stand.
        item {
            val owe = Split.iOwe(g.debts, me ?: "").sumOf { it.paise }
            val owed = Split.owedToMe(g.debts, me ?: "").sumOf { it.paise }
            Card(padding = 22.dp) {
                when {
                    owe > 0 -> { Text("YOU OWE", style = Type.Label); Money(owe, Type.Hero.copy(color = Ink.Coral)) }
                    owed > 0 -> { Text("YOU'RE OWED", style = Type.Label); Money(owed, Type.Hero.copy(color = Ink.Mint)) }
                    else -> { Text("BALANCE", style = Type.Label); Text("All settled ✓", style = Type.Title.copy(color = Ink.Mint)) }
                }
            }
        }
        if (g.isAdmin(me) && g.requests.isNotEmpty()) {
            item { SectionLabel("Wants to join") }
            item {
                Card(padding = 12.dp) {
                    g.requests.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = Type.Body.copy(fontWeight = FontWeight(700)))
                                Text("Asked with the code", style = Type.Small)
                            }
                            GhostButton("Deny", { thread { runCatching { Cloud.deny(db, g.id, r.id) }.onSuccess { toast.show("Said no to ${r.first}") }.onFailure { toast.show(Cloud.why(it)) } } })
                            Spacer(Modifier.width(8.dp))
                            GhostButton("Approve", { thread { runCatching { Cloud.approve(db, g.id, r.id) }.onSuccess { toast.show("${r.first} is in") }.onFailure { toast.show(Cloud.why(it)) } } }, color = Ink.Gold)
                        }
                    }
                }
            }
        }
        if (g.debts.isNotEmpty()) {
            item { SectionLabel("Who owes whom") }
            item {
                Card(padding = 12.dp) {
                    g.debts.forEach { d ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            val line = when {
                                d.from == me -> "You owe ${g.nameOf(d.to, me)}"
                                d.to == me -> "${g.nameOf(d.from, me)} owes you"
                                else -> "${g.nameOf(d.from, me)} owes ${g.nameOf(d.to, me)}"
                            }
                            Column(Modifier.weight(1f)) {
                                Text(line, style = Type.Body.copy(fontWeight = FontWeight(700)))
                                Text(rupees(d.paise), style = Type.Money.copy(color = if (d.from == me) Ink.Coral else if (d.to == me) Ink.Mint else Ink.Text))
                            }
                            if (d.from == me) GhostButton("Paid back", { settling = d }, color = Ink.Gold)
                            else if (d.to == me) GhostButton("Got it", { settling = d })
                        }
                    }
                }
            }
        }

        item { SectionLabel("Invite") }
        item {
            Card {
                Text("Code", style = Type.Small)
                Text(g.code, style = Type.Title.copy(letterSpacing = 6.sp, color = Ink.Gold))
                Spacer(Modifier.height(10.dp))
                GhostButton("Send invite", {
                    val text = "Join my group \"${g.name}\" on Evenly to split spends.\nCode: ${g.code}\n${Cloud.inviteLink(db, g)}"
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite"))
                }, Modifier.fillMaxWidth(), color = Ink.Gold)
                if (g.isAdmin(me)) {
                    Spacer(Modifier.height(8.dp))
                    GhostButton("New code", { newCode = true }, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                Text("The code only asks to join. ${if (g.isAdmin(me)) "You" else g.nameOf(g.adminId, me)} let${if (g.isAdmin(me)) "" else "s"} people in.", style = Type.Small)
            }
        }

        item { SectionLabel("People", trailing = if (g.isAdmin(me)) "Tap to remove" else null) }
        item {
            Card(padding = 8.dp) {
                g.members.forEach { m ->
                    val net = g.netOf(m.id)
                    val money = when {
                        net < 0 -> "${if (m.id == me) "owe" else "owes"} ${rupees(-net)}"
                        net > 0 -> "${if (m.id == me) "are" else "is"} owed ${rupees(net)}"
                        else -> "settled"
                    }
                    val role = if (m.id == g.adminId) "Admin" else "Member"
                    SettingRow(if (m.id == me) "You" else m.name, "$role · $money", if (m.id == g.adminId) "⭐" else "👤",
                        if (g.isAdmin(me) && m.id != me) ({ removing = m }) else null)
                }
            }
        }

        val acts = detail?.optJSONArray("activity")
        item { SectionLabel("Activity") }
        if (acts == null || acts.length() == 0) item {
            Card { Empty("🧾", "Nothing shared yet", "Swipe a spend right on Home or Spends to share it here.") }
        } else item {
            Card(padding = 12.dp) {
                for (i in 0 until acts.length()) ActivityRow(acts.getJSONObject(i), g, me, detail.optJSONObject("names"))
            }
        }
    }

    settling?.let { d ->
        SettleSheet(g, d, me ?: "") { done ->
            settling = null
            if (done) toast.show(if (d.from == me) "Marked as paid back" else "Marked as received")
        }
    }
    removing?.let { m -> RemoveSheet(g, m) { removing = null } }
    if (newCode) NewCodeSheet(g) { newCode = false }
}

/** Admin only. Blocked until the person is settled, so nobody's money leaves with them. */
@Composable
private fun RemoveSheet(g: Group, m: Member, onDone: () -> Unit) {
    val db = rememberDb()
    val toast = LocalToast.current
    var busy by remember { mutableStateOf(false) }
    val net = g.netOf(m.id)
    Sheet(onDone) {
        Text("Remove ${m.first}?", style = Type.Title)
        if (net != 0L) {
            Text(if (net < 0) "${m.first} still owes ${rupees(-net)}. Settle up first." else "${m.first} is still owed ${rupees(net)}. Settle up first.",
                style = Type.Body.copy(color = Ink.Coral))
            Text("You can remove someone once they're all settled.", style = Type.Small)
            GoldButton("OK", onDone)
        } else {
            Text("${m.first} leaves ${g.name}. Their past spends stay on their phone. They'd need to ask again to come back.", style = Type.Small)
            GoldButton(if (busy) "Removing…" else "Remove ${m.first}", {
                busy = true
                thread {
                    runCatching { Cloud.removeMember(db, g.id, m.id) }
                        .onSuccess { toast.show("${m.first} is out of ${g.name}"); onDone() }
                        .onFailure { toast.show(Cloud.why(it)); busy = false }
                }
            }, enabled = !busy)
        }
    }
}

@Composable
private fun NewCodeSheet(g: Group, onDone: () -> Unit) {
    val db = rememberDb()
    val toast = LocalToast.current
    var busy by remember { mutableStateOf(false) }
    Sheet(onDone) {
        Text("Make a new code?", style = Type.Title)
        Text("The old code ${g.code} stops working. People already in stay in.", style = Type.Small)
        GoldButton(if (busy) "Making…" else "Yes, new code", {
            busy = true
            thread {
                runCatching { Cloud.newCode(db, g.id) }
                    .onSuccess { toast.show("New code: $it"); onDone() }
                    .onFailure { toast.show(Cloud.why(it)); busy = false }
            }
        }, enabled = !busy)
    }
}

@Composable
private fun ActivityRow(a: JSONObject, g: Group, me: String?, names: JSONObject?) {
    // People removed since still have a name here.
    fun who(id: String) = if (g.member(id) == null && id != me) names?.optString(id)?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: "Someone" else g.nameOf(id, me)
    val share = a.optString("type") == "share"
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Ink.Raised), contentAlignment = Alignment.Center) {
            Text(if (share) "🧾" else "🤝", fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (share) {
                Text(a.optString("title"), style = Type.Body.copy(fontWeight = FontWeight(700)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val parts = a.getJSONArray("parts")
                val mine = (0 until parts.length()).map { parts.getJSONObject(it) }.firstOrNull { it.getString("userId") == me }?.getLong("paise")
                val payer = who(a.getString("payerId"))
                Text(listOfNotNull(
                    "$payer paid", dayLabel(a.getLong("at")),
                    mine?.let { "your part ${rupees(it)}" } ?: if (a.getString("payerId") != me) "not in it" else null,
                ).joinToString(" · "), style = Type.Small)
                a.optString("note").takeIf { !a.isNull("note") && it.isNotBlank() }?.let { Text("“$it”", style = Type.Small.copy(color = Ink.Faint)) }
            } else {
                Text("${who(a.getString("fromId"))} paid ${who(a.getString("toId")).let { if (it == "You") "you" else it }} back", style = Type.Body.copy(fontWeight = FontWeight(700)))
                Text(dayLabel(a.getLong("at")), style = Type.Small)
            }
        }
        Text(rupees(if (share) a.getLong("amountPaise") else a.getLong("paise")), style = Type.Money)
    }
}

@Composable
private fun SettleSheet(g: Group, d: Debt, me: String, onDone: (Boolean) -> Unit) {
    val db = rememberDb()
    var amount by remember { mutableStateOf((d.paise / 100.0).let { if (it % 1.0 == 0.0) "%.0f".format(it) else "%.2f".format(it) }) }
    val iPay = d.from == me
    val other = g.nameOf(if (iPay) d.to else d.from, me)
    Sheet({ onDone(false) }) {
        Text(if (iPay) "Paid $other back?" else "$other paid you?", style = Type.Title)
        Text(if (iPay) "Marks it in the group. Your bank text for it won't count as a new spend." else "Marks it in the group for both of you.", style = Type.Small)
        Field(amount, { amount = it }, "Amount", keyboard = KeyboardType.Decimal, style = Type.Title)
        val p = parsePaise(amount)
        GoldButton(if (iPay) "Yes, I paid ${p?.let { rupees(it) } ?: ""}" else "Yes, I got ${p?.let { rupees(it) } ?: ""}", {
            Cloud.settle(db, g.id, d.from, d.to, p!!)
            onDone(true)
        }, enabled = p != null && p <= d.paise)
    }
}

// ---------- sharing a spend ----------

/** Swipe a spend right to share it with a group. Only for spends you paid yourself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSwipe(t: Txn, onShare: (Txn) -> Unit, content: @Composable () -> Unit) {
    if (!t.shareable) { content(); return }
    val latest by rememberUpdatedState(t)
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.StartToEnd) onShare(latest)
        false // always spring back
    })
    SwipeToDismissBox(
        state, enableDismissFromEndToStart = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(Ink.Gold.copy(alpha = 0.18f)).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text("👥  Share to group", style = Type.Body.copy(color = Ink.Gold, fontWeight = FontWeight(800))) }
        },
    ) { Box(Modifier.background(Ink.Surface)) { content() } }
}

@Composable
fun ShareSheet(t: Txn, onDone: (shared: Boolean) -> Unit) {
    val db = rememberDb()
    val nav = LocalNav.current
    val toast = LocalToast.current
    val me = remember { Cloud.me(db) }
    val groups = remember { Cloud.groups(db) }
    if (me == null || groups.isEmpty()) {
        Sheet({ onDone(false) }) {
            Text("Share with friends", style = Type.Title)
            Text(if (me == null) "Sign in and make a group first. Your bank texts stay on this phone." else "Make a group first, then invite your friends.", style = Type.Small)
            GoldButton("Set up groups", { onDone(false); nav.go(Route.Groups()) })
        }
        return
    }
    val prev = remember { t.shareJson?.let { runCatching { JSONObject(it) }.getOrNull() } }
    var gid by remember { mutableStateOf(prev?.optString("groupId")?.takeIf { id -> groups.any { it.id == id } } ?: groups.first().id) }
    val g = groups.first { it.id == gid }
    val prevParts = remember { prev?.optJSONArray("parts")?.let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { p -> p.getString("userId") to p.getLong("paise") } } } }
    val ticked = remember(gid) { mutableStateMapOf<String, Boolean>().apply { g.members.forEach { m -> put(m.id, prevParts?.containsKey(m.id) ?: true) } } }
    var exact by remember(gid) { mutableStateOf(prevParts != null && prevParts.values.distinct().size > 1) }
    val typed = remember(gid) { mutableStateMapOf<String, String>().apply { prevParts?.forEach { (k, p) -> put(k, (p / 100.0).let { if (it % 1.0 == 0.0) "%.0f".format(it) else "%.2f".format(it) }) } } }

    val who = g.members.filter { ticked[it.id] == true }
    val parts: List<Pair<String, Long>> = if (exact) who.mapNotNull { m -> parsePaise(typed[m.id] ?: "")?.let { m.id to it } }
        else who.zip(Split.equal(t.amountPaise, who.size)).map { (m, p) -> m.id to p }
    val sum = parts.sumOf { it.second }
    val onlyMe = parts.size == 1 && parts[0].first == me.id
    val ok = parts.isNotEmpty() && parts.size == who.size && sum == t.amountPaise && !onlyMe
    val myPart = parts.firstOrNull { it.first == me.id }?.second ?: 0L

    Sheet({ onDone(false) }) {
        Text("Share ${rupees(t.amountPaise)}", style = Type.Title)
        Text("${t.payee ?: "Spend"} · ${dayLabel(t.ts)}", style = Type.Small)
        if (groups.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(groups) { x -> Pill(x.name, x.id == gid, { gid = x.id }, leading = x.emoji) }
        } else Text("${g.emoji}  ${g.name}", style = Type.H2)

        Text("WHO WAS THERE?", style = Type.Label)
        Column {
            g.members.forEach { m ->
                val on = ticked[m.id] == true
                Row(Modifier.fillMaxWidth().tap { ticked[m.id] = !on }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(if (on) Ink.Gold else Color.Transparent).border(1.5.dp, if (on) Ink.Gold else Ink.Stroke, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (on) Text("✓", color = Color(0xFF1A140A), fontSize = 13.sp, fontWeight = FontWeight(900)) }
                    Spacer(Modifier.width(12.dp))
                    Text(if (m.id == me.id) "You" else m.name, style = Type.Body, modifier = Modifier.weight(1f))
                    if (on && exact) {
                        Box(Modifier.width(110.dp)) { Field(typed[m.id] ?: "", { typed[m.id] = it }, "₹0", keyboard = KeyboardType.Decimal) }
                    } else if (on) {
                        Text(parts.firstOrNull { it.first == m.id }?.second?.let { rupees(it) } ?: "", style = Type.Money)
                    }
                }
            }
        }
        Text("MORE WAYS", style = Type.Label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Equal", !exact && ticked[me.id] == true, { exact = false; ticked[me.id] = true })
            Pill("Exact amounts", exact, {
                // Start from the split on screen, so you only change the ones that differ.
                if (!exact) parts.forEach { (u, paise) -> typed[u] = (paise / 100.0).let { if (it % 1.0 == 0.0) "%.0f".format(it) else "%.2f".format(it) } }
                exact = true
            })
            Pill("They owe me all", !exact && ticked[me.id] == false, { exact = false; ticked[me.id] = false })
        }
        Text(
            when {
                who.isEmpty() -> "Tick who was there."
                onlyMe -> "Only you? Tick at least one friend."
                exact && sum != t.amountPaise -> if (sum < t.amountPaise) "${rupees(t.amountPaise - sum)} left to place" else "${rupees(sum - t.amountPaise)} too much"
                myPart == 0L -> "They owe you all ${rupees(t.amountPaise)}. Nothing counts as your spend."
                else -> "Your part ${rupees(myPart)} counts as your spend. Friends owe you ${rupees(t.amountPaise - myPart)}."
            },
            style = Type.Small.copy(color = if (ok) Ink.Mint else Ink.Coral),
        )
        Text("🔒 ${g.name} sees the amount, name, date and note. Not your bank, card or text.", style = Type.Small)
        GoldButton("Share to ${g.name}", {
            Cloud.share(db, t, g, parts)
            toast.show(if (myPart > 0) "Shared. Your part: ${rupees(myPart)}" else "Shared. They owe you ${rupees(t.amountPaise)}")
            onDone(true)
        }, enabled = ok)
        if (t.shareId != null) GhostButton("Stop sharing", {
            Cloud.unshare(db, t); toast.show("Not shared any more"); onDone(true)
        }, Modifier.fillMaxWidth(), color = Ink.Coral)
    }
}

// ---------- "paying back?" ----------

/** Home card: a bank spend that looks like paying a friend back. One tap settles it in the group. */
@Composable
fun PaybackCard() {
    val db = rememberDb()
    val v = dbVersion(db)
    val toast = LocalToast.current
    val guess = remember(v) {
        val owe = paybacks(db)
        if (owe.isEmpty()) null else {
            val skip = db.setting("payback_skip")?.split(',')?.toSet() ?: emptySet()
            db.recentOwnSpends(System.currentTimeMillis() - 7 * 86_400_000L).filter { it.id.toString() !in skip }
                .firstNotNullOfOrNull { t -> (Split.paybackFor(t.amountPaise, t.payee, owe) ?: Split.paybackFor(t.amountPaise, t.payeeRaw, owe))?.let { t to it } }
        }
    } ?: return
    val (t, p) = guess
    val g = Cloud.group(db, p.groupId) ?: return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Ink.Surface).border(1.dp, Ink.Mint.copy(alpha = 0.5f), RoundedCornerShape(22.dp)).padding(18.dp),
    ) {
        Text("🤝  Paying ${p.to.first} back?", style = Type.H2)
        Spacer(Modifier.height(4.dp))
        Text("You sent ${rupees(t.amountPaise)} to ${t.payee ?: t.payeeRaw}. You owe ${p.to.first} exactly that in ${g.name}.", style = Type.Small)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Yes, settle up", {
                Cloud.settle(db, g.id, Cloud.me(db)!!.id, p.to.id, p.paise, t)
                toast.show("Settled with ${p.to.first}. That payment won't count as a spend.")
            }, Modifier.weight(1f), color = Ink.Mint)
            GhostButton("No", {
                db.putSetting("payback_skip", ((db.setting("payback_skip")?.split(',') ?: emptyList()) + t.id.toString()).joinToString(","))
            }, Modifier.weight(0.5f))
        }
    }
}

/** For a spend's detail page: how it was shared, or whose spend it is. */
@Composable
fun SharedInfo(t: Txn) {
    val db = rememberDb()
    val v = dbVersion(db)
    if (t.fromGroup) {
        Card {
            Text("👥  Shared by ${t.via?.substringBefore(" ·") ?: "a friend"}", style = Type.Body.copy(fontWeight = FontWeight(700)))
            Text(t.body, style = Type.Small)
            Text("Only your part counts as your spend.", style = Type.Small.copy(color = Ink.Mint))
        }
        return
    }
    val j = t.shareJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
    val g = remember(v) { Cloud.group(db, j.optString("groupId")) }
    val me = remember(v) { Cloud.me(db)?.id }
    val waiting = remember(v) { db.outbox().any { it.path == "/shares/${t.shareId}" } }
    val parts = j.getJSONArray("parts").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    Card {
        Text("👥  Shared to ${g?.name ?: j.optString("groupName")}", style = Type.Body.copy(fontWeight = FontWeight(700)))
        Spacer(Modifier.height(6.dp))
        parts.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(g?.nameOf(p.getString("userId"), me) ?: "Someone", style = Type.Body, modifier = Modifier.weight(1f))
                Text(rupees(p.getLong("paise")), style = Type.Body.copy(fontWeight = FontWeight(700)))
            }
        }
        if (waiting) Text("⏳ Waiting for internet to send", style = Type.Small.copy(color = Ink.Gold), modifier = Modifier.padding(top = 6.dp))
        else Text("✓ In the group", style = Type.Small.copy(color = Ink.Mint), modifier = Modifier.padding(top = 6.dp))
    }
}
