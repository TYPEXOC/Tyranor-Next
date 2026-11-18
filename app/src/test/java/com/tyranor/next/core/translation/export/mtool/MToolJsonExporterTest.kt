package com.tyranor.next.core.translation.export.mtool

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.translation.TranslationArchiveEntry
import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MToolJsonExporterTest {
    @Test
    fun exportsTranslatedSourceTextMapAndCollapsesIdenticalDuplicates() {
        val result = MToolJsonExporter.export(
            EngineType.RPG_MV.name,
            listOf(
                entry("key:1", "こんにちは", "你好"),
                entry("key:2", "こんにちは", "你好"),
                entry("key:3", "未翻译", null),
            ),
        )

        assertEquals("你好", JSONObject(result.text).getString("こんにちは"))
        assertEquals(1, result.entryCount)
    }

    @Test
    fun rejectsConflictingTranslationsForSameSourceText() {
        val failure = assertThrows(TranslationWorkbenchException::class.java) {
            MToolJsonExporter.export(
                EngineType.RPG_MZ.name,
                listOf(entry("key:1", "同じ原文", "译文一"), entry("key:2", "同じ原文", "译文二")),
            )
        }

        assertEquals(TranslationWorkbenchError.MTOOL_SOURCE_CONFLICT, failure.code)
    }

    @Test
    fun rejectsUnsupportedEngineAndEmptyTranslationSet() {
        val unsupported = assertThrows(TranslationWorkbenchException::class.java) {
            MToolJsonExporter.export("ONS", listOf(entry("key:1", "source", "target")))
        }
        assertEquals(TranslationWorkbenchError.MTOOL_ENGINE_UNSUPPORTED, unsupported.code)

        val empty = assertThrows(TranslationWorkbenchException::class.java) {
            MToolJsonExporter.export(EngineType.RPG_MV.name, listOf(entry("key:1", "source", null)))
        }
        assertEquals(TranslationWorkbenchError.NO_TRANSLATED_ENTRIES, empty.code)
    }

    private fun entry(key: String, source: String, translated: String?) = TranslationArchiveEntry(
        sourceKey = key,
        sourceText = source,
        translatedText = translated,
    )
}
