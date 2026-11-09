package com.projectnuke.fusion.retrieval

import android.content.Context
import java.io.File

/**
 * Production wiring for Document Intelligence. Everything stays lazy: no PDF
 * engine, no index scan, no service at app startup — the first PDF request
 * builds what it needs. One repository per process so index builds are
 * single-flight app-wide.
 */
object DocumentIntelligenceProvider {

    @Volatile
    private var indexRepository: PdfDocumentIndexRepository? = null

    @Volatile
    private var extractorRegistry: LocalDocumentExtractorRegistry? = null

    private val lock = Any()

    fun indexRepository(context: Context): PdfDocumentIndexRepository {
        val appContext = context.applicationContext
        indexRepository?.let { return it }
        synchronized(lock) {
            indexRepository?.let { return it }
            val store = DocumentIndexStore(File(appContext.filesDir, "document_index"))
            val engine = AndroidXPdfTextLayerEngine(appContext)
            val extractor = PdfTextLayerExtractor(engine)
            return PdfDocumentIndexRepository(store, extractor, engine).also { indexRepository = it }
        }
    }

    fun extractorRegistry(context: Context): LocalDocumentExtractorRegistry {
        val appContext = context.applicationContext
        extractorRegistry?.let { return it }
        synchronized(lock) {
            extractorRegistry?.let { return it }
            val textExtractor = PlainTextDocumentExtractor()
            val pdfExtractor = PdfTextLayerExtractor(AndroidXPdfTextLayerEngine(appContext))
            return LocalDocumentExtractorRegistry(
                textExtractor = textExtractor,
                pdfExtractor = pdfExtractor,
            ).also { extractorRegistry = it }
        }
    }

    /**
     * Production PDF admission seam used by picker / handoff / project flows:
     * index-based so the derived cache is (optionally) built exactly once at
     * ingestion time and shared with later RAG requests.
     */
    suspend fun pdfAdmission(
        context: Context,
        path: String,
        mimeType: String?,
    ): DocumentAdmissionPolicy.DocumentAdmissionResult {
        val repository = indexRepository(context)
        return when (val result = repository.obtain(path, mimeType)) {
            is DocumentIndexResult.Ready -> DocumentAdmissionPolicy.DocumentAdmissionResult.Accepted
            is DocumentIndexResult.NotReady -> DocumentAdmissionPolicy.DocumentAdmissionResult.Rejected(
                result.status,
            )
        }
    }
}