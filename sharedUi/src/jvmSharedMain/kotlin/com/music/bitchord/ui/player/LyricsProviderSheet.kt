package com.music.bitchord.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.LyricsProviderState
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/**
 * Manual provider chooser for the lyrics half-player.
 *
 * Uses [PlayerDrawer] and [ROW_SHAPE] with clean two-line rows (provider name
 * and status), subtle frosted borders, tactile press feedback, and tap-to-retry
 * support on missed rows.
 */
@Composable
internal fun LyricsProviderSheet(
    hazeState: HazeState,
    currentSource: LyricsSource?,
    states: Map<LyricsSource, LyricsProviderState>,
    onSelect: (LyricsSource) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val savedOrder by PlayerSettings.lyricsSourceOrder.collectAsStateWithLifecycle()
    val sources = remember(savedOrder) { LyricsSource.ordered(savedOrder) }
    var requestedSource by remember { mutableStateOf<LyricsSource?>(null) }
    LaunchedEffect(currentSource, requestedSource) {
        if (requestedSource != null && currentSource == requestedSource) onDismiss()
    }

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.choose_lyrics_provider),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sources.forEach { source ->
                LyricsProviderRow(
                    source = source,
                    state = states[source] ?: LyricsProviderState.NOT_FETCHED,
                    current = source == currentSource,
                    onClick = {
                        val wasFound = states[source] == LyricsProviderState.FOUND
                        requestedSource = source
                        onSelect(source)
                        if (wasFound) onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun LyricsProviderRow(
    source: LyricsSource,
    state: LyricsProviderState,
    current: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val enabled = !current && state != LyricsProviderState.FETCHING
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.985f else 1f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "lyricsProviderPressScale",
    )

    val status = when {
        current -> stringResource(Res.string.lyrics_provider_current)
        state == LyricsProviderState.FOUND -> stringResource(Res.string.lyrics_provider_found)
        state == LyricsProviderState.NOT_FOUND -> stringResource(Res.string.lyrics_provider_not_found)
        state == LyricsProviderState.FETCHING -> stringResource(Res.string.lyrics_provider_fetching)
        else -> stringResource(Res.string.lyrics_provider_not_fetched)
    }

    val containerAlpha = when {
        current -> 0.13f
        pressed && enabled -> 0.11f
        state == LyricsProviderState.NOT_FOUND -> 0.035f
        else -> 0.055f
    }
    val borderAlpha = when {
        current -> 0.28f
        state == LyricsProviderState.FOUND -> 0.14f
        else -> 0.06f
    }
    val iconBgAlpha = when {
        current -> 0.20f
        state == LyricsProviderState.FOUND -> 0.12f
        state == LyricsProviderState.NOT_FOUND -> 0.05f
        else -> 0.08f
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = containerAlpha))
            .border(BorderStroke(0.75.dp, Color.White.copy(alpha = borderAlpha)), ROW_SHAPE)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
            ) {
                haptics.play(Haptic.Select)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = iconBgAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state == LyricsProviderState.FETCHING -> CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.85f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(19.dp),
                )
                current || state == LyricsProviderState.FOUND -> Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (current) 1f else 0.78f),
                    modifier = Modifier.size(20.dp),
                )
                state == LyricsProviderState.NOT_FOUND -> Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.48f),
                    modifier = Modifier.size(19.dp),
                )
                else -> Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = source.label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = Color.White.copy(
                    alpha = when {
                        current -> 1f
                        state == LyricsProviderState.NOT_FOUND -> 0.62f
                        else -> 0.88f
                    },
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(
                    alpha = when {
                        current -> 0.75f
                        state == LyricsProviderState.FOUND -> 0.62f
                        state == LyricsProviderState.NOT_FOUND -> 0.45f
                        else -> 0.52f
                    },
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
