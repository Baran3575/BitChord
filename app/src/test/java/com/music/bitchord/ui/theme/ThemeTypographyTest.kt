package com.music.bitchord.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTypographyTest {

    @Test
    fun appThemeColorSchemesExposeComplete36RoleNeutralTokens() {
        assertSame(BitChordDarkColorScheme, DarkColors)
        assertSame(BitChordLightColorScheme, LightColors)
        assertEquals(36, DarkColors.allRoles().size)
        assertEquals(36, LightColors.allRoles().size)
    }

    @Test
    fun sfProDisplayMapsW500ToMediumCutInsteadOfRegularFallback() {
        val defaultFonts = (SFProDisplay as FontListFontFamily).fonts
        val regularFont = defaultFonts.first { it.weight == FontWeight.W400 } as ResourceFont
        val mediumFont = defaultFonts.first { it.weight == FontWeight.W500 } as ResourceFont
        assertEquals(R.font.sf_pro_display_regular, regularFont.resId)
        assertEquals(R.font.sf_pro_display_medium, mediumFont.resId)
        assertNotEquals(regularFont.resId, mediumFont.resId)

        val legacyFonts = (SFProDisplayLegacy as FontListFontFamily).fonts
        val legacyMedium = legacyFonts.first { it.weight == FontWeight.W500 } as ResourceFont
        assertEquals(R.font.sf_pro_display_medium, legacyMedium.resId)
    }

    @Test
    fun all15TypographyStylesAreExplicitlyDefinedInDefaultAndLegacyScales() {
        assertAll15StylesSpecified("BitChordTypography", BitChordTypography, SFProDisplay)
        assertAll15StylesSpecified("LegacyTypography", LegacyTypography, SFProDisplayLegacy)

        // Verify previously missing tokens (`displayMedium`, `displaySmall`, `headlineMedium`,
        // `titleSmall`, `labelLarge`) have distinct calibrated sizes and weights and do not
        // collapse `titleSmall` / `labelLarge` to W400 Regular.
        assertEquals(28.sp, BitChordTypography.displayMedium.fontSize)
        assertEquals(24.sp, BitChordTypography.displaySmall.fontSize)
        assertEquals(22.sp, BitChordTypography.headlineMedium.fontSize)
        assertEquals(FontWeight.W500, BitChordTypography.titleSmall.fontWeight)
        assertEquals(FontWeight.W500, BitChordTypography.labelLarge.fontWeight)
        assertEquals(FontWeight.W500, LegacyTypography.titleSmall.fontWeight)
        assertEquals(FontWeight.W500, LegacyTypography.labelLarge.fontWeight)
    }

    @Test
    fun sharedSemanticTypographyTokensInheritFontFamilyAndSpecifyMetrics() {
        val tokens = listOf(
            "alertTitle" to BitChordTypography.alertTitle,
            "alertBody" to BitChordTypography.alertBody,
            "compactBody" to BitChordTypography.compactBody,
            "callout" to BitChordTypography.callout,
            "dialogRowDetail" to BitChordTypography.dialogRowDetail,
            "captionDetail" to BitChordTypography.captionDetail,
            "microLabel" to BitChordTypography.microLabel,
            "tabBarLabel" to BitChordTypography.tabBarLabel,
            "miniPlayerTitle" to BitChordTypography.miniPlayerTitle,
            "miniPlayerSubtitle" to BitChordTypography.miniPlayerSubtitle,
            "qualityBadge" to BitChordTypography.qualityBadge,
            "explicitBadge" to BitChordTypography.explicitBadge,
            "sheetHeader" to BitChordTypography.sheetHeader,
            "videoOverlayTitle" to BitChordTypography.videoOverlayTitle,
            "playerTrackTitle" to BitChordTypography.playerTrackTitle,
            "lyricSyncedLead" to BitChordTypography.lyricSyncedLead,
            "lyricUnsyncedLead" to BitChordTypography.lyricUnsyncedLead,
            "lyricBackingVoice" to BitChordTypography.lyricBackingVoice,
            "lyricSubVoice" to BitChordTypography.lyricSubVoice,
            "lyricSubBackingVoice" to BitChordTypography.lyricSubBackingVoice,
            "onboardingHero" to BitChordTypography.onboardingHero,
            "featureTitle" to BitChordTypography.featureTitle,
            "replayCardStat" to BitChordTypography.replayCardStat,
            "replayCardMicroStamp" to BitChordTypography.replayCardMicroStamp,
            "replayGenreHero" to BitChordTypography.replayGenreHero,
        )

        for ((name, style) in tokens) {
            assertEquals("$name should inherit SFProDisplay", SFProDisplay, style.fontFamily)
            assertTrue("$name fontSize must be specified", style.fontSize.isSpecified)
            assertTrue("$name lineHeight must be specified", style.lineHeight.isSpecified)
            assertTrue("$name lineHeight must exceed fontSize", style.lineHeight.value > style.fontSize.value)
        }

        val legacyAlertTitle = LegacyTypography.alertTitle
        assertEquals(SFProDisplayLegacy, legacyAlertTitle.fontFamily)
        assertEquals(17.sp, legacyAlertTitle.fontSize)
        assertEquals(13.sp, BitChordTypography.alertBody.fontSize)
        assertEquals(9.sp, AutoSizeLabelMinFontSize)
        assertEquals(20.sp * 0.95f, ReplayYearTypography.huge(20.sp).lineHeight)
    }

    @Test
    fun zeroAdHocFontSizeOverridesExistOutsideCentralThemeDefinitions() {
        val repoRoot = sequenceOf(File(".."), File("."))
            .map { it.canonicalFile }
            .firstOrNull { File(it, "app/src/main").isDirectory && File(it, "sharedUi/src").isDirectory }
            ?: return

        val allowedFiles = setOf(
            File(repoRoot, "app/src/main/java/com/music/bitchord/ui/theme/Theme.kt").canonicalFile,
            File(repoRoot, "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/theme/TypographyTokens.kt").canonicalFile,
        )
        val fontSizeOverridePattern = Regex("""\bfontSize\s*=""")

        val violations = mutableListOf<String>()
        listOf(File(repoRoot, "app/src/main"), File(repoRoot, "sharedUi/src"))
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { it.canonicalFile !in allowedFiles }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    if (fontSizeOverridePattern.containsMatchIn(line)) {
                        val rel = file.relativeTo(repoRoot).path
                        violations += "$rel:${idx + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue(
            "Expected zero ad-hoc fontSize overrides outside ui/theme/, found:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    private fun assertAll15StylesSpecified(
        scaleName: String,
        typography: Typography,
        expectedFamily: Any,
    ) {
        val styles: List<Pair<String, TextStyle>> = listOf(
            "displayLarge" to typography.displayLarge,
            "displayMedium" to typography.displayMedium,
            "displaySmall" to typography.displaySmall,
            "headlineLarge" to typography.headlineLarge,
            "headlineMedium" to typography.headlineMedium,
            "headlineSmall" to typography.headlineSmall,
            "titleLarge" to typography.titleLarge,
            "titleMedium" to typography.titleMedium,
            "titleSmall" to typography.titleSmall,
            "bodyLarge" to typography.bodyLarge,
            "bodyMedium" to typography.bodyMedium,
            "bodySmall" to typography.bodySmall,
            "labelLarge" to typography.labelLarge,
            "labelMedium" to typography.labelMedium,
            "labelSmall" to typography.labelSmall,
        )

        assertEquals(15, styles.size)
        for ((name, style) in styles) {
            assertEquals("$scaleName.$name fontFamily", expectedFamily, style.fontFamily)
            assertNotNull("$scaleName.$name fontWeight", style.fontWeight)
            assertTrue("$scaleName.$name fontSize must be specified", style.fontSize.isSpecified)
            assertTrue("$scaleName.$name lineHeight must be specified", style.lineHeight.isSpecified)
            assertTrue("$scaleName.$name letterSpacing must be specified", style.letterSpacing.isSpecified)
            assertTrue(
                "$scaleName.$name lineHeight (${style.lineHeight}) must exceed fontSize (${style.fontSize})",
                style.lineHeight.value > style.fontSize.value,
            )
        }
    }
}
