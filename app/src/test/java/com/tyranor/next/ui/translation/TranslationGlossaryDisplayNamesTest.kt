package com.tyranor.next.ui.translation

import com.tyranor.next.core.translation.TranslationGlossary
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationGlossaryDisplayNamesTest {
    @Test
    fun duplicateNamesReceiveMatchingOrdinalsWhileUniqueNamesStayUnchanged() {
        val glossaries = listOf(
            glossary("a", "2077"),
            glossary("b", " 2077 "),
            glossary("c", "Unique"),
            glossary("d", "Separate"),
        )

        assertEquals(listOf(1, 2, null, null), duplicateGlossaryOrdinals(glossaries))
    }

    private fun glossary(id: String, name: String) = TranslationGlossary(
        id = id,
        name = name,
        revision = 1L,
        termCount = 0,
        sourceProjectId = null,
        updatedAt = 0L,
    )
}
