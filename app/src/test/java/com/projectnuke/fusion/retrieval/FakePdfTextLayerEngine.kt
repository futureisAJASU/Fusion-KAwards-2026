package com.projectnuke.fusion.retrieval

import java.io.File

/**
 * Programmable fake PDF text-layer engine exercising the exact production
 * extractor contract (open -> pageCount -> 1-based pageTextLayer -> close).
 * Host tests never need the Android sandbox stack; device behavior stays
 * user-owned (androidTest real engine optional).
 */
class FakePdfTextLayerEngine : PdfTextLayerEngine {

    data class FakePage(
        val text: String = "",
        val imageCount: Int = 0,
    )

    private class Stub(
        override val pageCount: Int,
        private val pages: List<FakePage>,
        internal val password: Boolean,
        internal val unreadable: Boolean,
    ) : PdfTextLayerDocument {
        override suspend fun pageTextLayer(pageNumber: Int): PdfPageTextLayer? {
            if (pageNumber < 1 || pageNumber > pageCount) {
                throw PdfEngineStatusException(DocumentExtractionStatus.UNREADABLE)
            }
            val page = pages.getOrNull(pageNumber - 1)
                ?: throw PdfEngineStatusException(DocumentExtractionStatus.UNREADABLE)
            if (page.text.isEmpty() && page.imageCount == 0) return null
            return PdfPageTextLayer(
                text = page.text,
                textContentCount = if (page.text.isEmpty()) 0 else 1,
                imageElementCount = page.imageCount,
            )
        }

        override fun close() = Unit
    }

    private val sources = LinkedHashMap<String, Stub>()

    fun install(
        path: String,
        pages: List<FakePage>,
        password: Boolean = false,
        unreadable: Boolean = false,
        pageCountOverride: Int? = null,
    ) {
        sources[path] = Stub(
            pageCount = pageCountOverride ?: pages.size,
            pages = pages,
            password = password,
            unreadable = unreadable,
        )
    }

    fun installTextPages(path: String, pages: List<String>) {
        install(path, pages.map { FakePage(it) })
    }

    override suspend fun open(sourcePath: String): PdfTextLayerDocument {
        val stub = sources[sourcePath]
            ?: throw PdfEngineStatusException(DocumentExtractionStatus.UNREADABLE)
        if (stub.password) {
            throw PdfEngineStatusException(DocumentExtractionStatus.PASSWORD_PROTECTED)
        }
        if (stub.unreadable) {
            throw PdfEngineStatusException(DocumentExtractionStatus.UNREADABLE)
        }
        return stub
    }

    /** Writes a real temp file so the extractor's file checks can run. */
    fun touchFile(path: String): File {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText("stub")
        return file
    }

    fun removeFile(path: String) {
        File(path).delete()
    }
}