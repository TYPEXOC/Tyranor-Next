package com.tyranor.next.core.translation.extraction

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.mv.RpgMakerMvTextExtractor
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.mz.RpgMakerMzTextExtractor
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpgMakerTextExtractorTest {
    @Test
    fun registryHasIndependentMvAndMzEntriesAndNoGuessingFallback() {
        assertEquals("rpgmaker-mv-json-v1", TextExtractorRegistry.find(EngineType.RPG_MV)?.descriptor?.extractorId)
        assertEquals("rpgmaker-mz-json-v1", TextExtractorRegistry.find(EngineType.RPG_MZ)?.descriptor?.extractorId)
        assertEquals(null, TextExtractorRegistry.find(EngineType.RPGMAKER))
        assertTrue(RpgMakerMvTextExtractor.descriptor.formatVersion != RpgMakerMzTextExtractor.descriptor.formatVersion)
    }

    @Test
    fun extractsKnownDatabaseAndEventTextWithStableSourceLocations() = runBlocking {
        val source = FakeGameSource(
            mapOf(
                "www/data/System.json" to """{"gameTitle":"夜明け","terms":{"basic":["HP","MP"],"messages":{"actionFailure":"失敗した"}},"note":"ignored plugin note"}""",
                "www/data/Map001.json" to """{"events":[null,{"name":"宿屋","pages":[{"list":[{"code":101,"parameters":["",0,0,2,"店主"]},{"code":401,"parameters":["旅人よ、ようこそ。"]},{"code":102,"parameters":[["泊まる","帰る"],0,0,2]},{"code":356,"parameters":["plugin visible-looking text"]},{"code":402,"parameters":[0,"泊まる"]}]}]}]}]}""",
                "www/data/Plugin.json" to "{}",
            ),
        )

        val plan = RpgMakerMvTextExtractor.preflight(source)
        val extracted = plan.files.flatMap { RpgMakerMvTextExtractor.extractFile(source, it) }
        val texts = extracted.map { it.sourceText }

        assertEquals(listOf("www/data/Map001.json", "www/data/System.json"), plan.files)
        assertTrue(texts.containsAll(listOf("夜明け", "HP", "MP", "失敗した", "店主", "旅人よ、ようこそ。", "泊まる", "帰る")))
        assertFalse(texts.contains("ignored plugin note"))
        assertFalse(texts.contains("plugin visible-looking text"))
        assertFalse(texts.any { it == "泊まる" && extracted.count { entry -> entry.sourceText == it } > 1 })
        assertEquals(extracted.map { it.sourceKey }.toSet().size, extracted.size)
        assertEquals(extracted, plan.files.flatMap { RpgMakerMvTextExtractor.extractFile(source, it) })
    }

    @Test
    fun requiresRpgMakerDataDirectoryAndSystemFile() = runBlocking {
        val exception = runCatching {
            RpgMakerMzTextExtractor.preflight(FakeGameSource(mapOf("data/Map001.json" to "{}")))
        }.exceptionOrNull()

        assertTrue(exception is TranslationExtractionException)
        assertEquals(ExtractionErrorCode.UNSUPPORTED_FORMAT, (exception as TranslationExtractionException).code)
    }

    private class FakeGameSource(private val files: Map<String, String>) : ReadOnlyGameSource {
        override suspend fun list(relativePath: String): List<GameDocumentInfo> {
            val prefix = if (relativePath.isEmpty()) "" else "$relativePath/"
            val children = files.keys.asSequence()
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }
                .distinct()
                .sorted()
                .map { name ->
                    GameDocumentInfo(
                        name = name,
                        isDirectory = files.keys.any { it.startsWith("$prefix$name/") },
                    )
                }
                .toList()
            return children
        }

        override suspend fun open(relativePath: String) = files[relativePath]
            ?.toByteArray(Charsets.UTF_8)
            ?.let(::ByteArrayInputStream)
    }
}
