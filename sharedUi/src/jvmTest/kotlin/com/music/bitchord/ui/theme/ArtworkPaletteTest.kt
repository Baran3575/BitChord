package com.music.bitchord.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.ui.graphics.ColorUtils
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPaletteTest {
    @Test
    fun grayscaleDoesNotAcquireTheDefaultRedHue() {
        assertEquals(0f, adaptedArtworkSaturation(0f, minimum = 0.20f, maximum = 0.62f), 0f)
        assertEquals(0f, adaptedArtworkSaturation(0f, minimum = 0.55f, maximum = 1f), 0f)
    }

    @Test
    fun nearlyNeutralArtworkKeepsItsSubtleSaturation() {
        assertEquals(0.08f, adaptedArtworkSaturation(0.08f, minimum = 0.20f, maximum = 0.62f), 0f)
    }

    @Test
    fun genuinelyChromaticArtworkIsStillStrengthenedAndCapped() {
        assertEquals(0.55f, adaptedArtworkSaturation(0.25f, minimum = 0.55f, maximum = 1f), 0f)
        assertEquals(0.62f, adaptedArtworkSaturation(0.90f, minimum = 0.20f, maximum = 0.62f), 0f)
    }

    @Test
    fun neutralColorSchemesSpecifyAll36RolesWithoutStockMaterialPurpleOrPink() {
        val stockMaterialDefaults = setOf(
            Color(0xFFD0BCFF),
            Color(0xFFCCC2DC),
            Color(0xFFEFB8C8),
            Color(0xFF4F378B),
            Color(0xFF332D41),
            Color(0xFF633B48),
            Color(0xFF141218),
            Color(0xFF1D1B20),
            Color(0xFF211F26),
            Color(0xFF2B2930),
            Color(0xFF36343B),
            Color(0xFF6650A4),
            Color(0xFF625B71),
            Color(0xFF7D5260),
            Color(0xFFEADDFF),
            Color(0xFFE8DEF8),
            Color(0xFFFFD8E4),
            Color(0xFFFEF7FF),
            Color(0xFFF3EDF7),
            Color(0xFFECE6F0),
            Color(0xFFE6E0E9),
        )

        val darkRoles = BitChordDarkColorScheme.allRoles()
        val lightRoles = BitChordLightColorScheme.allRoles()
        assertEquals(36, darkRoles.size)
        assertEquals(36, lightRoles.size)

        darkRoles.forEach { (role, color) ->
            assertFalse("Dark role '$role' leaked stock M3 color $color", color in stockMaterialDefaults)
            if (!role.contains("error", ignoreCase = true)) {
                val hsl = FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }
                assertTrue("Dark non-error role '$role' should stay neutral monochrome, got s=${hsl[1]}", hsl[1] <= 0.08f)
            }
        }
        lightRoles.forEach { (role, color) ->
            assertFalse("Light role '$role' leaked stock M3 color $color", color in stockMaterialDefaults)
            if (!role.contains("error", ignoreCase = true)) {
                val hsl = FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }
                assertTrue("Light non-error role '$role' should stay neutral monochrome, got s=${hsl[1]}", hsl[1] <= 0.08f)
            }
        }

        assertSchemeMeetsWcagAa("BitChordDarkColorScheme", BitChordDarkColorScheme)
        assertSchemeMeetsWcagAa("BitChordLightColorScheme", BitChordLightColorScheme)
    }

    @Test
    fun monochromeAndLowSaturationArtworkReturnsStrictNeutralSchemesWithoutRedOrBrownTint() {
        val neutralInputs = listOf(
            Triple(Color(0xFF000000), Color(0xFF000000), Color(0xFF000000)),
            Triple(Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFFFFFFFF)),
            Triple(Color(0xFF7A7A7A), Color(0xFF8C8C8C), Color(0xFF606060)),
            Triple(Color(0xFF544E4E), Color(0xFF615A5A), Color(0xFF474242)), // s < 0.12f
        )

        for ((dominant, vibrant, edge) in neutralInputs) {
            assertSame(
                BitChordDarkColorScheme,
                deriveArtworkColorScheme(dominant = dominant, vibrant = vibrant, edge = edge, dark = true),
            )
            assertSame(
                BitChordLightColorScheme,
                deriveArtworkColorScheme(dominant = dominant, vibrant = vibrant, edge = edge, dark = false),
            )
        }
    }

    @Test
    fun artworkColorSchemeMeetsWcagAaAcrossSaturatedPaleAndDarkSleevesInBothModes() {
        val sleeves = listOf(
            "vivid-crimson" to Triple(Color(0xFF8C1022), Color(0xFFFF1E42), Color(0xFF450711)),
            "neon-lime-yellow" to Triple(Color(0xFFE8FF2B), Color(0xFFD4FF00), Color(0xFFF5FF9E)),
            "deep-cobalt-blue" to Triple(Color(0xFF0A1B4F), Color(0xFF1D5BFF), Color(0xFF061030)),
            "pale-cream-peach" to Triple(Color(0xFFFFF6EA), Color(0xFFFF8C42), Color(0xFFF7E6D4)),
            "electric-cyan-teal" to Triple(Color(0xFF084B54), Color(0xFF00F0FF), Color(0xFF062C33)),
            "vivid-magenta-violet" to Triple(Color(0xFF3E1159), Color(0xFFE13DFF), Color(0xFF220833)),
            "dark-forest-emerald" to Triple(Color(0xFF071F14), Color(0xFF1ED760), Color(0xFF04120B)),
        )

        for ((name, swatches) in sleeves) {
            val (dominant, vibrant, edge) = swatches
            val darkScheme = deriveArtworkColorScheme(dominant = dominant, vibrant = vibrant, edge = edge, dark = true)
            val lightScheme = deriveArtworkColorScheme(dominant = dominant, vibrant = vibrant, edge = edge, dark = false)

            assertEquals(36, darkScheme.allRoles().size)
            assertEquals(36, lightScheme.allRoles().size)
            assertNotEquals("Chromatic sleeve '$name' should harmonize dark primary", BitChordDarkColorScheme.primary, darkScheme.primary)
            assertNotEquals("Chromatic sleeve '$name' should harmonize light primary", BitChordLightColorScheme.primary, lightScheme.primary)

            assertSchemeMeetsWcagAa("$name (dark / forced-dark player)", darkScheme)
            assertSchemeMeetsWcagAa("$name (light)", lightScheme)
        }
    }

    @Test
    fun lerpColorSchemeInterpolatesAll36RolesSmoothly() {
        val start = BitChordDarkColorScheme
        val stop = deriveArtworkColorScheme(
            dominant = Color(0xFF143566),
            vibrant = Color(0xFF3D8BFF),
            edge = Color(0xFF0C2040),
            dark = true,
        )

        assertSame(start, lerpColorScheme(start, stop, 0f))
        assertSame(stop, lerpColorScheme(start, stop, 1f))

        val mid = lerpColorScheme(start, stop, 0.5f)
        assertEquals(36, mid.allRoles().size)
        assertNotEquals(start.primary, mid.primary)
        assertNotEquals(stop.primary, mid.primary)
    }

    @Test
    fun discordPresenceColorsAreDistinctAndIndependentFromArtworkSchemes() {
        val statuses = setOf(
            DiscordPresenceColors.Online,
            DiscordPresenceColors.Idle,
            DiscordPresenceColors.DoNotDisturb,
            DiscordPresenceColors.Offline,
        )
        assertEquals(4, statuses.size)
        assertEquals(Color(0xFF23A55A), DiscordPresenceColors.Online)
        assertEquals(Color(0xFFF0B232), DiscordPresenceColors.Idle)
        assertEquals(Color(0xFFF23F43), DiscordPresenceColors.DoNotDisturb)
        assertEquals(Color(0xFF80848E), DiscordPresenceColors.Offline)
    }

    @Test
    fun forcedDarkPlayerSchemeKeepsQueueCheckboxesLegibleEvenOnBrightWhiteAndPastelSleeves() {
        val brightSleeves = listOf(
            "pure-white" to Triple(Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFFFFFFFF)),
            "pastel-lemon" to Triple(Color(0xFFFFFBE0), Color(0xFFFFEA00), Color(0xFFFFF8C4)),
            "pale-sky" to Triple(Color(0xFFEAF6FF), Color(0xFF42A5F5), Color(0xFFD6EEFF)),
        )

        for ((name, swatches) in brightSleeves) {
            val (dominant, vibrant, edge) = swatches
            // NowPlayingScreen passes dark = true even when the app is in light mode.
            val playerScheme = deriveArtworkColorScheme(
                dominant = dominant,
                vibrant = vibrant,
                edge = edge,
                dark = true,
            )
            val surfaceLum = ColorUtils.calculateLuminance(playerScheme.surface.toArgb())
            assertTrue("$name forced-dark player surface must stay dark, got lum=$surfaceLum", surfaceLum < 0.12)

            // Checked checkbox fill (primary) and checkmark (onPrimary), plus unchecked border (onSurfaceVariant / outline).
            assertContrastAtLeast(name, "checked box primary vs surface", playerScheme.primary, playerScheme.surface, WCAG_AA_TEXT_CONTRAST)
            assertContrastAtLeast(name, "checkmark onPrimary vs primary", playerScheme.onPrimary, playerScheme.primary, WCAG_AA_TEXT_CONTRAST)
            assertContrastAtLeast(name, "unchecked box onSurfaceVariant vs surface", playerScheme.onSurfaceVariant, playerScheme.surface, WCAG_AA_TEXT_CONTRAST)
            assertContrastAtLeast(name, "unchecked box outline vs surface", playerScheme.outline, playerScheme.surface, WCAG_AA_UI_CONTRAST)
        }
    }

    @Test
    fun seedCacheDeduplicatesConcurrentLoadsAndEnforces128EntryLruBound() = runBlocking {
        val decodes = AtomicInteger(0)
        val url = "https://example.com/test-concurrent-cover.jpg"
        val expected = Seed(
            dominant = Color(0xFF143566),
            vibrant = Color(0xFF3D8BFF),
            edge = Color(0xFF0C2040),
            topBandLuminance = 0.18f,
        )

        // Simulate 6 concurrent callers (e.g. NowPlayingScreen + topBandLuminance + playerMenuPalette)
        // requesting the same uncached URL on the same frame: must perform exactly 1 decode.
        val results = (1..6).map {
            async {
                loadOrReuseArtworkSeed(url) {
                    decodes.incrementAndGet()
                    delay(25)
                    expected
                }
            }
        }.awaitAll()

        assertEquals(1, decodes.get())
        results.forEach { assertSame(expected, it) }
        assertSame(expected, cachedArtworkSeed(url))

        // Subsequent cached reads perform zero additional decodes.
        val cachedHit = loadOrReuseArtworkSeed(url) {
            decodes.incrementAndGet()
            expected
        }
        assertEquals(1, decodes.get())
        assertSame(expected, cachedHit)

        // Populate 130 entries and touch entry #0 mid-way to verify 128-entry LRU eviction.
        val prefix = "https://example.com/lru-entry-"
        for (i in 0 until SEED_CACHE_ENTRIES) {
            loadOrReuseArtworkSeed("$prefix$i") { expected }
        }
        // Touch entry 0 so entry 1 becomes eldest.
        assertNotNull(cachedArtworkSeed("${prefix}0"))
        loadOrReuseArtworkSeed("${prefix}overflow") { expected }

        assertNotNull("Recently accessed entry 0 must remain in 128-entry LRU cache", cachedArtworkSeed("${prefix}0"))
        assertNull("Least-recently-used entry 1 should be evicted past 128 entries", cachedArtworkSeed("${prefix}1"))
    }

    private fun assertSchemeMeetsWcagAa(label: String, scheme: ColorScheme) {
        val surfaces = listOf(
            "background" to scheme.background,
            "surface" to scheme.surface,
            "surfaceVariant" to scheme.surfaceVariant,
            "surfaceDim" to scheme.surfaceDim,
            "surfaceContainerLowest" to scheme.surfaceContainerLowest,
            "surfaceContainerLow" to scheme.surfaceContainerLow,
            "surfaceContainer" to scheme.surfaceContainer,
            "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            "surfaceContainerHighest" to scheme.surfaceContainerHighest,
        )

        for ((surfaceName, surfaceColor) in surfaces) {
            assertContrastAtLeast(label, "onSurface vs $surfaceName", scheme.onSurface, surfaceColor, WCAG_AA_TEXT_CONTRAST)
            assertContrastAtLeast(label, "onSurfaceVariant vs $surfaceName", scheme.onSurfaceVariant, surfaceColor, WCAG_AA_TEXT_CONTRAST)
            assertContrastAtLeast(label, "primary vs $surfaceName", scheme.primary, surfaceColor, WCAG_AA_TEXT_CONTRAST)
            assertContrastAtLeast(label, "secondary vs $surfaceName", scheme.secondary, surfaceColor, WCAG_AA_TEXT_CONTRAST)
            assertContrastAtLeast(label, "tertiary vs $surfaceName", scheme.tertiary, surfaceColor, WCAG_AA_TEXT_CONTRAST)
            assertContrastAtLeast(label, "error vs $surfaceName", scheme.error, surfaceColor, WCAG_AA_TEXT_CONTRAST)
        }

        assertContrastAtLeast(label, "onBackground vs background", scheme.onBackground, scheme.background, WCAG_AA_TEXT_CONTRAST)
        assertContrastAtLeast(label, "onPrimary vs primary", scheme.onPrimary, scheme.primary, WCAG_AA_TEXT_CONTRAST)
        assertContrastAtLeast(label, "onPrimaryContainer vs primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer, WCAG_AA_TEXT_CONTRAST)
        assertContrastAtLeast(label, "onSecondary vs secondary", scheme.onSecondary, scheme.secondary, WCAG_AA_TEXT_CONTRAST)
        assertContrastAtLeast(label, "onSecondaryContainer vs secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer, WCAG_AA_TEXT_CONTRAST)
        assertContrastAtLeast(label, "onTertiary vs tertiary", scheme.onTertiary, scheme.tertiary, WCAG_AA_TEXT_CONTRAST)
        assertContrastAtLeast(label, "onTertiaryContainer vs tertiaryContainer", scheme.onTertiaryContainer, scheme.tertiaryContainer, WCAG_AA_TEXT_CONTRAST)
        assertContrastAtLeast(label, "inverseOnSurface vs inverseSurface", scheme.inverseOnSurface, scheme.inverseSurface, WCAG_AA_TEXT_CONTRAST)
        assertContrastAtLeast(label, "inversePrimary vs inverseSurface", scheme.inversePrimary, scheme.inverseSurface, WCAG_AA_TEXT_CONTRAST)
        assertContrastAtLeast(label, "onError vs error", scheme.onError, scheme.error, WCAG_AA_TEXT_CONTRAST)
        assertContrastAtLeast(label, "onErrorContainer vs errorContainer", scheme.onErrorContainer, scheme.errorContainer, WCAG_AA_TEXT_CONTRAST)

        // Interactive control outlines (e.g. PlayerQueue unchecked checkboxes) must meet WCAG 2.1 AA 3:1 UI contrast.
        assertContrastAtLeast(label, "outline vs background", scheme.outline, scheme.background, WCAG_AA_UI_CONTRAST)
        assertContrastAtLeast(label, "outline vs surface", scheme.outline, scheme.surface, WCAG_AA_UI_CONTRAST)
        assertContrastAtLeast(label, "outline vs surfaceContainer", scheme.outline, scheme.surfaceContainer, WCAG_AA_UI_CONTRAST)
    }

    private fun assertContrastAtLeast(
        schemeLabel: String,
        pairLabel: String,
        foreground: Color,
        background: Color,
        minContrast: Float,
    ) {
        val ratio = wcagContrastRatio(foreground, background)
        assertTrue(
            "$schemeLabel failed contrast for $pairLabel: got $ratio < $minContrast",
            ratio + 1e-3f >= minContrast,
        )
    }
}

