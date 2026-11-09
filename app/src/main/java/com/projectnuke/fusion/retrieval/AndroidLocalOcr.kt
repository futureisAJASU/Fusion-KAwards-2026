package com.projectnuke.fusion.retrieval

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import com.projectnuke.fusion.util.ThumbnailOrientationPolicy
import com.projectnuke.fusion.util.applyThumbnailOrientation
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicBoolean

/** Owns [bitmap] until the dispatched consumer has actually started. */
internal suspend fun <T> withBitmapOwnership(
    bitmap: Bitmap,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    consume: suspend (Bitmap) -> T,
): T {
    val consumerStarted = AtomicBoolean(false)
    return try {
        withContext(dispatcher) {
            consumerStarted.set(true)
            consume(bitmap)
        }
    } catch (failure: Throwable) {
        // Cancellation before dispatch leaves ownership with the caller. Once
        // the consumer starts, it owns the longer native-task lease itself.
        if (!consumerStarted.get() && !bitmap.isRecycled) bitmap.recycle()
        throw failure
    }
}

/** SDK inference keeps its bitmap lease until the task really finishes, including cancellation. */
internal class AndroidLocalOcr {
    private val nativeLease = Mutex()
    suspend fun recognize(bitmap: Bitmap): String = withBitmapOwnership(bitmap, Dispatchers.IO) {
        if (!nativeLease.tryLock()) { bitmap.recycle(); throw IllegalStateException("텍스트 인식이 아직 진행 중입니다.") }
        val recognizer = try { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
            catch (failure: Throwable) { bitmap.recycle(); nativeLease.unlock(); throw failure }
        val task = try { recognizer.process(InputImage.fromBitmap(bitmap, 0)) }
            catch (failure: Throwable) { recognizer.close(); bitmap.recycle(); nativeLease.unlock(); throw failure }
        task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) {
            try { recognizer.close() } finally { bitmap.recycle(); nativeLease.unlock() }
        }
        coroutineContext.ensureActive()
        val text = Tasks.await(task, 20, TimeUnit.SECONDS).text
        coroutineContext.ensureActive()
        text
    }

    fun image(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "이미지를 읽을 수 없습니다." }
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > MAX_PIXELS) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        return try {
            val transform = runCatching {
                ThumbnailOrientationPolicy.fromExifOrientation(
                    ExifInterface(file.path).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    ),
                )
            }.getOrDefault(com.projectnuke.fusion.util.ThumbnailTransform(0, false))
            applyThumbnailOrientation(decoded, transform).also { oriented ->
                if (oriented !== decoded) decoded.recycle()
            }
        } catch (failure: Throwable) {
            if (!decoded.isRecycled) decoded.recycle()
            throw failure
        }
    }

    fun render(page: PdfRenderer.Page): Bitmap {
        val scale = minOf(2.0, kotlin.math.sqrt(MAX_PIXELS.toDouble() / page.width / page.height))
        val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
            (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(android.graphics.Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        catch (failure: Throwable) { bitmap.recycle(); throw failure }
        return bitmap
    }

    companion object {
        const val MAX_PIXELS = 2_000_000L
        fun sourceHash(file: File): String {
            require(file.length() <= PdfDocumentBounds.MAX_PDF_FILE_BYTES)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var total = 0L
                var count = input.read(buffer)
                while (count >= 0) {
                    total += count
                    require(total <= PdfDocumentBounds.MAX_PDF_FILE_BYTES)
                    if (count > 0) digest.update(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
