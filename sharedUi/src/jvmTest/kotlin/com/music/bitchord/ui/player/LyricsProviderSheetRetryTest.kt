package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.ui.LyricsProviderState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsProviderSheetRetryTest {

    @Test
    fun `not found and untouched providers remain tappable unless already active`() {
        for (state in LyricsProviderState.entries) {
            assertTrue(
                isLyricsProviderRowEnabled(current = false, state = state),
                "Expected non-active row in state $state to be tappable for selection or retry",
            )
            assertFalse(
                isLyricsProviderRowEnabled(current = true, state = state),
                "Expected currently active provider row in state $state to be disabled",
            )
        }
    }

    @Test
    fun `offered providers exclude hidden entries`() {
        assertTrue(LyricsSource.offered.none { it.hidden })
    }
}
