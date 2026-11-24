package com.music.bitchord.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.data.canvas.SignatureInkTransformation
import com.music.bitchord.ui.components.DARK_INDICATOR_OPACITY
import com.music.bitchord.ui.components.glassContentColor
import com.music.bitchord.ui.components.glassEdgeColor
import com.music.bitchord.ui.components.glassSecondaryContentColor
import com.music.bitchord.ui.components.glassSubtitleContentColor
import com.music.bitchord.ui.components.glassSurfaceTintColor
import com.music.bitchord.ui.components.hazeSecondaryContentColor
import com.music.bitchord.ui.components.isLightGlassSurface
import com.music.bitchord.ui.components.topBarSearchFieldColors
import com.music.bitchord.ui.graphics.ColorUtils
import com.music.bitchord.ui.screens.TOP_RELEASE_SECONDARY_ALPHA
import com.music.bitchord.ui.screens.resolvePlayCircleColor
import com.music.bitchord.ui.screens.selectionBarColors
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.ui.theme.DarkColors
import com.music.bitchord.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassAndSurfaceContrastTest {

    private val extremeBackdrops = listOf(Color.White.toArgb(), Color.Black.toArgb())

    @Test
    fun liquidGlassAndGlassNavBarMeetWcagContrastAcrossExtremeBackdrops() {
        for (scheme in listOf(DarkColors, LightColors)) {
            val isLight = isLightGlassSurface(scheme.surface)
            val glassTint = glassSurfaceTintColor(isLight).toArgb()
            val primary = glassContentColor(isLight).toArgb()
            val tabUnselected = glassSecondaryContentColor(isLight).toArgb()
            val subtitle = glassSubtitleContentColor(isLight).toArgb()
            val indicatorAlpha = if (isLight) 0.5f else DARK_INDICATOR_OPACITY
            val indicator = Color.White.copy(alpha = indicatorAlpha).toArgb()

            for (backdrop in extremeBackdrops) {
                val barSurface = ColorUtils.compositeColors(glassTint, backdrop)
                val primaryContrast = ColorUtils.calculateContrast(primary, barSurface)
                val tabContrast = ColorUtils.calculateContrast(tabUnselected, barSurface)
                val subtitleContrast = ColorUtils.calculateContrast(subtitle, barSurface)

                assertTrue(
                    "Glass primary contrast $primaryContrast < 4.5 (isLight=$isLight)",
                    primaryContrast >= 4.5,
                )
                assertTrue(
                    "Glass unselected 10.sp tab contrast $tabContrast < 4.5 (isLight=$isLight)",
                    tabContrast >= 4.5,
                )
                assertTrue(
                    "Glass artist subtitle contrast $subtitleContrast < 4.5 (isLight=$isLight)",
                    subtitleContrast >= 4.5,
                )

                val selectedSurface = ColorUtils.compositeColors(indicator, barSurface)
                val selectedContrast = ColorUtils.calculateContrast(primary, selectedSurface)
                assertTrue(
                    "Glass selected tab contrast $selectedContrast < 4.5 (isLight=$isLight)",
                    selectedContrast >= 4.5,
                )
            }

            val rim = glassEdgeColor(isLight).toArgb()
            val naturalBackdrop = if (isLight) Color.White.toArgb() else Color.Black.toArgb()
            val rimContrast = ColorUtils.calculateContrast(rim, naturalBackdrop)
            assertTrue(
                "Glass rim contrast $rimContrast < 1.20 (isLight=$isLight)",
                rimContrast >= 1.20,
            )
        }
    }

    @Test
    fun miniPlayerAndFloatingBottomBarHazeRegularMeetWcagContrast() {
        // HazeMaterials.regular uses 0.73f alpha for light containers and 0.80f for dark containers.
        for ((scheme, hazeAlpha) in listOf(DarkColors to 0.80f, LightColors to 0.73f)) {
            val hazeScrim = scheme.surface.copy(alpha = hazeAlpha).toArgb()
            val primary = scheme.onBackground.toArgb()
            val secondary = hazeSecondaryContentColor(scheme.onSurface).toArgb()

            for (backdrop in extremeBackdrops) {
                val barSurface = ColorUtils.compositeColors(hazeScrim, backdrop)
                val primaryContrast = ColorUtils.calculateContrast(primary, barSurface)
                val secondaryContrast = ColorUtils.calculateContrast(secondary, barSurface)

                assertTrue(
                    "Haze primary contrast $primaryContrast < 4.5",
                    primaryContrast >= 4.5,
                )
                assertTrue(
                    "Haze secondary contrast $secondaryContrast < 4.5",
                    secondaryContrast >= 4.5,
                )
            }

            // Solid fallback (reduceDynamicBlur = true)
            val solidSurface = scheme.surface.toArgb()
            assertTrue(ColorUtils.calculateContrast(primary, solidSurface) >= 4.5)
            assertTrue(ColorUtils.calculateContrast(secondary, solidSurface) >= 4.5)
        }
    }

    @Test
    fun frostedTopBarSearchFieldColorsMeetContrastInGlassAndHazeModes() {
        for (scheme in listOf(DarkColors, LightColors)) {
            val isLight = isLightGlassSurface(scheme.surface)

            val glassColors = topBarSearchFieldColors(
                useLiquidGlass = true,
                isLightSurface = isLight,
                onSurface = scheme.onSurface,
                primary = scheme.primary,
            )
            val hazeColors = topBarSearchFieldColors(
                useLiquidGlass = false,
                isLightSurface = isLight,
                onSurface = scheme.onSurface,
                primary = scheme.primary,
            )

            val glassTint = glassSurfaceTintColor(isLight).toArgb()
            val hazeTint = scheme.surface.copy(alpha = if (isLight) 0.73f else 0.80f).toArgb()

            for (backdrop in extremeBackdrops) {
                val glassSurface = ColorUtils.compositeColors(glassTint, backdrop)
                assertTrue(ColorUtils.calculateContrast(glassColors.textColor.toArgb(), glassSurface) >= 4.5)
                assertTrue(ColorUtils.calculateContrast(glassColors.placeholderColor.toArgb(), glassSurface) >= 4.5)
                assertTrue(ColorUtils.calculateContrast(glassColors.iconColor.toArgb(), glassSurface) >= 4.5)

                val hazeSurface = ColorUtils.compositeColors(hazeTint, backdrop)
                assertTrue(ColorUtils.calculateContrast(hazeColors.textColor.toArgb(), hazeSurface) >= 4.5)
                assertTrue(ColorUtils.calculateContrast(hazeColors.placeholderColor.toArgb(), hazeSurface) >= 4.5)
                assertTrue(ColorUtils.calculateContrast(hazeColors.iconColor.toArgb(), hazeSurface) >= 4.5)
            }
        }
    }

    @Test
    fun detailScreenLightPaletteControlsAndPlayCircleMaintainContrast() {
        val lightPage = Color(0xFFEBE8E6) // ~0.91f lightness
        val lightWash = Color(0xFFE0DCDA) // ~0.87f lightness
        val lightPalette = ArtworkPalette(
            background = lightPage,
            wash = lightWash,
            elevated = Color(0xFFD6D1CE),
            accent = Color(0xFF9E1F32),
            onBackground = Color.Black,
            onBackgroundVariant = Color(0xFF5E4A4D),
            divider = Color.Black.copy(alpha = 0.10f),
        )

        // Near-white editorial keyColor must fall back to dark container on light wash
        val fallbackPlay = resolvePlayCircleColor(Color.White, lightPalette)
        assertEquals(lightPalette.container, fallbackPlay)
        assertTrue(
            ColorUtils.calculateContrast(fallbackPlay.toArgb(), lightPalette.wash.toArgb()) >= 3.0,
        )

        // Saturated dark editorial keyColor with >= 3.0:1 contrast is preserved
        val deepCrimson = Color(0xFF8B1024)
        assertEquals(deepCrimson, resolvePlayCircleColor(deepCrimson, lightPalette))

        // TopReleaseCard secondary lines over veiled light wash
        val cardSurface = ColorUtils.compositeColors(
            lightPalette.veil.toArgb(),
            lightPalette.wash.toArgb(),
        )
        val secondaryLine = lightPalette.onBackground.copy(alpha = TOP_RELEASE_SECONDARY_ALPHA)
        assertTrue(
            ColorUtils.calculateContrast(secondaryLine.toArgb(), cardSurface) >= 4.5,
        )
    }

    @Test
    fun localFolderSelectionBarUsesColorSchemePolarityInsteadOfOsDarkMode() {
        val lightBar = selectionBarColors(LightColors)
        assertEquals(Color.Black, lightBar.checkTint)
        assertEquals(Color.Black, lightBar.countColor)
        assertTrue(
            ColorUtils.calculateContrast(lightBar.checkTint.toArgb(), lightBar.container.toArgb()) >= 4.5,
        )
        assertTrue(
            ColorUtils.calculateContrast(lightBar.countColor.toArgb(), lightBar.container.toArgb()) >= 4.5,
        )

        val darkBar = selectionBarColors(DarkColors)
        assertEquals(Color.White, darkBar.checkTint)
        assertEquals(Color.White, darkBar.countColor)
        assertTrue(
            ColorUtils.calculateContrast(darkBar.countColor.toArgb(), darkBar.container.toArgb()) >= 4.5,
        )
    }

    @Test
    fun signatureInkTransformationSupportsLightAndDarkInkWithDistinctCacheKeys() {
        val whiteTransform = SignatureInkTransformation(Color.White)
        val blackTransform = SignatureInkTransformation(Color.Black)
        assertNotEquals(whiteTransform.cacheKey, blackTransform.cacheKey)

        // 3 white paper pixels + 1 black ink stroke pixel (75% paper >= 55% threshold)
        val scanForDarkPage = intArrayOf(
            0xFFFFFFFF.toInt(),
            0xFFFFFFFF.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
        )
        assertTrue(SignatureInkTransformation.transformSignaturePixels(scanForDarkPage, 0x00FFFFFF))
        assertEquals(0, scanForDarkPage[0])
        assertEquals(0xFFFFFFFF.toInt(), scanForDarkPage[3])

        val scanForLightPage = intArrayOf(
            0xFFFFFFFF.toInt(),
            0xFFFFFFFF.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
        )
        assertTrue(SignatureInkTransformation.transformSignaturePixels(scanForLightPage, 0x00000000))
        assertEquals(0, scanForLightPage[0])
        assertEquals(0xFF000000.toInt(), scanForLightPage[3])

        // Non-scan photograph (< 55% white paper) is left untouched
        val photoPixels = intArrayOf(
            0xFF223344.toInt(),
            0xFF334455.toInt(),
            0xFF445566.toInt(),
            0xFFFFFFFF.toInt(),
        )
        assertFalse(SignatureInkTransformation.transformSignaturePixels(photoPixels, 0x00000000))
        assertEquals(0xFF223344.toInt(), photoPixels[0])
    }
}
