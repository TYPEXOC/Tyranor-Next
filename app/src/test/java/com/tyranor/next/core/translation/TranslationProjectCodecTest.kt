package com.tyranor.next.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationProjectCodecTest {
    private val archive = TranslationProjectArchive(
        projectKey = "project-1",
        title = "星空, 彼方",
        engineId = "RPG_MV",
        sourceLanguage = "ja",
        targetLanguage = "zh-Hans",
        entries = listOf(
            TranslationArchiveEntry(
                sourceKey = "www/data/Map001.json#/events/1/pages/0/list/2/parameters/0",
                sourceText = "「你好，\n世界」",
                translatedText = "\"Hello,\nworld\"",
                sourceFile = "www/data/Map001.json",
                sourcePointer = "/events/1/pages/0/list/2/parameters/0",
                textType = "dialogue",
            ),
            TranslationArchiveEntry(sourceKey = "entry:2", sourceText = "重复原文"),
        ),
    )

    @Test
    fun csvRoundTripPreservesQuotedCommasQuotesAndNewlines() {
        assertEquals(archive, TranslationProjectCodec.decodeCsv(TranslationProjectCodec.encodeCsv(archive), "fallback"))
    }

    @Test
    fun ordinaryHeaderCsvCanBeImportedWithoutStableKeys() {
        val imported = TranslationProjectCodec.decodeCsv(
            "sourceText,translatedText\r\n\"第一行,内容\n第二行\",\"译文\"\r\n未翻译,\r\n",
            "import.csv",
        )

        assertEquals("import.csv", imported.title)
        assertEquals("csv:2", imported.entries[0].sourceKey)
        assertEquals("第一行,内容\n第二行", imported.entries[0].sourceText)
        assertNull(imported.entries[1].translatedText)
    }
}
