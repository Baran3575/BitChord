package com.music.bitchord.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Centralized semantic typography extensions derived from the active [Typography] scale.
 *
 * Every style inherits [Typography]'s configured [FontFamily] (`SFProDisplay` or
 * `SFProDisplayLegacy` on Android, platform font family on Desktop) and scales
 * uniformly with user font preferences and system font scaling.
 */

// ── Shared Alert, Dialog & Popover Styles ───────────────────────────────────

/** 17sp semibold alert/dialog/sheet headline (Apple Music alert & popover header). */
val Typography.alertTitle: TextStyle
    get() = titleMedium.copy(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = (-0.2).sp,
    )

/** 13sp regular alert/dialog/sheet explanatory body and secondary metadata. */
val Typography.alertBody: TextStyle
    get() = bodyMedium.copy(
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 0.2.sp,
    )

/** 13sp compact secondary line (16sp line height) used in shelf cards and compact subtitles. */
val Typography.compactBody: TextStyle
    get() = bodyMedium.copy(
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 0.2.sp,
    )

/** 15sp callout used for dialog list rows, action items, empty-state titles, and status titles. */
val Typography.callout: TextStyle
    get() = bodyLarge.copy(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 0.15.sp,
    )

/** 12sp secondary row description with tight 15sp line height for multi-line dialog items. */
val Typography.dialogRowDetail: TextStyle
    get() = bodySmall.copy(
        fontSize = 12.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 0.25.sp,
    )

/** 12.5sp caption used in cast device rows and audio pipeline secondary labels. */
val Typography.captionDetail: TextStyle
    get() = bodySmall.copy(
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 0.2.sp,
    )

/** 11.5sp micro label used in compact lyrics/pipeline badges. */
val Typography.microLabel: TextStyle
    get() = labelSmall.copy(
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.W500,
        letterSpacing = 0.25.sp,
    )

// ── Navigation, Mini-Player & Badge Tokens ──────────────────────────────────

/** 10sp bottom navigation tab bar label. */
val Typography.tabBarLabel: TextStyle
    get() = labelSmall.copy(
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = 0.5.sp,
    )

/** 13sp glass mini-player track title. */
val Typography.miniPlayerTitle: TextStyle
    get() = titleSmall.copy(
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.W700,
        letterSpacing = 0.15.sp,
    )

/** 12sp glass mini-player artist subtitle. */
val Typography.miniPlayerSubtitle: TextStyle
    get() = bodySmall.copy(
        fontSize = 12.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 0.35.sp,
    )

/** Dynamic +0.5sp step above [Typography.labelMedium] for player lossless/mixing badges. */
val Typography.qualityBadge: TextStyle
    get() = labelMedium.copy(
        fontWeight = FontWeight.Medium,
        fontSize = (labelMedium.fontSize.value + 0.5f).sp,
        lineHeight = (labelMedium.lineHeight.value + 0.5f).sp,
    )

/** 9.5sp bold cutout glyph style for the square Explicit ('E') badge. */
val Typography.explicitBadge: TextStyle
    get() = labelSmall.copy(
        fontSize = 9.5.sp,
        lineHeight = 10.sp,
        fontWeight = FontWeight.W800,
        letterSpacing = 0.sp,
    )

/** Minimum auto-size step floor for single-line bulk action labels. */
val AutoSizeLabelMinFontSize: TextUnit = 9.sp

// ── Player, Sheet & Lyrics Tokens ───────────────────────────────────────────

/** 19sp bold sheet & drawer section header (`LyricsOffsetSheet`, `PlayerDrawer`, `PlayerControls`, `DetailScreen`). */
val Typography.sheetHeader: TextStyle
    get() = titleLarge.copy(
        fontSize = 19.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.W700,
        letterSpacing = (-0.25).sp,
    )

/** 18sp bold music-video overlay track title. */
val Typography.videoOverlayTitle: TextStyle
    get() = titleLarge.copy(
        fontSize = 18.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.W700,
        letterSpacing = (-0.2).sp,
    )

/** 20sp bold Now Playing track header. */
val Typography.playerTrackTitle: TextStyle
    get() = titleLarge.copy(
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.W700,
        letterSpacing = (-0.2).sp,
    )

/** 34sp extra-bold synced lead lyric line. */
val Typography.lyricSyncedLead: TextStyle
    get() = displayLarge.copy(
        fontSize = 34.sp,
        lineHeight = 41.sp,
        fontWeight = FontWeight.ExtraBold,
    )

/** 30sp extra-bold unsynced lead lyric line. */
val Typography.lyricUnsyncedLead: TextStyle
    get() = headlineLarge.copy(
        fontSize = 30.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.ExtraBold,
    )

/** 23sp synced backing-vocal lyric line. */
val Typography.lyricBackingVoice: TextStyle
    get() = headlineMedium.copy(
        fontSize = 23.sp,
        lineHeight = 29.sp,
    )

/** 20sp romanized / translated sub-lyric voice. */
val Typography.lyricSubVoice: TextStyle
    get() = titleLarge.copy(
        fontSize = 20.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.Bold,
    )

/** 16sp romanized / translated backing sub-lyric voice. */
val Typography.lyricSubBackingVoice: TextStyle
    get() = titleMedium.copy(
        fontSize = 16.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Bold,
    )

// ── Onboarding & Replay Display Tokens ──────────────────────────────────────

/** 40sp bold onboarding hero display headline. */
val Typography.onboardingHero: TextStyle
    get() = displayLarge.copy(
        fontSize = 40.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.W800,
        letterSpacing = (-0.8).sp,
    )

/** 26sp bold onboarding & Replay card lead title. */
val Typography.featureTitle: TextStyle
    get() = displaySmall.copy(
        fontSize = 26.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.W800,
        letterSpacing = (-0.35).sp,
    )

/** 27sp bold Replay share card primary stat value. */
val Typography.replayCardStat: TextStyle
    get() = displaySmall.copy(
        fontSize = 27.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.W800,
    )

/** 7sp micro uppercase label for Replay card 'MEMBER SINCE' stamp. */
val Typography.replayCardMicroStamp: TextStyle
    get() = labelSmall.copy(
        fontSize = 7.sp,
        lineHeight = 8.sp,
        letterSpacing = 0.8.sp,
    )

/** Monospaced embossed Replay card text style in primary (13sp) or secondary (12sp) cut. */
fun replayCardEmbossedStyle(primary: Boolean): TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.W600,
    fontSize = if (primary) 13.sp else 12.sp,
    lineHeight = if (primary) 16.sp else 15.sp,
    letterSpacing = 1.6.sp,
)

/** 60sp heavy Replay genre story hero display. */
val Typography.replayGenreHero: TextStyle
    get() = displayLarge.copy(
        fontSize = 60.sp,
        lineHeight = 62.sp,
        fontWeight = FontWeight.W800,
        letterSpacing = (-1.2).sp,
    )

/** Dynamic pill badge style sized off the container's [height] for [ReplayYearButton]. */
@Composable
fun replayPillBadgeStyle(height: Dp): TextStyle {
    val scaledSp = with(LocalDensity.current) { (height * 0.5f).toSp() }
    return MaterialTheme.typography.labelLarge.copy(
        fontSize = scaledSp,
        lineHeight = scaledSp,
        fontWeight = FontWeight.W900,
        letterSpacing = 0.sp,
        color = Color.White,
    )
}

/** Centralized kinetic display typography tokens for Replay Year story slides. */
object ReplayYearTypography {
    val Setup: TextStyle = TextStyle(
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    )
    val SubSetup: TextStyle = Setup.copy(
        fontSize = 26.sp,
        lineHeight = 30.sp,
    )
    val Detail: TextStyle = TextStyle(
        fontSize = 19.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.W600,
        color = Color.White,
    )
    val ArtistLead: TextStyle = Detail.copy(
        fontSize = 21.sp,
        lineHeight = 26.sp,
    )
    val AxisTag: TextStyle = TextStyle(
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.W700,
    )
    val RankRowLeadTitle: TextStyle = TextStyle(
        fontSize = 24.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.W800,
    )
    val RankRowTitle: TextStyle = TextStyle(
        fontSize = 19.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.W800,
    )
    val RankRowSubtitle: TextStyle = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.W500,
    )
    val MonthSongTitle: TextStyle = TextStyle(
        fontSize = 15.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.W700,
    )
    val SummarySectionHeader: TextStyle = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.W700,
    )
    val SummaryRankIndex: TextStyle = TextStyle(
        fontSize = 17.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.W900,
    )
    val SummaryRankName: TextStyle = TextStyle(
        fontSize = 17.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.W700,
    )

    private val HugeBase = TextStyle(
        fontWeight = FontWeight.W900,
        letterSpacing = (-3).sp,
        color = Color.White,
    )

    fun huge(size: TextUnit): TextStyle = HugeBase.copy(
        fontSize = size,
        lineHeight = size * 0.95f,
    )
}
