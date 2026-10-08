package com.x45.expense.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x45.expense.R

/** Dark, premium palette: near-black surfaces, warm ivory text, champagne-gold accent. */
object Ink {
    val Bg = Color(0xFF0B0B0D)
    val Surface = Color(0xFF141417)
    val Raised = Color(0xFF1B1B1F)
    val Stroke = Color(0xFF2A2A2F)
    val Text = Color(0xFFF4F1EA)
    val Muted = Color(0xFFA9A59E)
    val Faint = Color(0xFF7A7771)
    val Gold = Color(0xFFE6C88E)
    val GoldDeep = Color(0xFFB8975A)
    val Mint = Color(0xFF7FE0B2)
    val Coral = Color(0xFFFF7B6B)
    val GoldBrush = Brush.linearGradient(listOf(Color(0xFFF3DDAE), Color(0xFFD7B271), Color(0xFFB8975A)))
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, w: Int) = Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Sans = FontFamily(variable(R.font.manrope, 400), variable(R.font.manrope, 500), variable(R.font.manrope, 600), variable(R.font.manrope, 700), variable(R.font.manrope, 800))

object Type {
    val Hero = TextStyle(fontFamily = Sans, fontWeight = FontWeight(800), fontSize = 42.sp, color = Ink.Text, letterSpacing = (-1).sp, fontFeatureSettings = "tnum")
    val Title = TextStyle(fontFamily = Sans, fontWeight = FontWeight(800), fontSize = 26.sp, color = Ink.Text, letterSpacing = (-0.5).sp)
    val H2 = TextStyle(fontFamily = Sans, fontWeight = FontWeight(700), fontSize = 18.sp, color = Ink.Text)
    val Body = TextStyle(fontFamily = Sans, fontWeight = FontWeight(500), fontSize = 15.sp, color = Ink.Text)
    val Small = TextStyle(fontFamily = Sans, fontWeight = FontWeight(500), fontSize = 14.sp, color = Ink.Muted)
    val Label = TextStyle(fontFamily = Sans, fontWeight = FontWeight(700), fontSize = 12.sp, color = Ink.Muted, letterSpacing = 1.2.sp)
    val Money = TextStyle(fontFamily = Sans, fontWeight = FontWeight(700), fontSize = 17.sp, color = Ink.Text, fontFeatureSettings = "tnum")
}

@Composable
fun SpendsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.Gold, onPrimary = Color(0xFF1A140A), secondary = Ink.Mint,
            background = Ink.Bg, surface = Ink.Surface, surfaceVariant = Ink.Raised, onSurface = Ink.Text, onBackground = Ink.Text,
            onSurfaceVariant = Ink.Muted, outline = Ink.Stroke, error = Ink.Coral,
            surfaceContainer = Ink.Raised, surfaceContainerHigh = Ink.Raised, surfaceContainerHighest = Ink.Raised, surfaceContainerLow = Ink.Surface,
        ),
        typography = MaterialTheme.typography.let { t ->
            t.copy(
                bodyLarge = t.bodyLarge.copy(fontFamily = Sans), bodyMedium = t.bodyMedium.copy(fontFamily = Sans),
                bodySmall = t.bodySmall.copy(fontFamily = Sans), labelLarge = t.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight(700)),
                labelMedium = t.labelMedium.copy(fontFamily = Sans), titleLarge = t.titleLarge.copy(fontFamily = Sans, fontWeight = FontWeight(800)),
                titleMedium = t.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight(700)),
                headlineSmall = t.headlineSmall.copy(fontFamily = Sans, fontWeight = FontWeight(800)),
            )
        },
        content = content,
    )
}

/** Soft "raised" card: a dark drop shadow below-right and a faint light edge above-left. */
fun Modifier.neu(radius: Dp = 22.dp, elevation: Dp = 10.dp, color: Color = Ink.Surface): Modifier = this
    .drawBehind {
        val r = radius.toPx()
        val e = elevation.toPx()
        drawIntoCanvas { c ->
            val p = android.graphics.Paint().apply {
                isAntiAlias = true
                this.color = color.toArgb()
            }
            p.setShadowLayer(e, e / 2, e / 2, Color.Black.copy(alpha = 0.75f).toArgb())
            c.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, p)
            p.setShadowLayer(e, -e / 3, -e / 3, Color.White.copy(alpha = 0.05f).toArgb())
            c.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, p)
        }
    }
    .clip(RoundedCornerShape(radius))
    .background(Brush.verticalGradient(listOf(color.copy(alpha = 1f).lighten(0.03f), color)))
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent)), RoundedCornerShape(radius))

/** Inset well (for inputs, tracks). */
fun Modifier.well(radius: Dp = 16.dp): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(Color(0xFF0E0E10))
    .border(1.dp, Brush.verticalGradient(listOf(Color.Black, Color.White.copy(alpha = 0.05f))), RoundedCornerShape(radius))

fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)

/** Press feedback: scale down on press + a light haptic tick on click. */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "tap")
    val haptic = LocalHapticFeedback.current
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = src, indication = null, enabled = enabled) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

fun Modifier.glow(color: Color, radius: Dp = 18.dp, shape: Shape = RoundedCornerShape(radius)): Modifier = this.drawBehind {
    drawIntoCanvas { c ->
        val p = android.graphics.Paint().apply {
            isAntiAlias = true; this.color = color.copy(alpha = 0.35f).toArgb()
            maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
        }
        val r = radius.toPx()
        c.nativeCanvas.drawRoundRect(6f, 10f, size.width - 6f, size.height + 4f, r, r, p)
    }
}
