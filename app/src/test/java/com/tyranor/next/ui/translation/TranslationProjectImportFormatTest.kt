package com.tyranor.next.ui.translation

import com.tyranor.next.core.translation.TranslationProjectFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationProjectImportFormatTest {
    @Test
    fun detectsMToolJsonByExtensionOrJsonMimeTypeAndRejectsCsv() {
        assertEquals(TranslationProjectFormat.MTOOL_JSON, detectProjectImportFormat("game.JSON", null))
        assertEquals(TranslationProjectFormat.MTOOL_JSON, detectProjectImportFormat("game", "application/json; charset=utf-8"))
        assertEquals(TranslationProjectFormat.MTOOL_JSON, detectProjectImportFormat("game", "application/vnd.tyranor+json"))
        assertNull(detectProjectImportFormat("game.csv", "application/json"))
        assertNull(detectProjectImportFormat("game.txt", "text/plain"))
    }
}
