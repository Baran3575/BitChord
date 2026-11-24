package com.music.bitchord.ui.graphics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The parts of `androidx.core.graphics.ColorUtils` the artwork code uses,
 * ported as written so the phone's numbers do not move: HSL both ways and
 * relative luminance. Colours are packed ARGB ints, as there.
 */
object ColorUtils {

    fun colorToHSL(color: Int, outHsl: FloatArray) {
        RGBToHSL((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, outHsl)
    }

    @Suppress("FunctionName")
    fun RGBToHSL(rf: Int, gf: Int, bf: Int, outHsl: FloatArray) {
        val r = rf / 255f
        val g = gf / 255f
        val b = bf / 255f

        val max = max(r, max(g, b))
        val min = min(r, min(g, b))
        val deltaMaxMin = max - min

        var h: Float
        val s: Float
        val l = (max + min) / 2f

        if (max == min) {
            h = 0f
            s = 0f
        } else {
            h = when (max) {
                r -> ((g - b) / deltaMaxMin) % 6f
                g -> ((b - r) / deltaMaxMin) + 2f
                else -> ((r - g) / deltaMaxMin) + 4f
            }
            s = deltaMaxMin / (1f - abs(2f * l - 1f))
        }

        h = (h * 60f) % 360f
        if (h < 0) h += 360f

        outHsl[0] = h.coerceIn(0f, 360f)
        outHsl[1] = s.coerceIn(0f, 1f)
        outHsl[2] = l.coerceIn(0f, 1f)
    }

    @Suppress("FunctionName")
    fun HSLToColor(hsl: FloatArray): Int {
        val h = hsl[0]
        val s = hsl[1]
        val l = hsl[2]

        val c = (1f - abs(2 * l - 1f)) * s
        val m = l - 0.5f * c
        val x = c * (1f - abs((h / 60f % 2f) - 1f))

        val hueSegment = h.toInt() / 60

        var r = 0
        var g = 0
        var b = 0

        when (hueSegment) {
            0 -> {
                r = (255 * (c + m)).roundToInt()
                g = (255 * (x + m)).roundToInt()
                b = (255 * m).roundToInt()
            }
            1 -> {
                r = (255 * (x + m)).roundToInt()
                g = (255 * (c + m)).roundToInt()
                b = (255 * m).roundToInt()
            }
            2 -> {
                r = (255 * m).roundToInt()
                g = (255 * (c + m)).roundToInt()
                b = (255 * (x + m)).roundToInt()
            }
            3 -> {
                r = (255 * m).roundToInt()
                g = (255 * (x + m)).roundToInt()
                b = (255 * (c + m)).roundToInt()
            }
            4 -> {
                r = (255 * (x + m)).roundToInt()
                g = (255 * m).roundToInt()
                b = (255 * (c + m)).roundToInt()
            }
            5, 6 -> {
                r = (255 * (c + m)).roundToInt()
                g = (255 * m).roundToInt()
                b = (255 * (x + m)).roundToInt()
            }
        }

        r = r.coerceIn(0, 255)
        g = g.coerceIn(0, 255)
        b = b.coerceIn(0, 255)

        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Relative luminance, 0 for black and 1 for white. */
    fun calculateLuminance(color: Int): Double {
        val sr = linear(((color ushr 16) and 0xFF) / 255.0)
        val sg = linear(((color ushr 8) and 0xFF) / 255.0)
        val sb = linear((color and 0xFF) / 255.0)
        // The Y of XYZ, which is what ColorUtils returns (divided back out of 100).
        return (100 * (sr * 0.2126 + sg * 0.7152 + sb * 0.0722)) / 100
    }

    /** Alpha-composites [foreground] over [background] in sRGB, matching AndroidX ColorUtils. */
    fun compositeColors(foreground: Int, background: Int): Int {
        val bgAlpha = (background ushr 24) and 0xFF
        val fgAlpha = (foreground ushr 24) and 0xFF
        val a = compositeAlpha(fgAlpha, bgAlpha)
        val r = compositeComponent((foreground ushr 16) and 0xFF, fgAlpha, (background ushr 16) and 0xFF, bgAlpha, a)
        val g = compositeComponent((foreground ushr 8) and 0xFF, fgAlpha, (background ushr 8) and 0xFF, bgAlpha, a)
        val b = compositeComponent(foreground and 0xFF, fgAlpha, background and 0xFF, bgAlpha, a)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** WCAG 2.1 contrast ratio between [foreground] (composited if translucent) and opaque [background]. */
    fun calculateContrast(foreground: Int, background: Int): Double {
        require(((background ushr 24) and 0xFF) == 0xFF) {
            "background can not be translucent: #${background.toUInt().toString(16)}"
        }
        val fg = if (((foreground ushr 24) and 0xFF) < 0xFF) {
            compositeColors(foreground, background)
        } else {
            foreground
        }
        val luminance1 = calculateLuminance(fg) + 0.05
        val luminance2 = calculateLuminance(background) + 0.05
        return max(luminance1, luminance2) / min(luminance1, luminance2)
    }

    private fun compositeAlpha(foregroundAlpha: Int, backgroundAlpha: Int): Int =
        0xFF - (((0xFF - backgroundAlpha) * (0xFF - foregroundAlpha)) / 0xFF)

    private fun compositeComponent(fgC: Int, fgA: Int, bgC: Int, bgA: Int, a: Int): Int {
        if (a == 0) return 0
        return ((0xFF * fgC * fgA) + (bgC * bgA * (0xFF - fgA))) / (a * 0xFF)
    }

    private fun linear(channel: Double): Double =
        if (channel < 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
}
