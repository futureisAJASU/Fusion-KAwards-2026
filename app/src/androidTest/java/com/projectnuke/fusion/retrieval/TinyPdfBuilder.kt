package com.projectnuke.fusion.retrieval

// Repository-owned synthetic PDF generator for device-side real-engine tests.
// Generates a minimal valid PDF with a Type1 text layer in every page.
// Deterministic, tiny, no copyrighted documents. Device validation stays
// user-owned; this source only needs to COMPILE on host builds.
object TinyPdfBuilder {

    fun build(pages: List<String>): ByteArray {
        require(pages.isNotEmpty())
        val pageObjects = mutableListOf<String>()
        val contentObjects = mutableListOf<String>()
        val fontObjectIndex = 3 + pages.size * 2
        for ((index, text) in pages.withIndex()) {
            val contentObjectIndex = 3 + pages.size + index
            pageObjects +=
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 $fontObjectIndex 0 R >> >> " +
                    "/Contents $contentObjectIndex 0 R >>"
            val content = "BT /F1 12 Tf 72 720 Td (${escape(text)}) Tj ET"
            val contentLength = content.toByteArray(Charsets.ISO_8859_1).size
            contentObjects += "<< /Length $contentLength >>\nstream\n$content\nendstream"
        }
        val bodies = mutableListOf<String>()
        bodies += "<< /Type /Catalog /Pages 2 0 R >>"
        bodies += "<< /Type /Pages /Kids [" + pages.indices.joinToString(" ") { "${it + 3} 0 R" } + "] /Count ${pages.size} >>"
        bodies += pageObjects
        bodies += contentObjects
        bodies += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        return render(bodies)
    }

    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("(", "\\(")
        .replace(")", "\\)")

    private fun render(bodies: List<String>): ByteArray {
        val sb = StringBuilder()
        sb.append("%PDF-1.4\n")
        val offsets = IntArray(bodies.size)
        for ((index, body) in bodies.withIndex()) {
            offsets[index] = sb.length
            sb.append("${index + 1} 0 obj\n").append(body).append("\nendobj\n")
        }
        val xrefOffset = sb.length
        sb.append("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) {
            sb.append("%010d 00000 n \n".format(offset))
        }
        sb.append("trailer\n<< /Size ${bodies.size + 1} /Root 1 0 R >>\nstartxref\n")
            .append(xrefOffset).append("\n%%EOF")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
