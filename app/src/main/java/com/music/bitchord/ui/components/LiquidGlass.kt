/*
 * The glass rendering itself is Kyant0/backdrop (Apache-2.0), vendored at
 * [com.music.bitchord.ui.components.backdrop] — see that package for the
 * upstream attribution. This file is the integration glue, adapted from
 * EchoMusicApp/Echo-Music's GlassEffectConfig/Modifier.liquidGlass
 * (GPL-3.0), cut down from Echo's full per-component/vibrancy-slider config
 * to the single on/off switch BitChord exposes in Settings. It is used by the
 * floating nav bar and, while enabled, the app-wide floating top controls.
 */
package com.music.bitchord.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.backdrop.Backdrop
import com.music.bitchord.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.bitchord.ui.components.backdrop.drawBackdrop
import com.music.bitchord.ui.components.backdrop.effects.blur
import com.music.bitchord.ui.components.backdrop.effects.colorControls
import com.music.bitchord.ui.components.backdrop.effects.lens
import com.music.bitchord.ui.components.backdrop.highlight.Highlight
import com.music.bitchord.ui.components.backdrop.highlight.HighlightElement
import com.music.bitchord.ui.components.backdrop.highlight.HighlightStyle
import com.music.bitchord.ui.components.backdrop.internal.ShapeProvider
import com.music.bitchord.ui.components.backdrop.shadow.Shadow
import androidx.compose.ui.unit.dp

/** Whether the liquid glass nav bar is turned on — see [AppSettings.liquidGlass]. */
val LocalLiquidGlassEnabled = staticCompositionLocalOf { false }

/** The backdrop content (app UI) that a liquid glass surface samples from. */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop> { error("No AppBackdrop provided") }

/**
 * Where a [liquidGlass] surface composed under it also records the glass it
 * draws — the sampled, blurred and tinted backdrop, without its rim or shadow —
 * so a lens above it can refract that surface rather than the page behind it.
 * The tab bar's travelling selection pill is the one reader; see
 * [com.music.bitchord.ui.components.floatingtabbar.GlassSelectionPill].
 */
internal val LocalGlassExport = compositionLocalOf<LayerBackdrop?> { null }

/**
 * The backdrop blur pipeline requires [android.graphics.RenderEffect] on a
 * [android.graphics.RenderNode], available from Android 12 (API 31).
 */
fun isGlassSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

/** Apple-matched defaults (Echo's GlassEffectConfig()), with WCAG-calibrated tint floors. */
private const val VIBRANCY = 1f
private const val BLUR_RADIUS_DP = 8f
private const val LENS_HEIGHT = 0.5f
private const val LENS_AMOUNT = 0.5f
private const val LENS_MAX_DP = 48f
internal const val DARK_SURFACE_OPACITY = 0.72f
internal const val LIGHT_SURFACE_OPACITY = 0.68f
internal const val GLASS_SECONDARY_ALPHA = 0.78f
internal const val GLASS_SUBTITLE_ALPHA = 0.80f
internal const val HAZE_SECONDARY_ALPHA = 0.72f
internal const val DARK_GLASS_EDGE_ALPHA = 0.14f
internal const val LIGHT_GLASS_EDGE_ALPHA = 0.12f
internal val DARK_GLASS_TINT = Color(0xFF121212)
internal val LIGHT_GLASS_TINT = Color(0xFFFAFAFA)

private val LightGlassHighlight = Highlight(
    style = HighlightStyle.Default(
        color = Color.Black.copy(alpha = 0.22f),
        blendMode = DrawScope.DefaultBlendMode,
    ),
)

/**
 * Resolution fraction the glass surface records and processes its backdrop at.
 *
 * A third, which is what Echo's `glassResolutionScale` comes to at this blur
 * radius. It was briefly raised to a half while the sharp-text bug was being
 * chased — wrongly, as it turned out: the cause was a transparent backdrop, not
 * the resample (see MainActivity's `paintBackdrop`). A third is nine times fewer
 * pixels than full resolution through the colour matrix, the blur and the lens
 * shader, on as many as six surfaces at once in the middle of a fold, and the
 * blur is what hides the upscale.
 */
private const val GLASS_RESOLUTION_SCALE = 0.33f

/**
 * The blur and resolution under [AppSettings.glassPerformance]. With no lens to
 * bend what is behind it, the frost alone has to break up a page's text, so it
 * is twice the radius — and that wider blur hides a smaller recording, which
 * takes the surface from about 11% of its pixels to about 6%.
 */
private const val FROST_BLUR_RADIUS_DP = 16f
private const val FROST_RESOLUTION_SCALE = 0.25f

/**
 * The hairline along a bar's edge, and what stands in for the glass rim
 * wherever the glass itself is not drawn.
 */
internal val GLASS_EDGE_WIDTH = 0.5.dp

internal fun isLightGlassSurface(surface: Color): Boolean = surface.luminance() > 0.5f

internal fun glassSurfaceTintColor(isLight: Boolean): Color =
    if (isLight) {
        LIGHT_GLASS_TINT.copy(alpha = LIGHT_SURFACE_OPACITY)
    } else {
        DARK_GLASS_TINT.copy(alpha = DARK_SURFACE_OPACITY)
    }

internal fun glassContentColor(isLight: Boolean): Color =
    if (isLight) Color.Black else Color.White

internal fun glassSecondaryContentColor(isLight: Boolean): Color =
    glassContentColor(isLight).copy(alpha = GLASS_SECONDARY_ALPHA)

internal fun glassSubtitleContentColor(isLight: Boolean): Color =
    glassContentColor(isLight).copy(alpha = GLASS_SUBTITLE_ALPHA)

internal fun hazeSecondaryContentColor(onSurface: Color): Color =
    onSurface.copy(alpha = HAZE_SECONDARY_ALPHA)

internal fun glassEdgeColor(isLight: Boolean): Color =
    if (isLight) {
        Color.Black.copy(alpha = LIGHT_GLASS_EDGE_ALPHA)
    } else {
        Color.White.copy(alpha = DARK_GLASS_EDGE_ALPHA)
    }

internal fun glassHighlight(isLight: Boolean): Highlight =
    if (isLight) LightGlassHighlight else Highlight.Default

/**
 * Icon and label colour for content sitting on a glass surface.
 *
 * Glass shows whatever is behind it rather than the theme's surface colour, so
 * the usual onSurface greys have nothing dependable to sit against. Pure black
 * or white off the theme's luminance is the only tint that holds against
 * arbitrary artwork, and it is what Echo's own glass nav bar uses.
 */
@Composable
fun glassContentColor(): Color =
    glassContentColor(isLightGlassSurface(MaterialTheme.colorScheme.surface))

/** Calibrated secondary ink for unselected 10.sp tabs and search icons/hints on glass. */
@Composable
fun glassSecondaryContentColor(): Color =
    glassSecondaryContentColor(isLightGlassSurface(MaterialTheme.colorScheme.surface))

/** Calibrated secondary ink for artist subtitles and explicit badges on glass. */
@Composable
fun glassSubtitleContentColor(): Color =
    glassSubtitleContentColor(isLightGlassSurface(MaterialTheme.colorScheme.surface))

/** Calibrated secondary ink for Haze surfaces over arbitrary artwork backdrops. */
@Composable
fun hazeSecondaryContentColor(): Color =
    hazeSecondaryContentColor(MaterialTheme.colorScheme.onSurface)

/** Theme-polarized hairline edge for glass, Haze, and reduced-blur bars. */
@Composable
fun glassEdgeColor(): Color =
    glassEdgeColor(isLightGlassSurface(MaterialTheme.colorScheme.surface))

/**
 * Selected-tab indicator colour for a glass surface: white in both themes.
 *
 * In light theme that is the inverse of [glassContentColor], a bright lift
 * behind a dark glyph. In dark theme it is a faint white lift over the bar's
 * dark glass rather than a black scrim pressed into it — the selected tab is
 * raised out of the bar, as on iOS, not sunk.
 */
@Composable
fun glassIndicatorColor(): Color =
    if (isLightGlassSurface(MaterialTheme.colorScheme.surface)) {
        Color.White.copy(alpha = 0.5f)
    } else {
        Color.White.copy(alpha = DARK_INDICATOR_OPACITY)
    }

/** The selected tab's pill in dark theme, a step whiter than the bar. */
internal const val DARK_INDICATOR_OPACITY = 0.14f

/**
 * A lightweight visual match for liquid glass over a stable background.
 *
 * This keeps the same translucent tint, directional highlight and hairline as
 * [liquidGlass], but intentionally performs no backdrop capture, blur, lens
 * refraction or shadow rendering. When Liquid Glass is disabled or unsupported,
 * [fallbackColor] preserves the control's existing filled appearance.
 */
@Composable
fun Modifier.lightweightLiquidGlass(
    shape: CornerBasedShape,
    fallbackColor: Color,
): Modifier {
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val isLight = isLightGlassSurface(MaterialTheme.colorScheme.surface)
    val highlight = glassHighlight(isLight)
    val edgeColor = glassEdgeColor(isLight)
    val shapeProvider = ShapeProvider { shape }

    return clip(shape)
        .background(
            color = if (useGlass) glassSurfaceTintColor(isLight) else fallbackColor,
            shape = shape,
        )
        .then(
            if (useGlass) {
                HighlightElement(
                    shapeProvider = shapeProvider,
                    highlight = { highlight },
                )
            } else {
                Modifier
            },
        )
        .border(GLASS_EDGE_WIDTH, edgeColor, shape)
}

/**
 * Just the rim of liquid glass — the directional [Highlight] that catches the
 * light along a glass edge — for a control that keeps its own fill.
 *
 * Over a flat, stable page the blur and lens of [liquidGlass] would sample a
 * single colour and change nothing a reader could see; the rim is the part
 * that reads as glass. No backdrop capture, so it costs a stroke. Nothing at
 * all while Liquid Glass is off or unsupported.
 */
@Composable
fun Modifier.liquidGlassRim(shape: CornerBasedShape): Modifier {
    if (!LocalLiquidGlassEnabled.current || !isGlassSupported()) return this
    val isLight = isLightGlassSurface(MaterialTheme.colorScheme.surface)
    val highlight = glassHighlight(isLight)
    val shapeProvider = remember(shape) { ShapeProvider { shape } }
    return then(
        HighlightElement(
            shapeProvider = shapeProvider,
            highlight = { highlight },
        ),
    )
}

/**
 * Renders this composable as a liquid glass surface sampling [LocalAppBackdrop]:
 * vibrancy, blur and lens refraction, then a theme-adaptive surface tint (light
 * glass on light theme, dark on dark). Returns the receiver unchanged on devices
 * without RenderEffect support — callers should still gate on [isGlassSupported]
 * to fall back to the regular Haze treatment there.
 *
 * Under "reduce dynamic blur" the surface is filled solid instead, which is what
 * that setting promises everywhere else in the app. It is checked here rather
 * than at each call site so there is one answer to it: [MainActivity] also stops
 * recording the backdrop layer when it is on, and a surface that still tried to
 * sample would be sampling a layer nothing is drawing into.
 *
 * Under [AppSettings.glassPerformance] the lens goes and the blur frosts instead,
 * for phones that can't spare the refraction shader. The vibrancy, tint, rim and
 * shadow are the same either way.
 *
 * [shape] is restricted to [CornerBasedShape] because the backdrop's lens effect
 * throws for any other shape type.
 */
@Composable
fun Modifier.liquidGlass(
    shape: CornerBasedShape,
    /** Drawn over the glass in place of the theme's default tint, when set. */
    surfaceTint: Color? = null,
): Modifier {
    if (!isGlassSupported()) return this
    val isLight = isLightGlassSurface(MaterialTheme.colorScheme.surface)
    val edgeColor = glassEdgeColor(isLight)
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    if (reduceDynamicBlur) {
        return background(MaterialTheme.colorScheme.surface, shape)
            .border(GLASS_EDGE_WIDTH, edgeColor, shape)
    }
    val glassPerformance by AppSettings.glassPerformance.collectAsStateWithLifecycle()
    val backdrop = LocalAppBackdrop.current
    val exportedBackdrop = LocalGlassExport.current
    val density = LocalDensity.current
    val resolutionScale = if (glassPerformance) FROST_RESOLUTION_SCALE else GLASS_RESOLUTION_SCALE
    val blurRadiusDp = if (glassPerformance) FROST_BLUR_RADIUS_DP else BLUR_RADIUS_DP
    val blurPx = with(density) { blurRadiusDp.dp.toPx() } * resolutionScale
    val lensHeightPx = with(density) { (LENS_HEIGHT * LENS_MAX_DP).dp.toPx() } * resolutionScale
    val lensAmountPx = with(density) { (LENS_AMOUNT * LENS_MAX_DP).dp.toPx() } * resolutionScale
    val surfaceTintColor = surfaceTint ?: glassSurfaceTintColor(isLight)
    val highlight = glassHighlight(isLight)

    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1f + 0.5f * VIBRANCY)
            blur(blurPx)
            if (!glassPerformance && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens(
                    refractionHeight = lensHeightPx,
                    refractionAmount = lensAmountPx,
                    depthEffect = true,
                    chromaticAberration = true,
                )
            }
        },
        highlight = { highlight },
        shadow = { Shadow.Default },
        onDrawSurface = {
            drawRect(color = surfaceTintColor, size = size)
        },
        exportedBackdrop = exportedBackdrop,
        backdropScale = resolutionScale,
    )
}
