package com.projectnuke.fusion.retrieval

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.AttachmentRecord
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LocalOcrDeviceTest {
    @Test fun canceledBeforeOcrWorkerStartsRecyclesRenderedBitmap() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val workerOccupied = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val blocker = CoroutineScope(dispatcher).launch {
            workerOccupied.countDown()
            releaseWorker.await(5, TimeUnit.SECONDS)
        }
        assertTrue(workerOccupied.await(5, TimeUnit.SECONDS))
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val recognition = launch(Dispatchers.Unconfined) {
                withBitmapOwnership(bitmap, dispatcher) { error("canceled consumer must not start") }
            }
            recognition.cancel()
            releaseWorker.countDown()
            recognition.join()
            blocker.join()
            assertTrue("bitmap from a canceled pre-dispatch OCR request must be recycled", bitmap.isRecycled)
        } finally {
            releaseWorker.countDown()
            dispatcher.close()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test fun ocrImageDecodeAppliesExifOrientationWithinSampledBounds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "orientation-smoke-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val oriented = AndroidLocalOcr().image(file)
            try {
                assertEquals(40, oriented.width)
                assertEquals(80, oriented.height)
            } finally { oriented.recycle() }
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    @Test fun bundledOcrImageFlowsIntoIndexAndLocalRagWithProvenance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(AttachmentStorageManager.getAttachmentDirectory(context), "ocr-smoke-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawText("Fusion local workspace", 40f, 180f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 80f })
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val repository = DocumentIntelligenceProvider.indexRepository(context)
        try {
            val result = repository.recognizeText(file.canonicalPath, "image/png") { _, _ -> }
            assertTrue(result is LocalOcrResult.Complete)
            val index = (repository.obtain(file.canonicalPath, "image/png") as DocumentIndexResult.Ready).index
            assertEquals(LocalDocumentKind.OCR_IMAGE, index.metadata.kind)
            assertTrue(index.pages.single().ocrDerived)
            assertTrue(index.pages.single().text.contains("Fusion", true))
            val rag = LocalRagCoordinator(FileLocalAttachmentTextStore(), pdfIndexSource = repository)
            val records = listOf(AttachmentRecord(file.name, "image/png", file.canonicalPath))
            val prepared = rag.prepare("ocr-smoke", "Fusion", records) as LocalRagPreparationResult.Prepared
            assertTrue(prepared.selections.all { it.source.ocrDerived && it.source.pageNumber == null })
            assertTrue(rag.validate(prepared.snapshot, records))
            file.writeBytes(byteArrayOf(1,2,3))
            assertFalse(rag.validate(prepared.snapshot, records))
        } finally { repository.deleteIndex(file.canonicalPath); file.delete() }
    }
}
