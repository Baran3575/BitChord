package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.data.lyrics.remapped
import com.music.bitchord.data.model.VersionTimeMap
import com.music.bitchord.ui.player.adjustedLyricsPosition
import com.music.bitchord.ui.player.adjustedLyricsSeekTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class RemappedLyricsOffsetTest {

    @Test
    fun `remapped video lyrics compose with user lyricsOffsetMs without mutating user offset`() {
        val timeMap = VersionTimeMap(
            listOf(VersionTimeMap.Segment(fromStartMs = 0L, toStartMs = 25_000L, durationMs = 180_000L)),
        )
        val remapped = listOf(
            LyricLine(
                timeMs = 10_000L,
                text = "Opening line",
                words = listOf(LyricWord(10_000L, 12_000L, "Opening")),
            ),
        ).remapped(timeMap)

        val firstSung = remapped.first { !it.isGap }
        assertEquals(35_000L, firstSung.timeMs)

        val savedUserOffsetMs = 500
        assertEquals(35_500L, adjustedLyricsSeekTarget(firstSung.timeMs, savedUserOffsetMs))
        assertEquals(35_000L, adjustedLyricsPosition(positionMs = 35_500L, offsetMs = savedUserOffsetMs))
        assertEquals(500, savedUserOffsetMs)
    }
}
