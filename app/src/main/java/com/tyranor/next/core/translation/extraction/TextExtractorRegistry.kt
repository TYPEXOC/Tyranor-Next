package com.tyranor.next.core.translation.extraction

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.mv.RpgMakerMvTextExtractor
import com.tyranor.next.core.translation.extraction.engines.rpgmaker.mz.RpgMakerMzTextExtractor

/** Explicit allowlist. Unknown types never fall through to a different engine parser. */
object TextExtractorRegistry {
    private val extractors = listOf(
        RpgMakerMvTextExtractor,
        RpgMakerMzTextExtractor,
    ).associateBy { it.descriptor.engineId }

    fun find(engineId: String): TextExtractor? = extractors[engineId]

    fun find(engineType: EngineType): TextExtractor? = find(engineType.name)

    fun registeredDescriptors(): List<TextExtractorDescriptor> = extractors.values.map { it.descriptor }
}
