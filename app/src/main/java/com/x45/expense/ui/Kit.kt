package com.x45.expense.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import com.x45.expense.data.BOOK_ALL
import com.x45.expense.data.BOOK_COMPANY
import com.x45.expense.data.BOOK_PERSONAL
import com.x45.expense.data.Db
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x45.expense.data.Category
import com.x45.expense.data.KIND_IGNORED
import com.x45.expense.data.KIND_PARENT
import com.x45.expense.data.KIND_TRANSFER
import com.x45.expense.data.Txn
import com.x45.expense.ui.theme.Ink
import com.x45.expense.ui.theme.Type
import com.x45.expense.ui.theme.glow
import com.x45.expense.ui.theme.neu
import com.x45.expense.ui.theme.tap
import com.x45.expense.ui.theme.well
import kotlin.math.roundToLong

// ---------- layout ----------

/** A scrolling screen with a big serif title. Bottom padding leaves room for the floating dock. */
@Composable
fun Page(
    title: String?,
    onBack: (() -> Unit)? = null,
    eyebrow: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    bottomPad: Dp = 128.dp,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header(title, onBack, eyebrow, trailing) }
        content()
    }
}

@Composable
fun Header(title: String?, onBack: (() -> Unit)? = null, eyebrow: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                if (eyebrow != null) Text(eyebrow.uppercase(), style = Type.Label)
                if (title != null) Text(title, style = Type.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit, tint: Color = Ink.Text, size: Dp = 44.dp) {
    Box(Modifier.size(size).neu(radius = size / 2, elevation = 6.dp).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().neu().let { if (onClick != null) it.tap(onClick = onClick) else it }.padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Type.Label, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Type.Label.copy(color = Ink.Gold), modifier = Modifier.let { m -> onTrailing?.let { m.tap(onClick = it) } ?: m })
    }
}

// ---------- buttons & inputs ----------

@Composable
fun GoldButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.fillMaxWidth().height(56.dp)
            .let { if (enabled) it.glow(Ink.Gold, radius = 18.dp) else it }
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) Ink.GoldBrush else SolidColor(Ink.Raised))
            .tap(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.Body.copy(fontWeight = FontWeight(800), color = if (enabled) Color(0xFF1A140A) else Ink.Faint, fontSize = 16.sp))
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ink.Text) {
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, Ink.Stroke, RoundedCornerShape(16.dp)).tap(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.Body.copy(fontWeight = FontWeight(700), color = color)) }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: String? = null) {
    Row(
        modifier.clip(CircleShape)
            .background(if (selected) Ink.Gold.copy(alpha = 0.16f) else Ink.Surface)
            .border(1.dp, if (selected) Ink.Gold else Ink.Stroke, CircleShape)
            .tap(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) Text(leading + " ", fontSize = 14.sp)
        Text(text, style = Type.Small.copy(color = if (selected) Ink.Gold else Ink.Text, fontWeight = FontWeight(700)))
    }
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, style: TextStyle = Type.Body,
) {
    BasicTextField(
        value, onChange, textStyle = style, cursorBrush = SolidColor(Ink.Gold), singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth().well().padding(horizontal = 16.dp, vertical = 15.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = Ink.Faint))
                inner()
            }
        },
    )
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked, onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color(0xFF1A140A), checkedTrackColor = Ink.Gold, checkedBorderColor = Ink.Gold,
            uncheckedThumbColor = Ink.Muted, uncheckedTrackColor = Ink.Bg, uncheckedBorderColor = Ink.Stroke,
        ),
    )
}

/** Row with a title, a "why" line and something on the right (toggle, value, chevron). */
@Composable
fun SettingRow(title: String, sub: String? = null, emoji: String? = null, onClick: (() -> Unit)? = null, right: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().let { m -> onClick?.let { m.tap(onClick = it) } ?: m }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (emoji != null) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Ink.Raised), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 18.sp) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Body.copy(fontWeight = FontWeight(700)))
            if (sub != null) Text(sub, style = Type.Small)
        }
        right()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.Raised,
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 6.dp).size(40.dp, 4.dp).clip(CircleShape).background(Ink.Stroke)) },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

// ---------- money ----------

/** Rupee amount that counts up to its value. */
@Composable
fun Money(paise: Long, style: TextStyle, modifier: Modifier = Modifier, countUp: Boolean = true) {
    val anim = remember { Animatable(if (countUp) 0f else paise.toFloat()) }
    LaunchedEffect(paise) { anim.animateTo(paise.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    val shown = if (anim.isRunning) (anim.value / 100).roundToLong() * 100 else paise
    Text(rupees(shown), style = style, modifier = modifier, maxLines = 1)
}

// ---------- categories & rows ----------

fun Category?.tint(): Color = this?.let { Color(it.color) } ?: Ink.Gold

@Composable
fun CatIcon(c: Category?, size: Dp = 42.dp, kind: String? = null) {
    val muted = kind == KIND_TRANSFER || kind == KIND_IGNORED || kind == KIND_PARENT
    val color = if (muted) Ink.Muted else c.tint()
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.15f)).border(1.dp, color.copy(alpha = 0.25f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                kind == KIND_TRANSFER -> "⇄"
                kind == KIND_IGNORED -> "—"
                kind == KIND_PARENT -> "✂"
                else -> c?.emoji ?: "?"
            },
            fontSize = (size.value * 0.42f).sp, color = color, textAlign = TextAlign.Center,
        )
    }
}

fun kindLabel(kind: String) = when (kind) {
    KIND_TRANSFER -> "TRANSFER"
    KIND_IGNORED -> "HIDDEN"
    KIND_PARENT -> "SPLIT"
    else -> null
}

@Composable
fun TxnRow(t: Txn, cat: Category?, onClick: () -> Unit, showDay: Boolean = false) {
    Row(Modifier.fillMaxWidth().tap(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        CatIcon(cat, kind = t.kind)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.payee ?: "Unknown payee", style = Type.Body.copy(fontWeight = FontWeight(700)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                if (showDay) dayLabel(t.ts) else timeOf(t.ts),
                t.category ?: if (t.counted) "Untagged" else null,
                t.place?.substringBefore(",") ?: if (t.manual) t.mode.takeIf { it != "Other" } ?: "Cash" else t.bank,
            ).joinToString(" · ")
            Text(sub, style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                rupees(t.amountPaise),
                style = Type.Money.copy(
                    color = if (t.counted) Ink.Text else Ink.Faint,
                    textDecoration = if (t.counted) null else TextDecoration.LineThrough,
                ),
            )
            when {
                !t.counted -> Text(kindLabel(t.kind) ?: "", style = Type.Label.copy(fontSize = 9.sp))
                t.category == null -> Text("TAP TO TAG", style = Type.Label.copy(fontSize = 9.sp, color = Ink.Gold))
            }
        }
    }
}

@Composable
fun Empty(emoji: String, title: String, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 40.sp)
        Spacer(Modifier.height(10.dp))
        Text(title, style = Type.H2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(text, style = Type.Small, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            GhostButton(action, onAction, color = Ink.Gold)
        }
    }
}

// ---------- charts ----------

/** Ring chart. Each part is (color, value). */
@Composable
fun Donut(parts: List<Pair<Color, Long>>, modifier: Modifier = Modifier, thickness: Dp = 20.dp, center: @Composable () -> Unit = {}) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(parts) { p.snapTo(0f); p.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val total = parts.sumOf { it.second }.coerceAtLeast(1).toFloat()
            val stroke = thickness.toPx()
            val d = size.minDimension - stroke
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(Ink.Raised, 0f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
            var start = -90f
            parts.forEach { (c, v) ->
                val sweep = 360f * v / total * p.value
                if (sweep > 0.5f) drawArc(c, start, (sweep - 1.5f).coerceAtLeast(0.5f), false, tl, Size(d, d), style = Stroke(stroke))
                start += sweep
            }
        }
        center()
    }
}

/** Progress ring (budget used). Over 100% turns coral. */
@Composable
fun Ring(fraction: Float, modifier: Modifier = Modifier, thickness: Dp = 10.dp, center: @Composable () -> Unit = {}) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(fraction) { p.animateTo(fraction.coerceIn(0f, 1f), tween(1100, easing = FastOutSlowInEasing)) }
    val color = when { fraction >= 1f -> Ink.Coral; fraction >= 0.8f -> Ink.Gold; else -> Ink.Mint }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = thickness.toPx()
            val d = size.minDimension - stroke
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(Ink.Raised, 0f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
            drawArc(color, -90f, 360f * p.value, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        center()
    }
}

@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(fraction) { p.animateTo(fraction.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing)) }
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(Ink.Raised)) {
        Box(Modifier.fillMaxWidth(p.value).height(height).clip(CircleShape).background(color))
    }
}

/** Column chart. Labels shown under bars where non-empty. */
@Composable
fun Bars(values: List<Long>, labels: List<String>, modifier: Modifier = Modifier, highlight: Int? = null, height: Dp = 130.dp) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(values) { p.snapTo(0f); p.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1).toFloat()
            val slot = size.width / values.size.coerceAtLeast(1)
            val w = (slot * 0.62f).coerceAtMost(26.dp.toPx())
            values.forEachIndexed { i, v ->
                val h = (size.height * v / max * p.value).coerceAtLeast(if (v > 0) 3.dp.toPx() else 2.dp.toPx())
                val x = slot * i + (slot - w) / 2
                val brush = if (i == highlight) Ink.GoldBrush else SolidColor(if (v > 0) Color(0xFF3A3A40) else Ink.Raised)
                drawRoundRect(brush, Offset(x, size.height - h), Size(w, h), CornerRadius(w / 2.5f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(
                    l, style = Type.Label.copy(fontSize = 9.sp, letterSpacing = 0.sp, color = if (i == highlight) Ink.Gold else Ink.Faint),
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1,
                )
            }
        }
    }
}

// ---------- number pad ----------

@Composable
fun Numpad(onKey: (Char) -> Unit, modifier: Modifier = Modifier) {
    val keys = listOf("123", "456", "789", ".0<")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).height(58.dp).neu(radius = 18.dp, elevation = 5.dp).tap { onKey(k) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (k == '<') "⌫" else k.toString(), style = Type.Title.copy(fontSize = 24.sp))
                    }
                }
            }
        }
    }
}

/** Applies a numpad key to an amount string, keeping at most 2 decimals and 9 digits. */
fun typeAmount(cur: String, k: Char): String = when {
    k == '<' -> cur.dropLast(1)
    k == '.' -> if (cur.contains('.')) cur else (cur.ifEmpty { "0" } + ".")
    cur.contains('.') && cur.substringAfter('.').length >= 2 -> cur
    cur.replace(".", "").length >= 9 -> cur
    cur == "0" -> k.toString()
    else -> cur + k
}

// ---------- icons not in material-icons-core ----------

object Glyph {
    val Chart: ImageVector = ImageVector.Builder("chart", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(4f, 13f); lineTo(7f, 13f); lineTo(7f, 20f); lineTo(4f, 20f); close()
            moveTo(10.5f, 8f); lineTo(13.5f, 8f); lineTo(13.5f, 20f); lineTo(10.5f, 20f); close()
            moveTo(17f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(17f, 20f); close()
        }
    }.build()

    val Grid: ImageVector = ImageVector.Builder("grid", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            listOf(4f to 4f, 14f to 4f, 4f to 14f, 14f to 14f).forEach { (x, y) ->
                moveTo(x, y); lineTo(x + 6f, y); lineTo(x + 6f, y + 6f); lineTo(x, y + 6f); close()
            }
        }
    }.build()
}

/** Which spends to show (All / Personal / Company). Always All until a card or spend is marked company. */
@Composable
fun shownBook(db: Db, v: Int): String? = androidx.compose.runtime.remember(v) { if (db.hasCompany()) db.book else null }

/** The Personal / Company switch. Same choice on Home, Spends and Insights. */
@Composable
fun BookSwitch(db: Db, book: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(BOOK_ALL to "All", BOOK_PERSONAL to "Personal", BOOK_COMPANY to "Company").forEach { (b, label) ->
            Pill(label, book == b, { db.book = b }, leading = if (b == BOOK_COMPANY) "💼" else if (b == BOOK_PERSONAL) "🙂" else null)
        }
    }
}
