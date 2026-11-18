package com.tyranor.next.core.translation.extraction.engines.rpgmaker.mv

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.translation.extraction.ExtractedTextEntry
import com.tyranor.next.core.translation.extraction.ExtractionPlan
import com.tyranor.next.core.translation.extraction.ReadOnlyGameSource
import com.tyranor.next.core.translation.extraction.TextExtractor
import com.tyranor.next.core.translation.extraction.TextExtractorDescriptor
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.RpgMakerJsonTextReader

/** RPG Maker MV JSON resource generation extractor. */
object RpgMakerMvTextExtractor : TextExtractor {
    override val descriptor = TextExtractorDescriptor(
        extractorId = "rpgmaker-mv-json-v1",
        engineId = EngineType.RPG_MV.name,
        formatVersion = "mv-json-data-v1",
        outputSchemaVersion = 1,
    )

    override suspend fun preflight(source: ReadOnlyGameSource): ExtractionPlan =
        RpgMakerJsonTextReader.preflight(source)

    override suspend fun extractFile(source: ReadOnlyGameSource, relativePath: String): List<ExtractedTextEntry> =
        RpgMakerJsonTextReader.extractFile(source, relativePath)
}
