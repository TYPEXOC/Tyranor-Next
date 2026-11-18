package com.tyranor.next.core.translation.export.mtool

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.translation.TranslationArchiveEntry
import com.tyranor.next.core.translation.TranslationProjectCodec
import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import org.json.JSONObject

/** Exports the MTool source-text → translation JSON map used by RPG Maker MV/MZ translators. */
internal object MToolJsonExporter {
    fun export(engineId: String?, entries: List<TranslationArchiveEntry>): MToolJsonExport {
        val engine = engineId?.let { runCatching { EngineType.valueOf(it) }.getOrNull() }
        if (engine != EngineType.RPG_MV && engine != EngineType.RPG_MZ) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.MTOOL_ENGINE_UNSUPPORTED)
        }
        if (entries.size > TranslationProjectCodec.MAX_ENTRIES) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_TOO_LARGE)
        }
        val map = linkedMapOf<String, String>()
        entries.forEach { entry ->
            val translated = entry.translatedText?.takeIf(String::isNotBlank) ?: return@forEach
            val previous = map.putIfAbsent(entry.sourceText, translated)
            if (previous != null && previous != translated) {
                throw TranslationWorkbenchException(TranslationWorkbenchError.MTOOL_SOURCE_CONFLICT)
            }
        }
        if (map.isEmpty()) throw TranslationWorkbenchException(TranslationWorkbenchError.NO_TRANSLATED_ENTRIES)
        val json = JSONObject().apply { map.forEach { (source, target) -> put(source, target) } }.toString(2)
        if (json.length > TranslationProjectCodec.MAX_DOCUMENT_CHARS) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_TOO_LARGE)
        }
        return MToolJsonExport(json, map.size)
    }
}

internal data class MToolJsonExport(
    val text: String,
    val entryCount: Int,
)
