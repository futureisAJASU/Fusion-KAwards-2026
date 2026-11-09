package com.projectnuke.fusion.retrieval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE-OWNED validation of the real AndroidX PDF stack. The host build only
 * COMPILES this source; running it requires a device (user-owned). It proves
 * the official SandboxedPdfLoader path extracts a real synthetic PDF text
 * layer with 1-based pages in platform order.
 *
 * Note: running connected tests is explicitly NOT part of the host batch.
 */
@RunWith(AndroidJUnit4::class)
class PdfAndroidxRealEngineDeviceTest {

    private fun writePdf(context: Context, name: String, pages: List<String>): File {
        val dir = File(context.cacheDir, "pdf-device-fixtures")
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(TinyPdfBuilder.build(pages))
        return file
    }

    @Test
    fun twoPageTextLayerExtractsInOrderWithRealPages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = writePdf(context, "two-page.pdf", listOf("alpha first page", "beta second page"))
        val engine = AndroidXPdfTextLayerEngine(context)
        engine.open(file.absolutePath).use { document ->
            assertEquals(2, document.pageCount)
            val first = document.pageTextLayer(1)
            assertTrue(first != null && first!!.text.contains("alpha first page"))
            val second = document.pageTextLayer(2)
            assertTrue(second != null && second!!.text.contains("beta second page"))
        }
    }

    @Test
    fun missingFileFailsTyped() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = AndroidXPdfTextLayerEngine(context)
        val failure = try {
            engine.open(File(context.cacheDir, "not-present.pdf").absolutePath)
            null
        } catch (e: PdfEngineStatusException) {
            e
        }
        assertTrue(failure != null)
        assertEquals(DocumentExtractionStatus.UNREADABLE, failure!!.status)
    }
}