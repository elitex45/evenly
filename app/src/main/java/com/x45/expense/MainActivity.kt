package com.x45.expense

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.x45.expense.cloud.Cloud
import com.x45.expense.data.Db
import com.x45.expense.data.Places
import com.x45.expense.ui.AccountsScreen
import com.x45.expense.ui.AddScreen
import com.x45.expense.ui.BudgetsScreen
import com.x45.expense.ui.CategoriesScreen
import com.x45.expense.ui.DetailScreen
import com.x45.expense.ui.GoldButton
import com.x45.expense.ui.GroupScreen
import com.x45.expense.ui.GroupsScreen
import com.x45.expense.ui.Glyph
import com.x45.expense.ui.HomeScreen
import com.x45.expense.ui.InsightsScreen
import com.x45.expense.ui.LOCK_AUTH
import com.x45.expense.ui.LocalNav
import com.x45.expense.ui.LocalPerms
import com.x45.expense.ui.LocalToast
import com.x45.expense.ui.MoreScreen
import com.x45.expense.ui.Nav
import com.x45.expense.ui.Onboarding
import com.x45.expense.ui.PeopleScreen
import com.x45.expense.ui.Perms
import com.x45.expense.ui.PermsCtl
import com.x45.expense.ui.ReviewScreen
import com.x45.expense.ui.Route
import com.x45.expense.ui.SpendsScreen
import com.x45.expense.ui.Toaster
import com.x45.expense.ui.UnreadScreen
import com.x45.expense.ui.dbVersion
import com.x45.expense.ui.theme.Ink
import com.x45.expense.ui.theme.SpendsTheme
import com.x45.expense.ui.theme.Type
import com.x45.expense.ui.theme.glow
import com.x45.expense.ui.theme.neu
import com.x45.expense.ui.theme.tap
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import java.io.File
import kotlin.concurrent.thread

class MainActivity : FragmentActivity() {
    /** Set by notification taps: "review", "budgets" or "spend:<id>". */
    val openRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = filesDir
            osmdroidTileCache = File(cacheDir, "tiles")
        }
        Notify.channels(this)
        Notify.scheduleDaily(this)
        openRequest.value = request(intent)
        setContent { SpendsTheme { App(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequest.value = request(intent)
    }

    /** What a notification tap or an invite link (evenly://join/CODE) wants to open. */
    private fun request(i: Intent?): String? =
        i?.getStringExtra(Notify.EXTRA_OPEN) ?: i?.data?.takeIf { it.scheme == "evenly" && it.host == "join" }?.lastPathSegment?.let { "join:$it" }

    fun unlock(onOk: () -> Unit) {
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Evenly").setAllowedAuthenticators(LOCK_AUTH).build()
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(activity: MainActivity) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val v = dbVersion(db)
    val nav = remember { Nav() }
    val toast = remember { Toaster() }

    // v1 installs already imported their texts: skip the welcome screens for them.
    var onboarded by remember {
        val legacy = ctx.getSharedPreferences("app", 0).getBoolean("imported", false)
        if (legacy && db.setting("onboarded") == null) { db.putSetting("onboarded", "1"); db.putSetting("imported", "1") }
        mutableStateOf(db.setting("onboarded") != null)
    }

    fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun readPerms() = Perms(
        sms = granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.READ_SMS),
        location = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        bgLocation = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        notify = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
    )
    var perms by remember { mutableStateOf(readPerms()) }

    // App lock: ask again after 30 s in the background.
    var locked by remember { mutableStateOf(db.setting("lock") == "1") }
    var leftAt by remember { mutableStateOf(0L) }

    // Groups: sync when the app opens, then every 15 s while it's on screen, so friends' shares show up.
    var resumed by remember { mutableStateOf(true) }
    LaunchedEffect(resumed) {
        while (resumed) { delay(15_000); if (Cloud.signedIn(db) && !Cloud.syncing) Cloud.syncAsync(db) }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> {
                    perms = readPerms()
                    resumed = true
                    Cloud.syncAsync(db)
                    if (db.setting("lock") == "1" && leftAt > 0 && SystemClock.elapsedRealtime() - leftAt > 30_000) locked = true
                }
                Lifecycle.Event.ON_STOP -> { leftAt = SystemClock.elapsedRealtime(); resumed = false }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms = readPerms() }
    val pc = PermsCtl(
        perms,
        askSms = { launcher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)) },
        askLocation = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
        askBg = {
            // Android 11+ only offers "Allow all the time" on the app's settings page.
            if (Build.VERSION.SDK_INT >= 30) ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            else if (Build.VERSION.SDK_INT == 29) launcher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        },
        askNotify = { if (Build.VERSION.SDK_INT >= 33) launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
    )

    // Texts that arrived while the app was closed: pick up anything new, then name places.
    var importing by remember { mutableStateOf(false) }
    LaunchedEffect(perms.sms, onboarded) {
        if (onboarded && perms.sms && db.setting("imported") == null) {
            importing = true
            thread { com.x45.expense.sms.Ingest.importInbox(ctx, 90); db.putSetting("imported", "1"); importing = false }
        }
    }
    LaunchedEffect(v) { thread { Places.fillNames(ctx) } }

    // Notification taps.
    val open by activity.openRequest
    LaunchedEffect(open, onboarded) {
        val o = open ?: return@LaunchedEffect
        if (!onboarded) return@LaunchedEffect
        when {
            o == "review" -> { nav.tab(Route.Home); nav.go(Route.Review) }
            o == "budgets" -> { nav.tab(Route.More); nav.go(Route.Budgets) }
            o.startsWith("join:") -> { nav.tab(Route.More); nav.go(Route.Groups(o.removePrefix("join:"))) }
            o.startsWith("spend:") -> o.removePrefix("spend:").toLongOrNull()?.let { nav.go(Route.Detail(it)) }
        }
        activity.openRequest.value = null
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    BackHandler(enabled = nav.stack.size == 1 && nav.top != Route.Home && onboarded) { nav.tab(Route.Home) }

    CompositionLocalProvider(LocalNav provides nav, LocalToast provides toast, LocalPerms provides pc) {
        Box(Modifier.fillMaxSize().background(Ink.Bg).systemBarsPadding().imePadding()) {
            when {
                locked -> LockScreen { activity.unlock { locked = false } }
                !onboarded -> Onboarding { db.putSetting("onboarded", "1"); onboarded = true }
                else -> {
                    val top = nav.top
                    AnimatedContent(
                        targetState = top,
                        transitionSpec = { fadeIn(androidx.compose.animation.core.tween(220)) togetherWith fadeOut(androidx.compose.animation.core.tween(160)) },
                        label = "route",
                    ) { r ->
                        when (r) {
                            Route.Home -> HomeScreen(importing)
                            is Route.Spends -> SpendsScreen(r.category)
                            Route.Insights -> InsightsScreen()
                            Route.More -> MoreScreen()
                            Route.Review -> ReviewScreen()
                            is Route.Add -> AddScreen(r.editId)
                            is Route.Detail -> DetailScreen(r.id)
                            Route.Budgets -> BudgetsScreen()
                            Route.Accounts -> AccountsScreen()
                            Route.People -> PeopleScreen()
                            Route.Categories -> CategoriesScreen()
                            Route.Unread -> UnreadScreen()
                            is Route.Groups -> GroupsScreen(r.joinCode)
                            is Route.Group -> GroupScreen(r.id)
                        }
                    }
                    val showDock = nav.stack.size == 1 && !WindowInsets.isImeVisible
                    AnimatedVisibility(
                        showDock, Modifier.align(Alignment.BottomCenter),
                        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
                    ) { Dock(nav) }
                }
            }
            ToastHost(toast, Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun Dock(nav: Nav) {
    val top = nav.top
    Row(
        Modifier.padding(bottom = 14.dp).neu(radius = 32.dp, elevation = 14.dp, color = Ink.Raised).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DockItem(Icons.Default.Home, "Home", top == Route.Home) { nav.tab(Route.Home) }
        DockItem(Icons.AutoMirrored.Filled.List, "Spends", top is Route.Spends) { nav.tab(Route.Spends()) }
        Box(
            Modifier.padding(horizontal = 8.dp).size(56.dp).glow(Ink.Gold, 28.dp).clip(CircleShape).background(Ink.GoldBrush)
                .tap { nav.go(Route.Add()) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Add, "Add spend", tint = Color(0xFF1A140A), modifier = Modifier.size(28.dp)) }
        DockItem(Glyph.Chart, "Insights", top == Route.Insights) { nav.tab(Route.Insights) }
        DockItem(Glyph.Grid, "More", top == Route.More) { nav.tab(Route.More) }
    }
}

@Composable
private fun DockItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(58.dp).tap(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = if (selected) Ink.Gold else Ink.Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = Type.Label.copy(fontSize = 9.sp, letterSpacing = 0.3.sp, color = if (selected) Ink.Gold else Ink.Faint))
    }
}

@Composable
private fun ToastHost(t: Toaster, modifier: Modifier) {
    val stamp = t.stamp
    LaunchedEffect(stamp) { if (t.msg != null) { delay(if (t.undo != null) 4000 else 2600); if (t.stamp == stamp) t.msg = null } }
    AnimatedVisibility(t.msg != null, modifier.padding(top = 10.dp, start = 16.dp, end = 16.dp),
        enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        Row(
            Modifier.neu(radius = 18.dp, color = Ink.Raised).border(1.dp, Ink.Gold.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t.msg ?: "", style = Type.Body.copy(fontSize = 14.sp))
            t.undo?.let { u ->
                Spacer(Modifier.width(16.dp))
                Text("UNDO", style = Type.Label.copy(color = Ink.Gold, fontWeight = FontWeight(800)), modifier = Modifier.tap { u(); t.msg = null })
            }
        }
    }
}

@Composable
private fun LockScreen(unlock: () -> Unit) {
    LaunchedEffect(Unit) { unlock() }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("🔒", fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text("Evenly is locked", style = Type.Title)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth()) { GoldButton("Unlock", unlock) }
    }
}
