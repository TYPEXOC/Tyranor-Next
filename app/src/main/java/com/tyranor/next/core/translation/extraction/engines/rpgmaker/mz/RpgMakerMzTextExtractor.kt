package com.tyranor.next.core.translation.extraction.engines.rpgmaker.mz

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.translation.extraction.ExtractedTextEntry
import com.tyranor.next.core.translation.extraction.ExtractionPlan
import com.tyranor.next.core.translation.extraction.ReadOnlyGameSource
import com.tyranor.next.core.translation.extraction.TextExtractor
import com.tyranor.next.core.translation.extraction.TextExtractorDescriptor
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.RpgMakerJsonTextReader

/** RPG Maker MZ JSON resource generation extractor. */
object RpgMakerMzTextExtractor : TextExtractor {
    override val descriptor = TextExtractorDescriptor(
        extractorId = "rpgmaker-mz-json-v1",
        engineId = EngineType.RPG_MZ.name,
        formatVersion = "mz-json-data-v1",
        outputSchemaVersion = 1,
    )

    override suspend fun preflight(source: ReadOnlyGameSource): ExtractionPlan =
        RpgMakerJsonTextReader.preflight(source)

    override suspend fun extractFile(source: ReadOnlyGameSource, relativePath: String): List<ExtractedTextEntry> =
        RpgMakerJsonTextReader.extractFile(source, relativePath)
}
