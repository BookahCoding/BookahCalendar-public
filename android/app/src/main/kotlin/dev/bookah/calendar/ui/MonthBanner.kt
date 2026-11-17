package dev.bookah.calendar.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bookah.calendar.R
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** The picture of each month (docs/design-spec.md 3.4): one ukiyo-e scene per month, 1200 x 400 WebP made by tools/make-banners.py. */
object MonthArt {
    @DrawableRes
    fun resFor(month: Month): Int = when (month) {
        Month.JANUARY -> R.drawable.banner_01
        Month.FEBRUARY -> R.drawable.banner_02
        Month.MARCH -> R.drawable.banner_03
        Month.APRIL -> R.drawable.banner_04
        Month.MAY -> R.drawable.banner_05
        Month.JUNE -> R.drawable.banner_06
        Month.JULY -> R.drawable.banner_07
        Month.AUGUST -> R.drawable.banner_08
        Month.SEPTEMBER -> R.drawable.banner_09
        Month.OCTOBER -> R.drawable.banner_10
        Month.NOVEMBER -> R.drawable.banner_11
        Month.DECEMBER -> R.drawable.banner_12
    }

    // A decoded banner is about 1.9 MB; the Schedule scrolls past the same few again and again, so keep up to 8 MB of them.
    private val cache = object : LruCache<Int, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun bitmap(context: Context, @DrawableRes res: Int): ImageBitmap =
        cache.get(res) ?: BitmapFactory.decodeResource(context.resources, res).asImageBitmap().also { cache.put(res, it) }
}

/**
 * The month banner of the Schedule, the Month view and the strip in the drawer: the month's picture, with the name on a scrim
 * of the Surface colour on the left. Static: no animation. In high text contrast mode the picture is dropped and only the label stays.
 */
@Composable
fun MonthBanner(month: Month, title: String, height: Dp = 120.dp, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val t = LocalThemeColors.current
    val surface = MaterialTheme.colorScheme.surface
    val context = LocalContext.current
    val highContrast = remember { runCatching { Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1 }.getOrDefault(false) }
    val darkTheme = surface.luminance() < 0.5f
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp)).background(lerp(t.surface3, t.secondary, 0.22f))) {
        if (!highContrast) {
            val bitmap = remember(month) { MonthArt.bitmap(context, MonthArt.resFor(month)) }
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd)
            // on a dark theme the bright paper of the picture is dimmed a little
            if (darkTheme) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
        }
        if (showTitle) {
            // scrim behind the label, fading out to the right
            val solid = if (highContrast) 1f else 0.9f
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to surface.copy(alpha = solid), 0.35f to surface.copy(alpha = if (highContrast) 1f else 0.72f), 0.6f to Color.Transparent)))
            Text(title, Modifier.align(Alignment.CenterStart).padding(start = 18.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

fun monthTitle(month: Month, year: Int, locale: Locale = Locale.getDefault()) = month.getDisplayName(TextStyle.FULL_STANDALONE, locale) + " " + year
