package com.music.bitchord.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.ui.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * Complete 36-role neutral monochrome dark [ColorScheme] for BitChord.
 *
 * Every Material 3 color and surface-container token is explicitly specified
 * so zero stock Material 3 purple or pink fallback colors remain.
 */
val BitChordDarkColorScheme: ColorScheme = ColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF2C2C2C),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF1C1C1C),
    secondary = Color(0xFFE6E6E6),
    onSecondary = Color(0xFF121212),
    secondaryContainer = Color(0xFF242424),
    onSecondaryContainer = Color(0xFFF2F2F2),
    tertiary = Color(0xFFD4D4D4),
    onTertiary = Color(0xFF121212),
    tertiaryContainer = Color(0xFF282828),
    onTertiaryContainer = Color(0xFFF2F2F2),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0E0E0E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFF9A9A9A),
    surfaceTint = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFFF2F2F2),
    inverseOnSurface = Color(0xFF121212),
    error = Color(0xFFFF6961),
    onError = Color(0xFF1A0002),
    errorContainer = Color(0xFF3B1214),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF858585),
    outlineVariant = Color(0xFF2C2C2C),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2E2E2E),
    surfaceDim = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF181818),
    surfaceContainerHigh = Color(0xFF202020),
    surfaceContainerHighest = Color(0xFF282828),
    surfaceContainerLow = Color(0xFF121212),
    surfaceContainerLowest = Color(0xFF080808),
)

/**
 * Complete 36-role neutral monochrome light [ColorScheme] for BitChord.
 *
 * Every Material 3 color and surface-container token is explicitly specified
 * so zero stock Material 3 purple or pink fallback colors remain.
 */
val BitChordLightColorScheme: ColorScheme = ColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E6E6),
    onPrimaryContainer = Color(0xFF000000),
    inversePrimary = Color(0xFFF2F2F2),
    secondary = Color(0xFF3A3A3A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEAEAEA),
    onSecondaryContainer = Color(0xFF121212),
    tertiary = Color(0xFF484848),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE4E4E4),
    onTertiaryContainer = Color(0xFF121212),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFF7F7F7),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceTint = Color(0xFF000000),
    inverseSurface = Color(0xFF1C1C1C),
    inverseOnSurface = Color(0xFFF2F2F2),
    error = Color(0xFFB3191E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF6E6E6E),
    outlineVariant = Color(0xFFDADADA),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDCDCDC),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE6E6E6),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

/**
 * Dedicated semantic presence indicator colors for Discord integration.
 *
 * Kept independent from [ColorScheme] and [ArtworkPalette] so user presence
 * dots never shift hue when artwork tinting or theme mode changes.
 */
@Immutable
object DiscordPresenceColors {
    val Online: Color = Color(0xFF23A55A)
    val Idle: Color = Color(0xFFF0B232)
    val DoNotDisturb: Color = Color(0xFFF23F43)
    val Offline: Color = Color(0xFF80848E)
}

/** Minimum WCAG 2.1 AA contrast ratio for normal UI text and interactive glyphs. */
internal const val WCAG_AA_TEXT_CONTRAST = 4.5f

/** Minimum WCAG 2.1 AA contrast ratio for graphical objects and UI control boundaries. */
internal const val WCAG_AA_UI_CONTRAST = 3.0f

/**
 * Computes WCAG 2.1 contrast ratio `(L_lighter + 0.05) / (L_darker + 0.05)` in `[1f, 21f]`.
 */
fun wcagContrastRatio(first: Color, second: Color): Float {
    val l1 = ColorUtils.calculateLuminance(first.toArgb()).toFloat()
    val l2 = ColorUtils.calculateLuminance(second.toArgb()).toFloat()
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * Clamps [foreground] lightness in HSL space (preserving hue and saturation as closely as
 * possible) so that [wcagContrastRatio] against every color in [backgrounds] is at least
 * [minContrast]. Falls back to pure white or black if chromatic saturation prevents reaching
 * the target ratio at the extreme lightness bound.
 */
internal fun clampColorContrast(
    foreground: Color,
    backgrounds: List<Color>,
    minContrast: Float = WCAG_AA_TEXT_CONTRAST,
    preferLighter: Boolean,
): Color {
    if (backgrounds.all { wcagContrastRatio(foreground, it) >= minContrast }) {
        return foreground
    }

    val baseHsl = FloatArray(3).also { ColorUtils.colorToHSL(foreground.toArgb(), it) }
    val solvedPrimary = searchLightnessForContrast(baseHsl, backgrounds, minContrast, lighter = preferLighter)
    if (solvedPrimary != null) return solvedPrimary

    val solvedOpposite = searchLightnessForContrast(baseHsl, backgrounds, minContrast, lighter = !preferLighter)
    if (solvedOpposite != null) return solvedOpposite

    val whiteMin = backgrounds.minOf { wcagContrastRatio(Color.White, it) }
    val blackMin = backgrounds.minOf { wcagContrastRatio(Color.Black, it) }
    return if (whiteMin >= blackMin) Color.White else Color.Black
}

private fun searchLightnessForContrast(
    baseHsl: FloatArray,
    backgrounds: List<Color>,
    minContrast: Float,
    lighter: Boolean,
): Color? {
    val extreme = FloatArray(3).apply {
        this[0] = baseHsl[0]
        this[1] = baseHsl[1]
        this[2] = if (lighter) 1f else 0f
    }
    val extremeColor = Color(ColorUtils.HSLToColor(extreme))
    if (backgrounds.any { wcagContrastRatio(extremeColor, it) < minContrast }) {
        return null
    }

    var low = if (lighter) baseHsl[2].coerceIn(0f, 1f) else 0f
    var high = if (lighter) 1f else baseHsl[2].coerceIn(0f, 1f)
    val probe = floatArrayOf(baseHsl[0], baseHsl[1], baseHsl[2])

    repeat(18) {
        val mid = (low + high) * 0.5f
        probe[2] = mid
        val candidate = Color(ColorUtils.HSLToColor(probe))
        val passes = backgrounds.all { wcagContrastRatio(candidate, it) >= minContrast }
        if (lighter) {
            if (passes) high = mid else low = mid
        } else {
            if (passes) low = mid else high = mid
        }
    }
    probe[2] = if (lighter) high else low
    return Color(ColorUtils.HSLToColor(probe))
}

/**
 * Returns all 36 [ColorScheme] roles as a named map for verification and diagnostics.
 */
fun ColorScheme.allRoles(): Map<String, Color> = linkedMapOf(
    "primary" to primary,
    "onPrimary" to onPrimary,
    "primaryContainer" to primaryContainer,
    "onPrimaryContainer" to onPrimaryContainer,
    "inversePrimary" to inversePrimary,
    "secondary" to secondary,
    "onSecondary" to onSecondary,
    "secondaryContainer" to secondaryContainer,
    "onSecondaryContainer" to onSecondaryContainer,
    "tertiary" to tertiary,
    "onTertiary" to onTertiary,
    "tertiaryContainer" to tertiaryContainer,
    "onTertiaryContainer" to onTertiaryContainer,
    "background" to background,
    "onBackground" to onBackground,
    "surface" to surface,
    "onSurface" to onSurface,
    "surfaceVariant" to surfaceVariant,
    "onSurfaceVariant" to onSurfaceVariant,
    "surfaceTint" to surfaceTint,
    "inverseSurface" to inverseSurface,
    "inverseOnSurface" to inverseOnSurface,
    "error" to error,
    "onError" to onError,
    "errorContainer" to errorContainer,
    "onErrorContainer" to onErrorContainer,
    "outline" to outline,
    "outlineVariant" to outlineVariant,
    "scrim" to scrim,
    "surfaceBright" to surfaceBright,
    "surfaceDim" to surfaceDim,
    "surfaceContainer" to surfaceContainer,
    "surfaceContainerHigh" to surfaceContainerHigh,
    "surfaceContainerHighest" to surfaceContainerHighest,
    "surfaceContainerLow" to surfaceContainerLow,
    "surfaceContainerLowest" to surfaceContainerLowest,
)

/**
 * Linearly interpolates all 36 roles between [start] and [stop] using a single [fraction]
 * so dynamic artwork crossfades drive one animation channel instead of 36 independent
 * coroutines.
 */
internal fun lerpColorScheme(start: ColorScheme, stop: ColorScheme, fraction: Float): ColorScheme {
    val t = fraction.coerceIn(0f, 1f)
    if (t <= 0f) return start
    if (t >= 1f) return stop
    return ColorScheme(
        primary = lerp(start.primary, stop.primary, t),
        onPrimary = lerp(start.onPrimary, stop.onPrimary, t),
        primaryContainer = lerp(start.primaryContainer, stop.primaryContainer, t),
        onPrimaryContainer = lerp(start.onPrimaryContainer, stop.onPrimaryContainer, t),
        inversePrimary = lerp(start.inversePrimary, stop.inversePrimary, t),
        secondary = lerp(start.secondary, stop.secondary, t),
        onSecondary = lerp(start.onSecondary, stop.onSecondary, t),
        secondaryContainer = lerp(start.secondaryContainer, stop.secondaryContainer, t),
        onSecondaryContainer = lerp(start.onSecondaryContainer, stop.onSecondaryContainer, t),
        tertiary = lerp(start.tertiary, stop.tertiary, t),
        onTertiary = lerp(start.onTertiary, stop.onTertiary, t),
        tertiaryContainer = lerp(start.tertiaryContainer, stop.tertiaryContainer, t),
        onTertiaryContainer = lerp(start.onTertiaryContainer, stop.onTertiaryContainer, t),
        background = lerp(start.background, stop.background, t),
        onBackground = lerp(start.onBackground, stop.onBackground, t),
        surface = lerp(start.surface, stop.surface, t),
        onSurface = lerp(start.onSurface, stop.onSurface, t),
        surfaceVariant = lerp(start.surfaceVariant, stop.surfaceVariant, t),
        onSurfaceVariant = lerp(start.onSurfaceVariant, stop.onSurfaceVariant, t),
        surfaceTint = lerp(start.surfaceTint, stop.surfaceTint, t),
        inverseSurface = lerp(start.inverseSurface, stop.inverseSurface, t),
        inverseOnSurface = lerp(start.inverseOnSurface, stop.inverseOnSurface, t),
        error = lerp(start.error, stop.error, t),
        onError = lerp(start.onError, stop.onError, t),
        errorContainer = lerp(start.errorContainer, stop.errorContainer, t),
        onErrorContainer = lerp(start.onErrorContainer, stop.onErrorContainer, t),
        outline = lerp(start.outline, stop.outline, t),
        outlineVariant = lerp(start.outlineVariant, stop.outlineVariant, t),
        scrim = lerp(start.scrim, stop.scrim, t),
        surfaceBright = lerp(start.surfaceBright, stop.surfaceBright, t),
        surfaceDim = lerp(start.surfaceDim, stop.surfaceDim, t),
        surfaceContainer = lerp(start.surfaceContainer, stop.surfaceContainer, t),
        surfaceContainerHigh = lerp(start.surfaceContainerHigh, stop.surfaceContainerHigh, t),
        surfaceContainerHighest = lerp(start.surfaceContainerHighest, stop.surfaceContainerHighest, t),
        surfaceContainerLow = lerp(start.surfaceContainerLow, stop.surfaceContainerLow, t),
        surfaceContainerLowest = lerp(start.surfaceContainerLowest, stop.surfaceContainerLowest, t),
    )
}
