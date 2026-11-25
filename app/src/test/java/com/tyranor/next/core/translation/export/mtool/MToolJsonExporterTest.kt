package com.tyranor.next.core.translation.export.mtool

import com.tyranor.next.core.translation.TranslationArchiveEntry
import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MToolJsonExporterTest {
    @Test
    fun exportsSourceTextMapWithBlankTargetsAndCollapsesIdenticalDuplicates() {
        val result = MToolJsonExporter.export(
            listOf(
                entry("key:1", "こんにちは", "你好"),
                entry("key:2", "こんにちは", "你好"),
                entry("key:3", "未翻译", null),
                entry("key:4", "同一原文", null),
                entry("key:5", "同一原文", "same source"),
            ),
        )

        assertEquals("你好", JSONObject(result.text).getString("こんにちは"))
        assertEquals("", JSONObject(result.text).getString("未翻译"))
        assertEquals("same source", JSONObject(result.text).getString("同一原文"))
        assertEquals(3, result.entryCount)
    }

    @Test
    fun rejectsConflictingTranslationsForSameSourceText() {
        val failure = assertThrows(TranslationWorkbenchException::class.java) {
            MToolJsonExporter.export(
                listOf(entry("key:1", "同じ原文", "译文一"), entry("key:2", "同じ原文", "译文二")),
            )
        }

        assertEquals(TranslationWorkbenchError.MTOOL_SOURCE_CONFLICT, failure.code)
    }

    @Test
    fun allowsImportableSourceOnlyMapsAndRejectsEmptyProjects() {
        val empty = assertThrows(TranslationWorkbenchException::class.java) {
            MToolJsonExporter.export(emptyList())
        }
        assertEquals(TranslationWorkbenchError.EMPTY_PROJECT, empty.code)
    }

    private fun entry(key: String, source: String, translated: String?) = TranslationArchiveEntry(
        sourceKey = key,
        sourceText = source,
        translatedText = translated,
    )
}
