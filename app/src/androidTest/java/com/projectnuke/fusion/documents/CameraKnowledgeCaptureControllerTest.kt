package com.projectnuke.fusion.documents

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.projectnuke.fusion.retrieval.IndexedDocumentPage
import com.projectnuke.fusion.retrieval.LocalOcrResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraKnowledgeCaptureControllerTest {
    @Test
    fun `recognized camera source remains managed until user discards or adopts it`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = CameraKnowledgeCaptureController(
            context = context,
            recognizeImage = { _, _ ->
                LocalOcrResult.Complete(
                    listOf(IndexedDocumentPage(1, "local recognized text", 21, "hash", true, 1, ocrDerived = true)),
                )
            },
            isDurablyReferenced = { false },
        )
        val file = controller.createOutputFile()
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val state = controller.recognize(file.absolutePath)
            assertEquals(CameraOcrStatus.PREVIEW, state.status)
            assertEquals("local recognized text", state.previewText)
            assertTrue(state.displayName.startsWith("camera_ocr_"))
            assertTrue(file.isFile)
            assertTrue(controller.discard(file.canonicalPath))
            assertFalse(file.exists())
        } finally {
            if (file.exists()) controller.discard(file.absolutePath)
        }
    }

    @Test
    fun `ocr cancellation propagates and later discard settles temporary file`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = CameraKnowledgeCaptureController(
            context = context,
            recognizeImage = { _, _ -> throw CancellationException("cancelled OCR") },
            isDurablyReferenced = { false },
        )
        val file = controller.createOutputFile()
        file.writeBytes(byteArrayOf(1))
        try {
            controller.recognize(file.absolutePath)
            throw AssertionError("cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(file.exists())
        } finally {
            controller.discard(file.absolutePath)
        }
        assertFalse(file.exists())
    }
}
