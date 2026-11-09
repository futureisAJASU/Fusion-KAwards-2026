package com.projectnuke.fusion.util

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FusionThumbnailTransformPixelTest {
    @Test
    fun `all EXIF orientation transforms preserve the asymmetric pixel matrix`() {
        cases.forEach { case ->
            val source = asymmetricFixture()
            val transformed = applyThumbnailOrientation(
                source,
                ThumbnailOrientationPolicy.fromExifOrientation(case.orientation),
            )
            try {
                assertEquals(case.label, case.width, transformed.width)
                assertEquals(case.label, case.height, transformed.height)
                val actual = IntArray(case.width * case.height)
                transformed.getPixels(actual, 0, case.width, 0, 0, case.width, case.height)
                assertArrayEquals(case.label, case.pixels, actual)
            } finally {
                if (transformed !== source) transformed.recycle()
                source.recycle()
            }
        }
    }

    private fun asymmetricFixture(): Bitmap = Bitmap.createBitmap(
        SourceWidth,
        SourceHeight,
        Bitmap.Config.ARGB_8888,
    ).apply {
        setPixels(SourcePixels, 0, SourceWidth, 0, 0, SourceWidth, SourceHeight)
    }

    private data class OrientationCase(
        val label: String,
        val orientation: Int,
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    )

    private companion object {
        private const val SourceWidth = 2
        private const val SourceHeight = 3
        private const val A = 0xff102030.toInt()
        private const val B = 0xff405060.toInt()
        private const val C = 0xff708090.toInt()
        private const val D = 0xffa0b0c0.toInt()
        private const val E = 0xffd0e0f0.toInt()
        private const val F = 0xff123456.toInt()
        private val SourcePixels = intArrayOf(A, B, C, D, E, F)
        private val cases = listOf(
            OrientationCase("normal", ExifInterface.ORIENTATION_NORMAL, 2, 3, intArrayOf(A, B, C, D, E, F)),
            OrientationCase("flip horizontal", ExifInterface.ORIENTATION_FLIP_HORIZONTAL, 2, 3, intArrayOf(B, A, D, C, F, E)),
            OrientationCase("rotate 180", ExifInterface.ORIENTATION_ROTATE_180, 2, 3, intArrayOf(F, E, D, C, B, A)),
            OrientationCase("flip vertical", ExifInterface.ORIENTATION_FLIP_VERTICAL, 2, 3, intArrayOf(E, F, C, D, A, B)),
            OrientationCase("transpose", ExifInterface.ORIENTATION_TRANSPOSE, 3, 2, intArrayOf(A, C, E, B, D, F)),
            OrientationCase("rotate 90", ExifInterface.ORIENTATION_ROTATE_90, 3, 2, intArrayOf(E, C, A, F, D, B)),
            OrientationCase("transverse", ExifInterface.ORIENTATION_TRANSVERSE, 3, 2, intArrayOf(F, D, B, E, C, A)),
            OrientationCase("rotate 270", ExifInterface.ORIENTATION_ROTATE_270, 3, 2, intArrayOf(B, D, F, A, C, E)),
        )
    }
}
