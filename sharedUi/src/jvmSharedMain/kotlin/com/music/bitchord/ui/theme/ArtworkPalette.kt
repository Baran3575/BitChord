package com.music.bitchord.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ImageBitmap
import coil3.compose.LocalPlatformContext
import com.music.bitchord.ui.graphics.ColorUtils
import com.music.bitchord.ui.graphics.forPixelAccess
import com.music.bitchord.ui.graphics.paletteSwatches
import com.music.bitchord.ui.graphics.toImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.player.PlayerPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The colours an album, playlist or artist page paints itself in.
 *
 * Apple Music's release pages are not one design tinted five ways — the whole
 * page is derived from the sleeve, down to which grey the metadata line is. So
 * rather than hand callers a raw swatch and let each of them guess, this is the
 * finished set: a page tint, an accent that is legible *on that tint*, and the
 * two text colours and hairline that go with them.
 *
 * Every value is theme-aware. The same sleeve yields a near-black tint in dark
 * mode and a pale wash of the same hue in light mode, which is the only way the
 * pages stay readable when the app's theme disagrees with the artwork's.
 */
@Immutable
data class ArtworkPalette(
    /** The page's background wash. */
    val background: Color,
    /**
     * The colour the artwork's own bottom edge blurs down to.
     *
     * A blur wide enough to lose the picture leaves the mean of what it
     * sampled, so a page that starts from this colour where the artwork stops
     * reads as that blur carrying on rather than as a second surface beginning.
     * Lighter than [background]. An artist page is this colour, flat, all the
     * way down under its photograph; a release page, whose sleeve sits in a
     * frame rather than bleeding into the page, is [background] instead.
     */
    val wash: Color,
    /** Fill for the glass buttons and chips that sit on [background]. */
    val elevated: Color,
    /** The artwork's own colour, contrast-corrected — titles, icons, Play. */
    val accent: Color,
    val onBackground: Color,
    val onBackgroundVariant: Color,
    val divider: Color,
    /**
     * Complete 36-role contrast-clamped Material 3 [ColorScheme] harmonized
     * with the artwork's dominant, vibrant, and edge swatches (or neutral
     * monochrome fallback when saturation is below [CHROMATIC_SATURATION_THRESHOLD]).
     * Cross-fades smoothly unless `reduceAnimation` is enabled or cached up front.
     */
    val colorScheme: ColorScheme = BitChordDarkColorScheme,
    /**
     * Discrete settled target [ColorScheme] without per-frame interpolation ticks,
     * used on root `HazeSource` / `LayerBackdrop` hosts so `LocalColorScheme`
     * (`staticCompositionLocalOf`) updates at most once per track change.
     */
    val targetColorScheme: ColorScheme = colorScheme,
)

/**
 * Colours that came with the artwork rather than from decoding it — Apple
 * Music publishes a background and a text colour for every artist photograph.
 */
@Immutable
data class ArtworkKeyColors(val background: Color, val accent: Color)

/**
 * Pulls [ArtworkPalette] out of the artwork at [imageUrl], or out of
 * [keyColors] when the source already supplied them and there is nothing to
 * decode.
 *
 * Artwork that has already been read once is tinted on the very first frame,
 * off [seedCache] — a sheet opened from a page it shares a cover with, or a
 * page opened twice, has nothing to wait for and nothing to fade. Only a sleeve
 * genuinely being seen for the first time starts from the theme's own colours
 * and warms into the artwork's, so it never flashes a placeholder tint.
 * "Reduce animation" turns that crossfade into a cut.
 */
@Composable
fun rememberArtworkPalette(
    imageUrl: String?,
    dark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    /**
     * The artwork size to read, which should be whichever one the surface
     * already has on screen, matching the backdrop drawn from it.
     *
     * A quantiser cares about a thumbnail's resolution no more than a blur
     * does, so the only thing this choice decides is whether the read comes
     * out of the cache or off the network.
     */
    artPx: Int = CARD_ART_PX,
    keyColors: ArtworkKeyColors? = null,
): ArtworkPalette {
    val scheme = MaterialTheme.colorScheme
    val reduceAnimation by PlayerPlatform.host.settings.reduceAnimation.collectAsStateWithLifecycle()
    // Always asked, so the composable call is unconditional; handed nothing to
    // read when the colours are already in hand.
    val decoded = rememberArtworkSeed(if (keyColors == null) imageUrl else null, artPx)
    val rawSeed = keyColors?.toSeed() ?: decoded
    // Hold the outgoing sleeve's seed while an incoming non-null artwork URL is
    // still decoding so track switches glide directly from Track A to Track B
    // instead of flashing neutral monochrome mid-switch.
    var lastSeed by remember { mutableStateOf<Seed?>(null) }
    if (rawSeed != null) {
        lastSeed = rawSeed
    } else if (imageUrl == null && keyColors == null) {
        lastSeed = null
    }
    val seed = if (imageUrl == null && keyColors == null) null else (rawSeed ?: lastSeed)
    // Whether the colours were there from the first frame. If they were, there
    // is nothing to crossfade *from* and animating would only put a delay in
    // front of a surface that could already be right.
    val knownUpFront = remember(imageUrl) { decoded != null }

    val fallbackScheme = if (dark) BitChordDarkColorScheme else BitChordLightColorScheme
    val target = remember(seed, dark, scheme, fallbackScheme) {
        seed?.toPalette(dark) ?: ArtworkPalette(
            background = scheme.background,
            wash = scheme.background,
            elevated = scheme.surfaceVariant,
            accent = scheme.primary,
            onBackground = scheme.onBackground,
            onBackgroundVariant = scheme.onSurfaceVariant,
            divider = scheme.outline,
            colorScheme = fallbackScheme,
            targetColorScheme = fallbackScheme,
        )
    }

    val spec: AnimationSpec<Color> = if (reduceAnimation || knownUpFront) {
        snap()
    } else {
        tween(TINT_FADE_MS)
    }

    var startScheme by remember { mutableStateOf(target.colorScheme) }
    var settledScheme by remember { mutableStateOf(target.colorScheme) }
    val schemeProgress = remember { Animatable(1f) }
    LaunchedEffect(target.colorScheme, reduceAnimation, knownUpFront) {
        if (target.colorScheme == settledScheme && schemeProgress.value == 1f) return@LaunchedEffect
        if (reduceAnimation || knownUpFront) {
            startScheme = target.colorScheme
            settledScheme = target.colorScheme
            schemeProgress.snapTo(1f)
        } else {
            startScheme = lerpColorScheme(startScheme, settledScheme, schemeProgress.value)
            settledScheme = target.colorScheme
            schemeProgress.snapTo(0f)
            schemeProgress.animateTo(1f, tween(TINT_FADE_MS))
        }
    }
    val animatedScheme = if (reduceAnimation || knownUpFront || schemeProgress.value >= 1f) {
        target.colorScheme
    } else {
        lerpColorScheme(startScheme, settledScheme, schemeProgress.value)
    }

    return ArtworkPalette(
        background = animateColorAsState(target.background, spec, label = "tintBackground").value,
        wash = animateColorAsState(target.wash, spec, label = "tintWash").value,
        elevated = animateColorAsState(target.elevated, spec, label = "tintElevated").value,
        accent = animateColorAsState(target.accent, spec, label = "tintAccent").value,
        onBackground = animateColorAsState(target.onBackground, spec, label = "tintOn").value,
        onBackgroundVariant = animateColorAsState(
            target.onBackgroundVariant, spec, label = "tintOnVariant",
        ).value,
        divider = animateColorAsState(target.divider, spec, label = "tintDivider").value,
        colorScheme = animatedScheme,
        targetColorScheme = target.colorScheme,
    )
}

/**
 * Reads top-band relative luminance from the cached palette decode.
 * Returns raw artwork luminance without applying top scrim calculations.
 */
@Composable
fun rememberArtworkTopBandLuminance(
    imageUrl: String?,
    artPx: Int = CARD_ART_PX,
): Float? = rememberArtworkSeed(imageUrl, artPx)?.topBandLuminance

@Composable
private fun rememberArtworkSeed(imageUrl: String?, artPx: Int): Seed? {
    val context = LocalPlatformContext.current
    var seed by remember(imageUrl) { mutableStateOf(imageUrl?.let(::cachedArtworkSeed)) }

    LaunchedEffect(imageUrl, artPx) {
        if (imageUrl == null || seed != null) return@LaunchedEffect
        val found = loadOrReuseArtworkSeed(imageUrl) {
            val request = ImageRequest.Builder(context)
                .data(imageUrl.artworkAt(artPx))
                .size(PALETTE_PX)
                .forPixelAccess()
                .build()
            val result = SingletonImageLoader.get(context).execute(request)
            val bitmap = (result as? SuccessResult)?.image?.toImageBitmap() ?: return@loadOrReuseArtworkSeed null
            withContext(Dispatchers.Default) { seedOf(bitmap) }
        } ?: return@LaunchedEffect
        seed = found
    }
    return seed
}

/**
 * Colours already read, keyed by artwork URL.
 *
 * Reading them again costs a decode and a quantise for an answer that cannot
 * have changed — the artwork at a URL is the artwork at that URL. Guarded by
 * [seedCacheLock] together with [inFlightSeeds] so concurrent composables
 * mounting on the same frame share one decode and reuse the 128-entry LRU cache.
 */
private val seedCacheLock = Any()

private val seedCache = object : LinkedHashMap<String, Seed>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Seed>) = size > SEED_CACHE_ENTRIES
}

private val inFlightSeeds = HashMap<String, Deferred<Seed?>>()

private val seedDecodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

internal fun cachedArtworkSeed(imageUrl: String): Seed? =
    synchronized(seedCacheLock) { seedCache[imageUrl] }

internal suspend fun loadOrReuseArtworkSeed(
    imageUrl: String,
    loader: suspend () -> Seed?,
): Seed? {
    val deferred: Deferred<Seed?> = synchronized(seedCacheLock) {
        seedCache[imageUrl]?.let { return it }
        inFlightSeeds[imageUrl] ?: seedDecodeScope.async {
            try {
                val decoded = loader()
                if (decoded != null) {
                    synchronized(seedCacheLock) {
                        seedCache[imageUrl] = decoded
                    }
                }
                decoded
            } finally {
                synchronized(seedCacheLock) {
                    inFlightSeeds.remove(imageUrl)
                }
            }
        }.also { inFlightSeeds[imageUrl] = it }
    }
    return deferred.await()
}

/** Deep enough to cover a session's browsing without holding a screenful of colours. */
internal const val SEED_CACHE_ENTRIES = 128

private const val PALETTE_PX = 128

/** Short: this is a surface settling into its colour, not an effect in itself. */
private const val TINT_FADE_MS = 260

/**
 * The raw artwork colours: what the page is mostly made of, its brightest
 * note, and what its bottom edge averages out to.
 */
internal data class Seed(
    val dominant: Color,
    val vibrant: Color,
    val edge: Color,
    val topBandLuminance: Float,
)

private fun seedOf(bitmap: ImageBitmap): Seed? {
    fun swatches(clearFilters: Boolean) =
        paletteSwatches(bitmap, SWATCH_COUNT, clearFilters)

    // The default filter deliberately throws away near-black and near-white.
    // That is useful while looking for an accent, but it is the wrong answer
    // to "what colour is this page mostly made of?" A dark photograph would
    // otherwise be reduced to whatever warm face or tiny coloured detail
    // survived the filter, and a monochrome sleeve to an anti-aliased fringe.
    // Both cases used to turn into the same maroon page.
    val all = swatches(clearFilters = true)
    if (all.isEmpty()) return null
    val accentCandidates = swatches(clearFilters = false).ifEmpty { all }

    val dominant = all.maxBy { it.population }
    // The accent has to earn its place twice over: a colour nobody sees enough
    // of reads as arbitrary, and a grey one isn't an accent at all. Scoring on
    // saturation against the *square root* of population is what stops a sleeve
    // that is four-fifths black sky from accenting in black.
    val vibrant = accentCandidates.maxBy { swatch ->
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(swatch.rgb, it) }
        hsl[1] * sqrt(swatch.population.toFloat())
    }
    return Seed(
        dominant = Color(dominant.rgb),
        vibrant = Color(vibrant.rgb),
        edge = bitmap.bottomEdgeColor(),
        topBandLuminance = bitmap.topBandRelativeLuminance(),
    )
}

/**
 * A seed with every note taken from the supplied colours: the page is the
 * background from top to edge, and the accent is the supplied one.
 */
private fun ArtworkKeyColors.toSeed() = Seed(
    dominant = background,
    vibrant = accent,
    edge = background,
    topBandLuminance = relativeLuminance(background.toArgb()),
)

private const val SWATCH_COUNT = 24

/**
 * The mean of the artwork's bottom band — what a blur wide enough to lose the
 * picture leaves behind at that edge.
 *
 * A flat mean rather than a quantised swatch on purpose: a blur has no notion
 * of which colour is *important*, and the page under the artwork has to match
 * what the blur actually produced, not what the picture is about.
 */
private fun ImageBitmap.bottomEdgeColor(): Color {
    val band = (height * EDGE_BAND).toInt().coerceIn(1, height)
    val pixels = IntArray(width * band)
    readPixels(pixels, startX = 0, startY = height - band, width = width, height = band)

    var red = 0L
    var green = 0L
    var blue = 0L
    pixels.forEach { pixel ->
        red += (pixel shr 16) and 0xFF
        green += (pixel shr 8) and 0xFF
        blue += pixel and 0xFF
    }
    val count = pixels.size.coerceAtLeast(1)
    return Color(
        red = (red / count).toInt(),
        green = (green / count).toInt(),
        blue = (blue / count).toInt(),
    )
}

/** How much of the artwork's height the edge colour is read from. */
private const val EDGE_BAND = 0.18f

/**
 * The status inset occupies only the upper sliver of the full-bleed hero on a
 * phone. Keep this tight so titles or faces lower in the cover do not decide
 * the icon colour for pixels that are never behind the system bar.
 */
private const val TOP_BAND = 0.10f

private fun ImageBitmap.topBandRelativeLuminance(): Float {
    val band = (height * TOP_BAND).toInt().coerceIn(1, height)
    val pixels = IntArray(width * band)
    readPixels(pixels, startX = 0, startY = 0, width = width, height = band)
    return averageRelativeLuminance(pixels)
}

/** WCAG relative luminance: average in linear light, never gamma-encoded RGB. */
internal fun averageRelativeLuminance(pixels: IntArray): Float {
    if (pixels.isEmpty()) return 0f
    return pixels.sumOf { relativeLuminance(it).toDouble() }.div(pixels.size).toFloat()
}

internal fun relativeLuminance(argb: Int): Float {
    fun linear(channel: Int): Float {
        val srgb = channel / 255f
        return if (srgb <= 0.04045f) srgb / 12.92f else ((srgb + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }
    return 0.2126f * linear((argb shr 16) and 0xFF) +
        0.7152f * linear((argb shr 8) and 0xFF) +
        0.0722f * linear(argb and 0xFF)
}

/**
 * Maps artwork luminance to top scrim opacity to keep white status bar
 * icons legible over light album covers while staying subtle on dark ones.
 */
internal fun topBandScrimAlpha(artworkLuminance: Float?): Float {
    val luminance = artworkLuminance?.coerceIn(0f, 1f) ?: 0f
    return PLAYER_STATUS_SCRIM_MIN_ALPHA +
        (PLAYER_STATUS_SCRIM_MAX_ALPHA - PLAYER_STATUS_SCRIM_MIN_ALPHA) * luminance
}

private const val PLAYER_STATUS_SCRIM_MIN_ALPHA = 0.16f
private const val PLAYER_STATUS_SCRIM_MAX_ALPHA = 0.65f

private fun Seed.toPalette(dark: Boolean): ArtworkPalette = if (dark) {
    ArtworkPalette(
        // Deep enough that white body text clears contrast on any sleeve, but
        // not so deep the hue is gone — the whole point is that the page is
        // recognisably *this* record's colour.
        background = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.20f, maximum = 0.62f) },
            lightness = { 0.13f },
        ),
        // Follows the edge's own brightness within a band that stays clear of
        // white body text at the top and of [background] at the bottom: a
        // sleeve that ends dark hands over almost invisibly, one that ends
        // bright leaves a page that is visibly lit from under the artwork.
        wash = edge.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.18f, maximum = 0.58f) },
            lightness = { it.coerceIn(0.14f, 0.24f) },
        ),
        elevated = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.20f, maximum = 0.62f) },
            lightness = { 0.22f },
        ),
        accent = vibrant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.55f, maximum = 1f) },
            lightness = { it.coerceIn(0.62f, 0.78f) },
        ),
        onBackground = Color.White,
        // A pale tint of the page's own hue rather than a translucent white —
        // Apple Music's secondary lines are the sleeve's colour, lifted, so a
        // blue record's credits read light blue on its blue page. Bright
        // enough that it still clears contrast on [background].
        onBackgroundVariant = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.22f, maximum = 0.50f) },
            lightness = { 0.80f },
        ),
        divider = Color.White.copy(alpha = 0.12f),
        colorScheme = deriveArtworkColorScheme(dominant = dominant, vibrant = vibrant, edge = edge, dark = true),
    )
} else {
    ArtworkPalette(
        background = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.50f) },
            lightness = { 0.91f },
        ),
        wash = edge.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.12f, maximum = 0.46f) },
            lightness = { it.coerceIn(0.78f, 0.90f) },
        ),
        elevated = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.50f) },
            lightness = { 0.83f },
        ),
        accent = vibrant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.55f, maximum = 1f) },
            lightness = { it.coerceIn(0.30f, 0.44f) },
        ),
        onBackground = Color.Black,
        // The same hue taken down rather than up, for the pale page.
        onBackgroundVariant = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.22f, maximum = 0.55f) },
            lightness = { 0.34f },
        ),
        divider = Color.Black.copy(alpha = 0.10f),
        colorScheme = deriveArtworkColorScheme(dominant = dominant, vibrant = vibrant, edge = edge, dark = false),
    )
}

/**
 * Derives a complete 36-role contrast-clamped Material 3 [ColorScheme] from
 * [dominant], [vibrant], and [edge] artwork swatches.
 *
 * When both [dominant] and [vibrant] fall below [CHROMATIC_SATURATION_THRESHOLD],
 * returns [BitChordDarkColorScheme] or [BitChordLightColorScheme] unchanged so
 * monochrome sleeves stay strictly neutral without inventing warm/red/brown tints.
 */
internal fun deriveArtworkColorScheme(
    dominant: Color,
    vibrant: Color,
    edge: Color,
    dark: Boolean,
): ColorScheme {
    val dHsl = FloatArray(3).also { ColorUtils.colorToHSL(dominant.toArgb(), it) }
    val vHsl = FloatArray(3).also { ColorUtils.colorToHSL(vibrant.toArgb(), it) }
    val eHsl = FloatArray(3).also { ColorUtils.colorToHSL(edge.toArgb(), it) }

    val maxChroma = maxOf(dHsl[1], vHsl[1])
    if (maxChroma < CHROMATIC_SATURATION_THRESHOLD) {
        return if (dark) BitChordDarkColorScheme else BitChordLightColorScheme
    }

    val primarySeed = if (vHsl[1] >= CHROMATIC_SATURATION_THRESHOLD) vibrant else dominant
    val secondarySeed = if (dHsl[1] >= CHROMATIC_SATURATION_THRESHOLD) dominant else primarySeed
    val tertiarySeed = when {
        eHsl[1] >= CHROMATIC_SATURATION_THRESHOLD -> edge
        dHsl[1] >= CHROMATIC_SATURATION_THRESHOLD -> dominant
        else -> primarySeed
    }
    val surfaceSeed = if (dHsl[1] >= CHROMATIC_SATURATION_THRESHOLD) dominant else primarySeed
    val containerSeed = if (eHsl[1] >= CHROMATIC_SATURATION_THRESHOLD) edge else surfaceSeed

    return if (dark) {
        val background = surfaceSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.16f, maximum = 0.48f) },
            lightness = { 0.07f },
        )
        val surfaceLowest = surfaceSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.42f) },
            lightness = { 0.05f },
        )
        val surfaceDim = surfaceSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.16f, maximum = 0.46f) },
            lightness = { 0.08f },
        )
        val surface = surfaceSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.18f, maximum = 0.52f) },
            lightness = { 0.10f },
        )
        val surfaceLow = containerSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.18f, maximum = 0.50f) },
            lightness = { 0.12f },
        )
        val surfaceContainer = containerSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.18f, maximum = 0.52f) },
            lightness = { 0.14f },
        )
        val surfaceHigh = containerSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.20f, maximum = 0.54f) },
            lightness = { 0.17f },
        )
        val surfaceHighest = containerSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.20f, maximum = 0.54f) },
            lightness = { 0.20f },
        )
        val surfaceVariant = surfaceSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.20f, maximum = 0.54f) },
            lightness = { 0.18f },
        )
        val surfaceBright = containerSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.18f, maximum = 0.48f) },
            lightness = { 0.23f },
        )
        val allDarkSurfaces = listOf(
            background,
            surfaceLowest,
            surfaceDim,
            surface,
            surfaceLow,
            surfaceContainer,
            surfaceHigh,
            surfaceHighest,
            surfaceVariant,
        )

        val primary = clampColorContrast(
            foreground = primarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.52f, maximum = 0.92f) },
                lightness = { it.coerceIn(0.68f, 0.80f) },
            ),
            backgrounds = allDarkSurfaces,
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )
        val onPrimary = clampColorContrast(
            foreground = primarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.18f, maximum = 0.45f) },
                lightness = { 0.08f },
            ),
            backgrounds = listOf(primary),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )
        val primaryContainer = primarySeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.28f, maximum = 0.62f) },
            lightness = { 0.24f },
        )
        val onPrimaryContainer = clampColorContrast(
            foreground = primarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.35f, maximum = 0.85f) },
                lightness = { 0.90f },
            ),
            backgrounds = listOf(primaryContainer),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )

        val secondary = clampColorContrast(
            foreground = secondarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.24f, maximum = 0.58f) },
                lightness = { 0.76f },
            ),
            backgrounds = allDarkSurfaces,
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )
        val onSecondary = clampColorContrast(
            foreground = secondarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.16f, maximum = 0.40f) },
                lightness = { 0.09f },
            ),
            backgrounds = listOf(secondary),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )
        val secondaryContainer = secondarySeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.22f, maximum = 0.52f) },
            lightness = { 0.22f },
        )
        val onSecondaryContainer = clampColorContrast(
            foreground = secondarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.24f, maximum = 0.65f) },
                lightness = { 0.90f },
            ),
            backgrounds = listOf(secondaryContainer),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )

        val tertiary = clampColorContrast(
            foreground = tertiarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.28f, maximum = 0.68f) },
                lightness = { 0.76f },
            ),
            backgrounds = allDarkSurfaces,
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )
        val onTertiary = clampColorContrast(
            foreground = tertiarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.16f, maximum = 0.40f) },
                lightness = { 0.09f },
            ),
            backgrounds = listOf(tertiary),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )
        val tertiaryContainer = tertiarySeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.24f, maximum = 0.56f) },
            lightness = { 0.23f },
        )
        val onTertiaryContainer = clampColorContrast(
            foreground = tertiarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.28f, maximum = 0.70f) },
                lightness = { 0.90f },
            ),
            backgrounds = listOf(tertiaryContainer),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )

        val onBackground = clampColorContrast(
            foreground = Color.White,
            backgrounds = listOf(background),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )
        val onSurface = clampColorContrast(
            foreground = Color.White,
            backgrounds = allDarkSurfaces,
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )
        val onSurfaceVariant = clampColorContrast(
            foreground = surfaceSeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.16f, maximum = 0.42f) },
                lightness = { 0.78f },
            ),
            backgrounds = allDarkSurfaces,
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )
        val inverseSurface = surfaceSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.10f, maximum = 0.28f) },
            lightness = { 0.93f },
        )
        val inverseOnSurface = clampColorContrast(
            foreground = surfaceSeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.34f) },
                lightness = { 0.10f },
            ),
            backgrounds = listOf(inverseSurface),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )
        val inversePrimary = clampColorContrast(
            foreground = primarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.48f, maximum = 0.88f) },
                lightness = { 0.34f },
            ),
            backgrounds = listOf(inverseSurface),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )
        val error = clampColorContrast(
            foreground = BitChordDarkColorScheme.error,
            backgrounds = allDarkSurfaces,
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )
        val onError = clampColorContrast(
            foreground = BitChordDarkColorScheme.onError,
            backgrounds = listOf(error),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )
        val outline = clampColorContrast(
            foreground = surfaceSeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.36f) },
                lightness = { 0.46f },
            ),
            backgrounds = listOf(background, surface, surfaceContainer),
            minContrast = WCAG_AA_UI_CONTRAST,
            preferLighter = true,
        )
        val outlineVariant = surfaceSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.36f) },
            lightness = { 0.26f },
        )

        ColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            error = error,
            onError = onError,
            errorContainer = BitChordDarkColorScheme.errorContainer,
            onErrorContainer = BitChordDarkColorScheme.onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color.Black,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceHigh,
            surfaceContainerHighest = surfaceHighest,
            surfaceContainerLow = surfaceLow,
            surfaceContainerLowest = surfaceLowest,
        )
    } else {
        val background = surfaceSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.12f, maximum = 0.42f) },
            lightness = { 0.96f },
        )
        val surfaceLowest = Color.White
        val surfaceBright = surfaceSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.10f, maximum = 0.36f) },
            lightness = { 0.98f },
        )
        val surface = surfaceSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.12f, maximum = 0.42f) },
            lightness = { 0.94f },
        )
        val surfaceLow = containerSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.12f, maximum = 0.40f) },
            lightness = { 0.93f },
        )
        val surfaceContainer = containerSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.44f) },
            lightness = { 0.90f },
        )
        val surfaceHigh = containerSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.46f) },
            lightness = { 0.87f },
        )
        val surfaceHighest = containerSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.16f, maximum = 0.48f) },
            lightness = { 0.84f },
        )
        val surfaceVariant = surfaceSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.46f) },
            lightness = { 0.88f },
        )
        val surfaceDim = surfaceSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.44f) },
            lightness = { 0.83f },
        )
        val allLightSurfaces = listOf(
            background,
            surfaceLowest,
            surfaceBright,
            surfaceDim,
            surface,
            surfaceLow,
            surfaceContainer,
            surfaceHigh,
            surfaceHighest,
            surfaceVariant,
        )

        val primary = clampColorContrast(
            foreground = primarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.52f, maximum = 0.92f) },
                lightness = { it.coerceIn(0.26f, 0.38f) },
            ),
            backgrounds = allLightSurfaces,
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )
        val onPrimary = clampColorContrast(
            foreground = Color.White,
            backgrounds = listOf(primary),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )
        val primaryContainer = primarySeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.24f, maximum = 0.60f) },
            lightness = { 0.84f },
        )
        val onPrimaryContainer = clampColorContrast(
            foreground = primarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.35f, maximum = 0.80f) },
                lightness = { 0.16f },
            ),
            backgrounds = listOf(primaryContainer),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )

        val secondary = clampColorContrast(
            foreground = secondarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.22f, maximum = 0.55f) },
                lightness = { 0.30f },
            ),
            backgrounds = allLightSurfaces,
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )
        val onSecondary = clampColorContrast(
            foreground = Color.White,
            backgrounds = listOf(secondary),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )
        val secondaryContainer = secondarySeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.20f, maximum = 0.50f) },
            lightness = { 0.85f },
        )
        val onSecondaryContainer = clampColorContrast(
            foreground = secondarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.24f, maximum = 0.60f) },
                lightness = { 0.16f },
            ),
            backgrounds = listOf(secondaryContainer),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )

        val tertiary = clampColorContrast(
            foreground = tertiarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.26f, maximum = 0.64f) },
                lightness = { 0.30f },
            ),
            backgrounds = allLightSurfaces,
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )
        val onTertiary = clampColorContrast(
            foreground = Color.White,
            backgrounds = listOf(tertiary),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )
        val tertiaryContainer = tertiarySeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.22f, maximum = 0.54f) },
            lightness = { 0.85f },
        )
        val onTertiaryContainer = clampColorContrast(
            foreground = tertiarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.28f, maximum = 0.66f) },
                lightness = { 0.16f },
            ),
            backgrounds = listOf(tertiaryContainer),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )

        val onBackground = clampColorContrast(
            foreground = Color.Black,
            backgrounds = listOf(background),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )
        val onSurface = clampColorContrast(
            foreground = Color.Black,
            backgrounds = allLightSurfaces,
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )
        val onSurfaceVariant = clampColorContrast(
            foreground = surfaceSeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.18f, maximum = 0.48f) },
                lightness = { 0.28f },
            ),
            backgrounds = allLightSurfaces,
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )
        val inverseSurface = surfaceSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.34f) },
            lightness = { 0.14f },
        )
        val inverseOnSurface = clampColorContrast(
            foreground = surfaceSeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.12f, maximum = 0.30f) },
                lightness = { 0.94f },
            ),
            backgrounds = listOf(inverseSurface),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )
        val inversePrimary = clampColorContrast(
            foreground = primarySeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.50f, maximum = 0.90f) },
                lightness = { 0.74f },
            ),
            backgrounds = listOf(inverseSurface),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )
        val error = clampColorContrast(
            foreground = BitChordLightColorScheme.error,
            backgrounds = allLightSurfaces,
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = false,
        )
        val onError = clampColorContrast(
            foreground = BitChordLightColorScheme.onError,
            backgrounds = listOf(error),
            minContrast = WCAG_AA_TEXT_CONTRAST,
            preferLighter = true,
        )
        val outline = clampColorContrast(
            foreground = surfaceSeed.withHsl(
                saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.38f) },
                lightness = { 0.45f },
            ),
            backgrounds = listOf(background, surface, surfaceContainer),
            minContrast = WCAG_AA_UI_CONTRAST,
            preferLighter = false,
        )
        val outlineVariant = surfaceSeed.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.12f, maximum = 0.36f) },
            lightness = { 0.78f },
        )

        ColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            error = error,
            onError = onError,
            errorContainer = BitChordLightColorScheme.errorContainer,
            onErrorContainer = BitChordLightColorScheme.onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color.Black,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceHigh,
            surfaceContainerHighest = surfaceHighest,
            surfaceContainerLow = surfaceLow,
            surfaceContainerLowest = surfaceLowest,
        )
    }
}

/**
 * Keeps neutral artwork neutral instead of inventing a hue for it.
 *
 * HSL represents grey with hue zero. Raising that grey to a saturation floor
 * therefore does not make it "more colourful"; it manufactures red, which
 * becomes brown/maroon once the page lightness is lowered. A small real amount
 * of colour is kept as-is, while an unmistakably chromatic swatch can still be
 * strengthened enough to make controls legible and the page recognisable.
 */
internal fun adaptedArtworkSaturation(source: Float, minimum: Float, maximum: Float): Float {
    val saturation = source.coerceIn(0f, 1f)
    return if (saturation < CHROMATIC_SATURATION_THRESHOLD) {
        saturation
    } else {
        saturation.coerceIn(minimum, maximum)
    }
}

/** Below this, boosting saturation makes quantisation noise visible as a tint. */
private const val CHROMATIC_SATURATION_THRESHOLD = 0.12f

private fun Color.withHsl(
    saturation: (Float) -> Float = { it },
    lightness: (Float) -> Float = { it },
): Color {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }
    hsl[1] = saturation(hsl[1]).coerceIn(0f, 1f)
    hsl[2] = lightness(hsl[2]).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Perceived brightness, used only to tell a dark theme from a light one. */
private fun Color.luminance(): Float = ColorUtils.calculateLuminance(toArgb()).toFloat()
