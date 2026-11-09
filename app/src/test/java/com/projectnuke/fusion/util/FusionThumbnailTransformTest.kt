package com.projectnuke.fusion.util

import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionThumbnailTransformTest {
    @Test
    fun `all standard exif orientations have explicit transforms`() {
        assertEquals(ThumbnailTransform(0, false), ThumbnailOrientationPolicy.fromExifOrientation(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(ThumbnailTransform(0, true), ThumbnailOrientationPolicy.fromExifOrientation(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
        assertEquals(ThumbnailTransform(180, false), ThumbnailOrientationPolicy.fromExifOrientation(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(ThumbnailTransform(180, true), ThumbnailOrientationPolicy.fromExifOrientation(ExifInterface.ORIENTATION_FLIP_VERTICAL))
        assertEquals(ThumbnailTransform(90, true), ThumbnailOrientationPolicy.fromExifOrientation(ExifInterface.ORIENTATION_TRANSPOSE))
        assertEquals(ThumbnailTransform(90, false), ThumbnailOrientationPolicy.fromExifOrientation(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(ThumbnailTransform(270, true), ThumbnailOrientationPolicy.fromExifOrientation(ExifInterface.ORIENTATION_TRANSVERSE))
        assertEquals(ThumbnailTransform(270, false), ThumbnailOrientationPolicy.fromExifOrientation(ExifInterface.ORIENTATION_ROTATE_270))
        assertEquals(ThumbnailTransform(0, false), ThumbnailOrientationPolicy.fromExifOrientation(999))
    }

    @Test
    fun `sample size is power of two and uses symmetric max bound`() {
        assertEquals(1, thumbnailSampleSize(100, 80, 160))
        assertEquals(8, thumbnailSampleSize(2000, 1000, 160))
        assertEquals(
            thumbnailSampleSize(4000, 1000, 160),
            thumbnailSampleSize(1000, 4000, 160),
        )
        assertTrue(thumbnailSampleSize(10_000, 10_000, 160)!! and (thumbnailSampleSize(10_000, 10_000, 160)!! - 1) == 0)
    }

    @Test
    fun `nonpositive target size is rejected`() {
        assertNull(thumbnailSampleSize(100, 100, 0))
        assertNull(thumbnailSampleSize(100, 100, -1))
    }
}
