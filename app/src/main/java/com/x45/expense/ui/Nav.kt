package com.x45.expense.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.x45.expense.data.Db

sealed interface Route {
    data object Home : Route
    data class Spends(val category: String? = null) : Route
    data object Insights : Route
    data object More : Route
    data object Review : Route
    data class Add(val editId: Long? = null) : Route
    data class Detail(val id: Long) : Route
    data object Budgets : Route
    data object Accounts : Route
    data object People : Route
    data object Categories : Route
    data object Unread : Route
    data class Groups(val joinCode: String? = null) : Route
    data class Group(val id: String) : Route
}

val TABS = listOf(Route.Home, Route.Spends(), Route.Insights, Route.More)

/** Simple back stack. Tabs reset the stack; everything else pushes. */
class Nav {
    val stack = mutableStateListOf<Route>(Route.Home)
    val top: Route get() = stack.last()
    fun go(r: Route) { stack.add(r) }
    fun tab(r: Route) { stack.clear(); stack.add(r) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
}

/** Small message at the top of the screen, optionally with an Undo button. */
class Toaster {
    var msg by mutableStateOf<String?>(null)
    var undo: (() -> Unit)? by mutableStateOf(null)
    var stamp by mutableStateOf(0L)
    fun show(m: String, undo: (() -> Unit)? = null) { msg = m; this.undo = undo; stamp = System.nanoTime() }
}

data class Perms(val sms: Boolean, val location: Boolean, val bgLocation: Boolean, val notify: Boolean)

/** Current permissions plus functions that ask for each one. */
class PermsCtl(val perms: Perms, val askSms: () -> Unit, val askLocation: () -> Unit, val askBg: () -> Unit, val askNotify: () -> Unit)

val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }
val LocalToast = staticCompositionLocalOf<Toaster> { error("no toaster") }
val LocalPerms = staticCompositionLocalOf<PermsCtl> { error("no perms") }

@Composable
fun rememberDb(): Db {
    val ctx = LocalContext.current
    return remember { Db.get(ctx) }
}

/** Re-reads whenever the database changes. */
@Composable
fun dbVersion(db: Db): Int {
    val v by db.version.collectAsState()
    return v
}
