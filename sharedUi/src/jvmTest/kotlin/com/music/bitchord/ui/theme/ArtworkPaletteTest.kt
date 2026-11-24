package com.music.bitchord.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.ui.components.MenuFrostDark
import com.music.bitchord.ui.components.thumbnailBorderColor
import com.music.bitchord.ui.graphics.ColorUtils
import com.music.bitchord.ui.player.PLAYER_BAR_BORDER_ALPHA
import com.music.bitchord.ui.player.PLAYER_BAR_PLAIN_SLAB_ALPHA
import com.music.bitchord.ui.player.PLAYER_BAR_SLAB_ALPHA
import org.junit.Assert.assertEquals
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
    fun lightAndDarkArtworkPalettesMeetContrastAndPolarityThresholds() {
        val swatches = listOf(
            Color.White,
            Color.Black,
            Color(0xFF808080),
            Color(0xFFFA2D48),
            Color(0xFFFFD60A),
            Color(0xFF30D158),
            Color(0xFF64D2FF),
            Color(0xFF0A84FF),
            Color(0xFFBF5AF2),
        )
        val edges = listOf(Color.Black, Color(0xFF808080), Color.White)

        for (dark in listOf(true, false)) {
            for (swatch in swatches) {
                for (edge in edges) {
                    val palette = buildArtworkPalette(
                        dominant = swatch,
                        vibrant = swatch,
                        edge = edge,
                        dark = dark,
                    )
                    val surfaces = listOf(palette.background.toArgb(), palette.wash.toArgb())
                    for (surface in surfaces) {
                        val primaryTextContrast = ColorUtils.calculateContrast(
                            palette.onBackground.toArgb(),
                            surface,
                        )
                        assertTrue(
                            "onBackground contrast $primaryTextContrast < 4.5 (dark=$dark, swatch=$swatch)",
                            primaryTextContrast >= 4.5,
                        )

                        val secondaryTextContrast = ColorUtils.calculateContrast(
                            palette.onBackgroundVariant.toArgb(),
                            surface,
                        )
                        assertTrue(
                            "onBackgroundVariant contrast $secondaryTextContrast < 4.5 (dark=$dark, swatch=$swatch)",
                            secondaryTextContrast >= 4.5,
                        )

                        val containerContrast = ColorUtils.calculateContrast(
                            palette.container.toArgb(),
                            surface,
                        )
                        assertTrue(
                            "container contrast $containerContrast < 3.0 (dark=$dark, swatch=$swatch)",
                            containerContrast >= 3.0,
                        )

                        val onContainerContrast = ColorUtils.calculateContrast(
                            palette.onContainer.toArgb(),
                            palette.container.toArgb(),
                        )
                        assertTrue(
                            "onContainer contrast $onContainerContrast < 4.5 (dark=$dark)",
                            onContainerContrast >= 4.5,
                        )

                        val veiledSurface = ColorUtils.compositeColors(palette.veil.toArgb(), surface)
                        val veiledGlyphContrast = ColorUtils.calculateContrast(
                            palette.onBackground.toArgb(),
                            veiledSurface,
                        )
                        assertTrue(
                            "veiled button glyph contrast $veiledGlyphContrast < 4.5 (dark=$dark)",
                            veiledGlyphContrast >= 4.5,
                        )

                        val borderContrast = ColorUtils.calculateContrast(
                            palette.border.toArgb(),
                            surface,
                        )
                        assertTrue(
                            "border contrast $borderContrast < 1.20 (dark=$dark, swatch=$swatch)",
                            borderContrast >= 1.20,
                        )

                        val coverBorderContrast = ColorUtils.calculateContrast(
                            palette.coverBorder.toArgb(),
                            surface,
                        )
                        assertTrue(
                            "coverBorder contrast $coverBorderContrast < 1.20 (dark=$dark, swatch=$swatch)",
                            coverBorderContrast >= 1.20,
                        )

                        val signatureContrast = ColorUtils.calculateContrast(
                            palette.signatureInk.toArgb(),
                            surface,
                        )
                        assertTrue(
                            "signatureInk contrast $signatureContrast < 4.5 (dark=$dark, swatch=$swatch)",
                            signatureContrast >= 4.5,
                        )
                    }
                    assertEquals(
                        if (dark) Color.White else Color.Black,
                        palette.signatureInk,
                    )
                }
            }
        }
    }

    @Test
    fun playerBarSurfaceMeetsNoticeContrastOverWhiteAndBlackBackdrops() {
        for (slabAlpha in listOf(PLAYER_BAR_SLAB_ALPHA, PLAYER_BAR_PLAIN_SLAB_ALPHA)) {
            for (backdrop in listOf(Color.White.toArgb(), Color.Black.toArgb())) {
                val surface = ColorUtils.compositeColors(
                    MenuFrostDark.copy(alpha = slabAlpha).toArgb(),
                    backdrop,
                )
                val bodyContrast = ColorUtils.calculateContrast(Color.White.toArgb(), surface)
                assertTrue(
                    "Player bar white ink contrast $bodyContrast < 4.5 (alpha=$slabAlpha)",
                    bodyContrast >= 4.5,
                )
            }
        }
        val rimContrastOnBlack = ColorUtils.calculateContrast(
            Color.White.copy(alpha = PLAYER_BAR_BORDER_ALPHA).toArgb(),
            Color.Black.toArgb(),
        )
        assertTrue("Player bar rim contrast $rimContrastOnBlack < 1.20", rimContrastOnBlack >= 1.20)
    }

    @Test
    fun thumbnailBorderFollowsMaterialColorSchemePolarity() {
        val lightScheme = lightColorScheme(onSurface = Color.Black, surface = Color(0xFFF7F7F9))
        val darkScheme = darkColorScheme(onSurface = Color.White, surface = Color(0xFF0D0D0F))

        val lightBorder = thumbnailBorderColor(lightScheme)
        val darkBorder = thumbnailBorderColor(darkScheme)

        assertEquals(Color.Black.copy(alpha = 0.15f), lightBorder)
        assertEquals(Color.White.copy(alpha = 0.15f), darkBorder)
        assertTrue(
            ColorUtils.calculateContrast(lightBorder.toArgb(), Color.White.toArgb()) >= 1.20,
        )
        assertTrue(
            ColorUtils.calculateContrast(darkBorder.toArgb(), Color.Black.toArgb()) >= 1.20,
        )
    }
}
