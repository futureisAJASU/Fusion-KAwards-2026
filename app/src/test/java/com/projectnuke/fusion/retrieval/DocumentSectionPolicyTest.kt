package com.projectnuke.fusion.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSectionPolicyTest {
    @Test fun detectsHeadingsTablesListsAndKeyValueBlocksWithPageIdentity() {
        val outline = DocumentSectionPolicy.detect(
            listOf(
                DocumentSectionPageInput(
                    pageNumber = 4,
                    sourceChunkId = "page:4",
                    sourceContentHashHex = "a".repeat(64),
                    text = """
                        # Thermal limits
                        Maximum: 95 C
                        Typical: 70 C

                        | Model | Power |
                        | --- | --- |
                        | A | 10 W |
                        | B | 12 W |

                        - First
                        - Second
                        A sentence explaining the results.
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(
            listOf(DocumentSectionKind.KEY_VALUE, DocumentSectionKind.TABLE, DocumentSectionKind.LIST, DocumentSectionKind.TEXT),
            outline.sections.map { it.kind },
        )
        assertTrue(outline.sections.all { it.pageNumber == 4 && it.sourceChunkId == "page:4" })
        assertTrue(outline.sections.first().title.startsWith("Thermal limits"))
        assertFalse(outline.partial)
    }

    @Test fun plainTextDoesNotGainPageIdentityAndBoundsSectionsAndSourceText() {
        val outline = DocumentSectionPolicy.detect(
            listOf(DocumentSectionPageInput(null, null, "b".repeat(64), ("Intro\n" + "word ".repeat(12_000)))),
        )
        assertTrue(outline.sections.size <= DocumentSectionPolicy.MAX_SECTIONS)
        assertTrue(outline.sections.all { it.pageNumber == null })
        assertTrue(outline.partial)
        assertTrue(outline.sections.all { it.text.length <= DocumentSectionPolicy.MAX_SECTION_TEXT_CHARS })
    }

    @Test fun limitsPagesAndDoesNotExposeOrInventAnchors() {
        val inputs = (1..(DocumentSectionPolicy.MAX_SOURCE_PAGES + 2)).map { number ->
            DocumentSectionPageInput(number, "page:$number", "c".repeat(64), "# Section $number\nBody line.")
        }
        val outline = DocumentSectionPolicy.detect(inputs)
        assertTrue(outline.partial)
        assertTrue(outline.sections.size <= DocumentSectionPolicy.MAX_SECTIONS)
        assertEquals(1, outline.sections.first().pageNumber)
        assertEquals("page:1", outline.sections.first().sourceChunkId)
    }
}
