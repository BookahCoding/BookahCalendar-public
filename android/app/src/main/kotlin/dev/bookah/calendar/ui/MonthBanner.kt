package dev.bookah.calendar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * The month banner of the Schedule and the strip in the drawer (docs/design-spec.md 3.4): original vector drawing in the ukiyo-e
 * manner (flat colour shapes), one motif per month, tinted from the current theme's two accents plus paper and ink. The month name
 * sits on a scrim of the Surface colour on the left, never on the busy part of the picture. Static: no animation.
 */
@Composable
fun MonthBanner(month: Month, title: String, height: Dp = 120.dp, modifier: Modifier = Modifier) {
    val t = LocalThemeColors.current
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp)).background(lerp(t.surface3, t.secondary, 0.22f))) {
        Canvas(Modifier.fillMaxSize()) { drawMotif(month, t.accent, t.secondary, t.markPaper, t.text, t.markVermilion) }
        // scrim behind the label, fading out to the right
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to surface.copy(alpha = 0.92f), 0.45f to surface.copy(alpha = 0.75f), 0.75f to Color.Transparent)))
        Text(title, Modifier.align(Alignment.CenterStart).padding(start = 18.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

fun monthTitle(month: Month, year: Int, locale: Locale = Locale.getDefault()) = month.getDisplayName(TextStyle.FULL_STANDALONE, locale) + " " + year

private fun DrawScope.drawMotif(month: Month, a1: Color, a2: Color, paper: Color, ink: Color, red: Color) {
    val w = size.width
    val h = size.height
    when (month) {
        Month.JANUARY, Month.DECEMBER -> { sun(w * 0.80f, h * 0.30f, h * 0.14f, red); fuji(w * 0.66f, h, w * 0.5f, h * 0.70f, a2, paper) }
        Month.FEBRUARY -> { pines(w * 0.68f, h, h * 0.62f, ink); pines(w * 0.82f, h, h * 0.8f, ink); snowFlecks(w, h, paper) }
        Month.MARCH -> { sun(w * 0.86f, h * 0.28f, h * 0.12f, red); waves(h * 0.62f, h * 0.12f, a2, w * 0.4f, w); waves(h * 0.78f, h * 0.10f, a1, w * 0.4f, w) }
        Month.APRIL -> { fuji(w * 0.75f, h, w * 0.42f, h * 0.55f, a2, paper); blossom(w, h, red, paper) }
        Month.MAY -> { reeds(w * 0.62f, h, h * 0.7f, a1); reeds(w * 0.74f, h, h * 0.9f, a1); reeds(w * 0.86f, h, h * 0.6f, a1) }
        Month.JUNE -> { rain(w, h, a2); waves(h * 0.80f, h * 0.10f, a1, w * 0.4f, w) }
        Month.JULY -> { greatWave(w, h, a1, paper); sun(w * 0.9f, h * 0.22f, h * 0.10f, red) }
        Month.AUGUST -> { sun(w * 0.78f, h * 0.45f, h * 0.30f, red); waves(h * 0.82f, h * 0.08f, a1, w * 0.4f, w) }
        Month.SEPTEMBER -> { moon(w * 0.82f, h * 0.30f, h * 0.15f, paper); reeds(w * 0.66f, h, h * 0.7f, ink) }
        Month.OCTOBER -> { fuji(w * 0.72f, h, w * 0.5f, h * 0.7f, red, paper); maple(w, h, red) }
        Month.NOVEMBER -> { moon(w * 0.86f, h * 0.28f, h * 0.13f, paper); pines(w * 0.68f, h, h * 0.7f, ink); reeds(w * 0.8f, h, h * 0.5f, ink) }
    }
}

private fun DrawScope.sun(cx: Float, cy: Float, r: Float, color: Color) = drawCircle(color, r, Offset(cx, cy))

private fun DrawScope.moon(cx: Float, cy: Float, r: Float, color: Color) = drawCircle(color, r, Offset(cx, cy))

private fun DrawScope.fuji(cx: Float, baseY: Float, w: Float, h: Float, body: Color, snow: Color) {
    val top = baseY - h
    val p = Path().apply { moveTo(cx - w / 2, baseY); lineTo(cx - w * 0.08f, top); lineTo(cx + w * 0.08f, top); lineTo(cx + w / 2, baseY); close() }
    drawPath(p, body)
    val cap = Path().apply {
        moveTo(cx - w * 0.08f, top); lineTo(cx + w * 0.08f, top); lineTo(cx + w * 0.17f, top + h * 0.22f)
        lineTo(cx + w * 0.07f, top + h * 0.15f); lineTo(cx, top + h * 0.26f); lineTo(cx - w * 0.07f, top + h * 0.15f); lineTo(cx - w * 0.17f, top + h * 0.22f); close()
    }
    drawPath(cap, snow)
}

private fun DrawScope.waves(y: Float, amp: Float, color: Color, period: Float, width: Float) {
    val p = Path().apply {
        moveTo(0f, size.height); lineTo(0f, y)
        var x = 0f
        while (x < width) { quadraticTo(x + period / 4, y - amp, x + period / 2, y); quadraticTo(x + period * 0.75f, y + amp, x + period, y); x += period }
        lineTo(width, size.height); close()
    }
    drawPath(p, color)
}

private fun DrawScope.greatWave(w: Float, h: Float, body: Color, foam: Color) {
    val p = Path().apply {
        moveTo(w * 0.40f, h); lineTo(w * 0.40f, h * 0.70f)
        cubicTo(w * 0.50f, h * 0.35f, w * 0.68f, h * 0.10f, w * 0.84f, h * 0.30f)
        cubicTo(w * 0.78f, h * 0.28f, w * 0.74f, h * 0.36f, w * 0.78f, h * 0.46f)
        cubicTo(w * 0.86f, h * 0.55f, w * 0.94f, h * 0.60f, w, h * 0.70f); lineTo(w, h); close()
    }
    drawPath(p, body)
    for (i in 0 until 6) drawCircle(foam, h * (0.025f + 0.005f * (i % 3)), Offset(w * (0.70f + 0.035f * i), h * (0.22f + 0.06f * (i % 2) + 0.03f * i)))
}

private fun DrawScope.pines(cx: Float, baseY: Float, h: Float, color: Color) {
    drawRect(color, Offset(cx - h * 0.03f, baseY - h * 0.25f), Size(h * 0.06f, h * 0.25f))
    for (i in 0 until 3) {
        val y = baseY - h * (0.2f + 0.25f * i)
        val half = h * (0.28f - 0.07f * i)
        drawPath(Path().apply { moveTo(cx - half, y); lineTo(cx, y - h * 0.3f); lineTo(cx + half, y); close() }, color)
    }
}

private fun DrawScope.reeds(x: Float, baseY: Float, h: Float, color: Color) {
    drawLine(color, Offset(x, baseY), Offset(x + h * 0.08f, baseY - h), strokeWidth = h * 0.035f)
    drawOval(color, Offset(x + h * 0.04f, baseY - h * 0.95f), Size(h * 0.09f, h * 0.22f))
    drawLine(color, Offset(x, baseY - h * 0.2f), Offset(x - h * 0.22f, baseY - h * 0.55f), strokeWidth = h * 0.02f)
}

private fun DrawScope.rain(w: Float, h: Float, color: Color) {
    for (i in 0 until 16) {
        val x = w * (0.42f + 0.035f * i)
        drawLine(color.copy(alpha = 0.7f), Offset(x, h * (0.10f + 0.15f * (i % 3))), Offset(x - h * 0.08f, h * (0.30f + 0.15f * (i % 3))), strokeWidth = 3f)
    }
}

private fun DrawScope.blossom(w: Float, h: Float, petal: Color, centre: Color) {
    for (i in 0 until 9) {
        val c = Offset(w * (0.45f + 0.055f * i), h * (0.18f + 0.12f * ((i * 5) % 4)))
        drawCircle(petal, h * 0.045f, c)
        drawCircle(centre, h * 0.014f, c)
    }
}

private fun DrawScope.maple(w: Float, h: Float, color: Color) {
    for (i in 0 until 7) {
        val c = Offset(w * (0.45f + 0.07f * i), h * (0.25f + 0.1f * ((i * 3) % 4)))
        drawPath(Path().apply { moveTo(c.x, c.y - h * 0.05f); lineTo(c.x + h * 0.04f, c.y + h * 0.03f); lineTo(c.x - h * 0.04f, c.y + h * 0.03f); close() }, color)
    }
}

private fun DrawScope.snowFlecks(w: Float, h: Float, color: Color) {
    for (i in 0 until 14) drawCircle(color, 3f + (i % 3), Offset(w * (0.42f + 0.04f * i), h * (0.12f + 0.2f * ((i * 7) % 5) / 2f)))
}
