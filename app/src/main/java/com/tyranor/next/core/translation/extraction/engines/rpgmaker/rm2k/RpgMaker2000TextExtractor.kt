package com.tyranor.next.core.translation.extraction.engines.rpgmaker.rm2k

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

/** RPG Maker 2000/2003 LCF database, tree and map generation extractor. */
internal class RpgMaker2000TextExtractor(
    private val decoder: RpgMakerBinaryDataDecoder = NativeRpgMakerBinaryDataDecoder,
) : TextExtractor {
    override val descriptor = TextExtractorDescriptor(
        extractorId = "rpgmaker-2000-2003-lcf-v1",
        engineId = TranslationExtractorEngineIds.RPG_MAKER_2000_2003,
        formatVersion = "rpg-maker-2000-2003-lcf-v1",
        outputSchemaVersion = 1,
    )

    override suspend fun preflight(source: ReadOnlyGameSource): ExtractionPlan =
        ExtractionPlan(RpgMakerLegacySourceSupport.findRm2kFiles(source))

    override suspend fun extractFile(source: ReadOnlyGameSource, relativePath: String): List<ExtractedTextEntry> {
        val kind = when (relativePath.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
            "ldb" -> RpgMakerBinaryKind.RM2K_DATABASE
            "lmt" -> RpgMakerBinaryKind.RM2K_TREE
            "lmu" -> RpgMakerBinaryKind.RM2K_MAP
            else -> throw TranslationExtractionException(ExtractionErrorCode.UNSUPPORTED_FORMAT)
        }
        val input = source.open(relativePath)
            ?: throw TranslationExtractionException(ExtractionErrorCode.INVALID_GAME_DIRECTORY)
        val bytes = input.use { RpgMakerLegacySourceSupport.readBounded(it, MAX_RESOURCE_BYTES) }
        val json = decoder.decode(kind, relativePath.substringAfterLast('/'), bytes)
        return RpgMaker2000JsonTextReader.extract(
            relativePath,
            JSONObject(json.toString(Charsets.UTF_8)),
        )
    }

    private companion object {
        const val MAX_RESOURCE_BYTES = 32 * 1024 * 1024
    }
}
