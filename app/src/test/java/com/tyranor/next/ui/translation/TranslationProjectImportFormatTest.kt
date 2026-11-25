package com.tyranor.next.ui.translation

import com.tyranor.next.core.translation.TranslationProjectFormatRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationProjectImportFormatTest {
    @Test
    fun acceptsRegisteredExtensionsAndMimeTypes() {
        assertTrue(TranslationProjectFormatRegistry.supportsImportFile("game.CSV", null))
        assertTrue(TranslationProjectFormatRegistry.supportsImportFile("game", "text/csv; charset=utf-8"))
        assertTrue(TranslationProjectFormatRegistry.supportsImportFile("game.JSON", null))
        assertTrue(TranslationProjectFormatRegistry.supportsImportFile("game", "application/json; charset=utf-8"))
        assertTrue(TranslationProjectFormatRegistry.supportsImportFile("game", "application/vnd.tyranor+json"))
        assertFalse(TranslationProjectFormatRegistry.supportsImportFile("game.txt", "text/plain"))
    }
}
