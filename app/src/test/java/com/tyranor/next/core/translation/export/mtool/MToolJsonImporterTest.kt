package com.tyranor.next.core.translation.export.mtool

import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MToolJsonImporterTest {
    @Test
    fun importsSourceToTranslationMapAndUsesFileNameAsProjectTitle() {
        val archive = MToolJsonImporter.decode(
            """{"你好":"Hello","未翻译":""}""",
            "Adventure.json",
        )

        assertEquals("Adventure", archive.title)
        assertEquals(null, archive.engineId)
        assertEquals(2, archive.entries.size)
        val untranslated = archive.entries.first { it.sourceText == "未翻译" }
        val translated = archive.entries.first { it.sourceText == "你好" }
        assertNull(untranslated.translatedText)
        assertEquals("Hello", translated.translatedText)
        assertEquals("mtool:0", translated.sourceKey)
    }

    @Test
    fun rejectsEmptyMapsAndNonStringValues() {
        val empty = assertThrows(TranslationWorkbenchException::class.java) {
            MToolJsonImporter.decode("{}", "empty.json")
        }
        assertEquals(TranslationWorkbenchError.EMPTY_PROJECT, empty.code)

        val invalidValue = assertThrows(TranslationWorkbenchException::class.java) {
            MToolJsonImporter.decode("""{"source":42}""", "invalid.json")
        }
        assertEquals(TranslationWorkbenchError.INVALID_PROJECT_FILE, invalidValue.code)
    }
}
