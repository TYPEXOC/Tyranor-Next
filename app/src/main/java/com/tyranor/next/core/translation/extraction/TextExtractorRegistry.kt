package com.tyranor.next.core.translation.extraction

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.mv.RpgMakerMvTextExtractor
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.mz.RpgMakerMzTextExtractor
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.rm2k.RpgMaker2000TextExtractor
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.rgss.RpgMakerRgssTextExtractor

/** Explicit allowlist. Unknown types never fall through to a different engine parser. */
object TextExtractorRegistry {
    private val extractors = listOf(
        RpgMakerMvTextExtractor,
        RpgMakerMzTextExtractor,
        RpgMaker2000TextExtractor(),
        RpgMakerRgssTextExtractor(),
    ).associateBy { it.descriptor.engineId }

    fun find(engineId: String): TextExtractor? = extractors[engineId]

    fun find(engineType: EngineType): TextExtractor? = find(engineType.name)

    fun registeredDescriptors(): List<TextExtractorDescriptor> = extractors.values.map { it.descriptor }
}
