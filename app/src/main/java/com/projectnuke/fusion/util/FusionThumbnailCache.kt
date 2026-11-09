package com.projectnuke.fusion.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import java.io.File

internal data class ThumbnailTransform(
    val rotationDegrees: Int,
    val flipHorizontal: Boolean,
)

internal object ThumbnailOrientationPolicy {
    fun fromExifOrientation(orientation: Int): ThumbnailTransform = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ThumbnailTransform(0, true)
        ExifInterface.ORIENTATION_ROTATE_180 -> ThumbnailTransform(180, false)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> ThumbnailTransform(180, true)
        ExifInterface.ORIENTATION_TRANSPOSE -> ThumbnailTransform(90, true)
        ExifInterface.ORIENTATION_ROTATE_90 -> ThumbnailTransform(90, false)
        ExifInterface.ORIENTATION_TRANSVERSE -> ThumbnailTransform(270, true)
        ExifInterface.ORIENTATION_ROTATE_270 -> ThumbnailTransform(270, false)
        else -> ThumbnailTransform(0, false)
    }
}

/** Applies the production EXIF matrix while leaving bitmap ownership to caller. */
internal fun applyThumbnailOrientation(
    source: Bitmap,
    transform: ThumbnailTransform,
): Bitmap = if (transform == ThumbnailTransform(0, false)) {
    source
} else {
    Bitmap.createBitmap(
        source,
        0,
        0,
        source.width,
        source.height,
        Matrix().apply {
            setRotate(transform.rotationDegrees.toFloat())
            if (transform.flipHorizontal) postScale(-1f, 1f)
        },
        true,
    )
}

internal fun thumbnailSampleSize(width: Int, height: Int, targetSize: Int): Int? {
    if (width <= 0 || height <= 0 || targetSize <= 0) return null
    val threshold = targetSize.toLong() * 2L
    var sampleSize = 1
    while (width.toLong() / sampleSize > threshold || height.toLong() / sampleSize > threshold) {
        sampleSize *= 2
    }
    return sampleSize
}

object FusionThumbnailCache {
    private const val DefaultTargetSize = 160
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(path: String, targetSize: Int = DefaultTargetSize): Bitmap? {
        if (targetSize <= 0) return null
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!file.isFile) return null
        val key = "${file.path}:${file.lastModified()}:${file.length()}:$targetSize"
        cache.get(key)?.takeIf { !it.isRecycled }?.let { return it }
        val bitmap = decodeThumbnail(file.path, targetSize) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    fun clear() {
        cache.evictAll()
    }

    private fun decodeThumbnail(path: String, targetSize: Int): Bitmap? {
        if (targetSize <= 0) return null
        var ownedBitmap: Bitmap? = null
        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            BitmapFactory.decodeFile(path, boundsOptions)
            val originalWidth = boundsOptions.outWidth
            val originalHeight = boundsOptions.outHeight
            val sampleSize = thumbnailSampleSize(originalWidth, originalHeight, targetSize) ?: return null

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            ownedBitmap = BitmapFactory.decodeFile(path, decodeOptions) ?: return null
            val transform = runCatching {
                ThumbnailOrientationPolicy.fromExifOrientation(
                    ExifInterface(path).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                )
            }.getOrDefault(ThumbnailTransform(0, false))
            val oriented = applyThumbnailOrientation(ownedBitmap!!, transform).also {
                if (it !== ownedBitmap) ownedBitmap!!.recycle()
                ownedBitmap = it
            }
            if (oriented.width <= targetSize && oriented.height <= targetSize) {
                ownedBitmap = null
                oriented
            } else {
                val scale = minOf(
                    targetSize.toFloat() / oriented.width.toFloat(),
                    targetSize.toFloat() / oriented.height.toFloat()
                )
                val scaledWidth = (oriented.width * scale).toInt().coerceAtLeast(1)
                val scaledHeight = (oriented.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(oriented, scaledWidth, scaledHeight, true).also {
                    if (it !== oriented) oriented.recycle()
                    ownedBitmap = null
                }
            }
        } catch (_: Exception) {
            ownedBitmap?.takeIf { !it.isRecycled }?.recycle()
            null
        }
    }
}
