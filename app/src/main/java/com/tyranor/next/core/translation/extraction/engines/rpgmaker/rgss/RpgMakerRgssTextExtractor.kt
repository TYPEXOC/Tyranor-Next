package com.tyranor.next.core.translation.extraction.engines.rpgmaker.rgss

import com.tyranor.next.core.translation.extraction.ExtractedTextEntry
import com.tyranor.next.core.translation.extraction.ExtractionErrorCode
import com.tyranor.next.core.translation.extraction.ExtractionPlan
import com.tyranor.next.core.translation.extraction.ReadOnlyGameSource
import com.tyranor.next.core.translation.extraction.TextExtractor
import com.tyranor.next.core.translation.extraction.TextExtractorDescriptor
import com.tyranor.next.core.translation.extraction.TranslationExtractionException
import com.tyranor.next.core.translation.extraction.TranslationExtractorEngineIds
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.RpgMakerLegacySourceSupport
import com.tyranor.next.core.translation.extraction.nativebridge.NativeRpgMakerBinaryDataDecoder
import com.tyranor.next.core.translation.extraction.nativebridge.RpgMakerBinaryDataDecoder
import com.tyranor.next.core.translation.extraction.nativebridge.RpgMakerBinaryKind
import org.json.JSONObject

/** RPG Maker XP/VX/VX Ace and mkxp-z RGSS resource generation extractor. */
internal class RpgMakerRgssTextExtractor(
    private val decoder: RpgMakerBinaryDataDecoder = NativeRpgMakerBinaryDataDecoder,
) : TextExtractor {
    override val descriptor = TextExtractorDescriptor(
        extractorId = "rpgmaker-rgss-data-v1",
        engineId = TranslationExtractorEngineIds.RPG_MAKER_RGSS,
        formatVersion = "rgss-rxdata-rvdata-rvdata2-v1",
        outputSchemaVersion = 1,
    )

    override suspend fun preflight(source: ReadOnlyGameSource): ExtractionPlan =
        ExtractionPlan(RpgMakerLegacySourceSupport.findRgssDataFiles(source) ?: unsupported())

    override suspend fun extractFile(source: ReadOnlyGameSource, relativePath: String): List<ExtractedTextEntry> {
        val fileName = relativePath.substringAfterLast('/')
        val input = source.open(relativePath)
            ?: throw TranslationExtractionException(ExtractionErrorCode.INVALID_GAME_DIRECTORY)
        val limit = if (RpgMakerLegacySourceSupport.isRgssArchive(relativePath)) {
            MAX_ARCHIVE_BYTES
        } else {
            MAX_RESOURCE_BYTES
        }
        val bytes = input.use { RpgMakerLegacySourceSupport.readBounded(it, limit) }
        val kind = if (RpgMakerLegacySourceSupport.isRgssArchive(relativePath)) {
            RpgMakerBinaryKind.RGSS_ARCHIVE
        } else {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            if (extension !in RGSS_DATA_EXTENSIONS) unsupported()
            RpgMakerBinaryKind.RGSS_DATA
        }
        val json = decoder.decode(kind, fileName, bytes)
        return if (kind == RpgMakerBinaryKind.RGSS_ARCHIVE) {
            RpgMakerRgssJsonTextReader.extractArchive(json)
        } else {
            RpgMakerRgssJsonTextReader.extract(relativePath, JSONObject(json.toString(Charsets.UTF_8)))
        }
    }

    private fun unsupported(): Nothing =
        throw TranslationExtractionException(ExtractionErrorCode.UNSUPPORTED_FORMAT)

    private companion object {
        const val MAX_RESOURCE_BYTES = 32 * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
        val RGSS_DATA_EXTENSIONS = setOf("rxdata", "rvdata", "rvdata2")
    }
}
