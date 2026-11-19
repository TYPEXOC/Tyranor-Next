package com.tyranor.next.core.translation.extraction

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.mv.RpgMakerMvTextExtractor
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.mz.RpgMakerMzTextExtractor
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.rm2k.RpgMaker2000TextExtractor
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.rgss.RpgMakerRgssTextExtractor
import com.tyranor.next.core.translation.extraction.nativebridge.RpgMakerBinaryKind
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
        assertEquals("rpgmaker-2000-2003-lcf-v1", TextExtractorRegistry.find(TranslationExtractorEngineIds.RPG_MAKER_2000_2003)?.descriptor?.extractorId)
        assertEquals("rpgmaker-rgss-data-v1", TextExtractorRegistry.find(TranslationExtractorEngineIds.RPG_MAKER_RGSS)?.descriptor?.extractorId)
    }

    @Test
    fun extractsKnownDatabaseAndEventTextWithStableSourceLocations() = runBlocking {
        val source = FakeGameSource(
            mapOf(
                "www/data/System.json" to """{"gameTitle":"夜明け","terms":{"basic":["HP","MP"],"messages":{"actionFailure":"失敗した"}},"note":"ignored plugin note"}""".toByteArray(),
                "www/data/Map001.json" to """{"events":[null,{"name":"宿屋","pages":[{"list":[{"code":101,"parameters":["",0,0,2,"店主"]},{"code":401,"parameters":["旅人よ、ようこそ。"]},{"code":102,"parameters":[["泊まる","帰る"],0,0,2]},{"code":356,"parameters":["plugin visible-looking text"]},{"code":402,"parameters":[0,"泊まる"]}]}]}]}]}""".toByteArray(),
                "www/data/Plugin.json" to "{}".toByteArray(),
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
            RpgMakerMzTextExtractor.preflight(FakeGameSource(mapOf("data/Map001.json" to "{}".toByteArray())))
        }.exceptionOrNull()

        assertTrue(exception is TranslationExtractionException)
        assertEquals(ExtractionErrorCode.UNSUPPORTED_FORMAT, (exception as TranslationExtractionException).code)
    }

    @Test
    fun rm2kExtractorUsesItsOwnLcfDecoderAndKeepsDialogueLocationsStable() = runBlocking {
        val source = FakeGameSource(
            mapOf(
                "RPG_RT.ldb" to byteArrayOf(1),
                "RPG_RT.lmt" to byteArrayOf(2),
                "Map0001.lmu" to byteArrayOf(3),
                "MapTree.txt" to byteArrayOf(4),
            ),
        )
        val extractor = RpgMaker2000TextExtractor { kind, file, _ ->
            assertEquals(
                when (file) {
                    "RPG_RT.ldb" -> RpgMakerBinaryKind.RM2K_DATABASE
                    "RPG_RT.lmt" -> RpgMakerBinaryKind.RM2K_TREE
                    else -> RpgMakerBinaryKind.RM2K_MAP
                },
                kind,
            )
            """{"actors":[{"name":"勇者","note":"skip"}],"events":[{"pages":[{"event_commands":[{"code":10110,"string":"前半"},{"code":20110,"string":"後半"},{"code":20140,"string":"進む"},{"code":10610,"string":"主人公"}]}]}]}""".toByteArray()
        }

        val plan = extractor.preflight(source)
        val entries = plan.files.flatMap { extractor.extractFile(source, it) }

        assertEquals(listOf("RPG_RT.ldb", "RPG_RT.lmt", "Map0001.lmu"), plan.files)
        assertTrue(entries.any { it.sourceText == "勇者" })
        assertTrue(entries.any { it.sourceText == "前半\n後半" })
        assertTrue(entries.any { it.sourceText == "進む" })
        assertTrue(entries.any { it.sourceText == "主人公" })
        assertFalse(entries.any { it.sourceText == "skip" })
        assertEquals(entries.size, entries.map { it.sourceKey }.toSet().size)
    }

    @Test
    fun rgssExtractorFiltersDataFilesAndExtractsOnlyKnownEventText() = runBlocking {
        val document = """{"__type":"object","__class":"RPG::Map","__members":[["@events",{"__type":"array","__value":[{"__type":"object","__class":"RPG::Event::Page","__members":[["@list",{"__type":"array","__value":[{"__type":"object","__class":"RPG::EventCommand","__members":[["@code",102],["@parameters",{"__type":"array","__value":[{"__type":"array","__value":[{"__type":"str","__value":"開始"},{"__type":"str","__value":"戻る"}]}]}]]},{"__type":"object","__class":"RPG::EventCommand","__members":[["@code",401],["@parameters",{"__type":"array","__value":[{"__type":"str","__value":"ここへようこそ"}]}]]}]}]]}]}]]}"""
        val source = FakeGameSource(
            mapOf(
                "Data/System.rvdata2" to byteArrayOf(1),
                "Data/Map001.rvdata2" to byteArrayOf(2),
                "Data/Scripts.rvdata2" to byteArrayOf(3),
                "Data/Plugin.rvdata2" to byteArrayOf(4),
                "Graphics/Picture.rvdata2" to byteArrayOf(5),
            ),
        )
        val extractor = RpgMakerRgssTextExtractor { kind, file, _ ->
            assertEquals(RpgMakerBinaryKind.RGSS_DATA, kind)
            assertTrue(file == "System.rvdata2" || file == "Map001.rvdata2")
            document.toByteArray()
        }

        val plan = extractor.preflight(source)
        val entries = plan.files.flatMap { extractor.extractFile(source, it) }

        assertEquals(listOf("Data/Map001.rvdata2", "Data/System.rvdata2"), plan.files)
        assertTrue(entries.any { it.sourceText == "開始" })
        assertTrue(entries.any { it.sourceText == "戻る" })
        assertTrue(entries.any { it.sourceText == "ここへようこそ" })
        assertEquals(entries.size, entries.map { it.sourceKey }.toSet().size)
    }

    @Test
    fun rgssDirectoryRejectsMixedResourceGenerations() = runBlocking {
        val source = FakeGameSource(
            mapOf(
                "Data/System.rvdata2" to byteArrayOf(1),
                "Data/Map001.rxdata" to byteArrayOf(2),
            ),
        )

        val exception = runCatching { RpgMakerRgssTextExtractor().preflight(source) }.exceptionOrNull()

        assertTrue(exception is TranslationExtractionException)
        assertEquals(ExtractionErrorCode.UNSUPPORTED_FORMAT, (exception as TranslationExtractionException).code)
    }

    @Test
    fun rgssArchiveIsASeparatePlanAndUsesArchiveSpecificDecoder() = runBlocking {
        val archiveDocument = """{"resources":[{"file":"Data/Map001.rxdata","document":{"__type":"object","__class":"RPG::EventCommand","__members":[["@code",401],["@parameters",{"__type":"array","__value":[{"__type":"str","__value":"加密归档文本"}]}]]}}]}"""
        val source = FakeGameSource(mapOf("Game.rgssad" to byteArrayOf(9)))
        val extractor = RpgMakerRgssTextExtractor { kind, file, _ ->
            assertEquals(RpgMakerBinaryKind.RGSS_ARCHIVE, kind)
            assertEquals("Game.rgssad", file)
            archiveDocument.toByteArray()
        }

        val plan = extractor.preflight(source)
        val entries = extractor.extractFile(source, plan.files.single())

        assertEquals(listOf("Game.rgssad"), plan.files)
        assertEquals("加密归档文本", entries.single().sourceText)
        assertTrue(entries.single().sourceKey.startsWith("Data/Map001.rxdata#"))
    }

    @Test
    fun rgssArchiveEntriesCannotEscapeTheirDataDirectory() = runBlocking {
        val archiveDocument = """{"resources":[{"file":"../Scripts.rxdata","document":{}}]}"""
        val source = FakeGameSource(mapOf("Game.rgssad" to byteArrayOf(9)))
        val extractor = RpgMakerRgssTextExtractor { _, _, _ -> archiveDocument.toByteArray() }

        val exception = runCatching {
            extractor.extractFile(source, extractor.preflight(source).files.single())
        }.exceptionOrNull()

        assertTrue(exception is TranslationExtractionException)
        assertEquals(ExtractionErrorCode.CORRUPT_RESOURCE, (exception as TranslationExtractionException).code)
    }

    private class FakeGameSource(private val files: Map<String, ByteArray>) : ReadOnlyGameSource {
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
            ?.let(::ByteArrayInputStream)

    }
}
