package com.music.bitchord.data.canvas

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.roundToInt

/**
 * Turns a scanned signature (dark ink on white paper) into polarity-matched
 * strokes on transparency, so it reads on both dark and light detail pages
 * like the tinted SVGs.
 *
 * Maps each pixel's alpha to its darkness (GIMP-style white-to-alpha) and
 * paints the survivors in [inkColor]. Runs only for raster signature loads —
 * see DetailScreen — and refuses images that don't read as scans: when fewer
 * than [SCAN_WHITE_FRACTION] of pixels are near-white the input is returned
 * untouched, so an in-person autograph *photograph* never becomes monotone
 * blobs. Already-transparent PNG ink passes through the same math harmlessly.
 */
class SignatureInkTransformation(
    inkColor: Color = Color.White,
) : Transformation() {

    private val inkRgb: Int = inkColor.toArgb() and 0x00FFFFFF

    override val cacheKey: String =
        "signature-ink-v2-${inkRgb.toString(16).padStart(6, '0')}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height
        if (width <= 0 || height <= 0) return input

        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)

        if (!transformSignaturePixels(pixels, inkRgb)) return input

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    companion object {
        /** Luminance above this counts as paper rather than ink (0..1). */
        private const val PAPER_LUMINANCE = 0.92f

        /** Share of paper pixels required before treating the image as a scan. */
        private const val SCAN_WHITE_FRACTION = 0.55f

        internal fun transformSignaturePixels(pixels: IntArray, inkRgb: Int): Boolean {
            if (pixels.isEmpty()) return false
            val maskedRgb = inkRgb and 0x00FFFFFF
            var paper = 0
            for (pixel in pixels) {
                if (luminance(pixel) >= PAPER_LUMINANCE) paper++
            }
            if (paper.toFloat() / pixels.size < SCAN_WHITE_FRACTION) return false

            for (i in pixels.indices) {
                val pixel = pixels[i]
                val inAlpha = (pixel ushr 24) / 255f
                if (inAlpha <= 0f) continue
                val darkness = 1f - luminance(pixel)
                if (darkness <= 0f) {
                    pixels[i] = 0
                    continue
                }
                val outAlpha = (darkness * inAlpha * 255f).roundToInt().coerceIn(0, 255)
                pixels[i] = (outAlpha shl 24) or maskedRgb
            }
            return true
        }

        private fun luminance(pixel: Int): Float {
            val r = ((pixel ushr 16) and 0xFF) / 255f
            val g = ((pixel ushr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            return 0.299f * r + 0.587f * g + 0.114f * b
        }
    }
}
