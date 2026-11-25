package com.tyranor.next.core.translation.export.mtool

import com.tyranor.next.core.translation.TranslationArchiveEntry
import com.tyranor.next.core.translation.TranslationProjectCodec
import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import org.json.JSONObject

/** Exports the MTool source-text → translation JSON map used as the workbench JSON exchange format. */
internal object MToolJsonExporter {
    fun export(entries: List<TranslationArchiveEntry>): MToolJsonExport {
        if (entries.size > TranslationProjectCodec.MAX_ENTRIES) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_TOO_LARGE)
        }
        if (entries.any { it.sourceText.isBlank() }) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_PROJECT_FILE)
        }
        val map = linkedMapOf<String, String>()
        entries.forEach { entry ->
            val translated = entry.translatedText.orEmpty()
            val previous = map.putIfAbsent(entry.sourceText, translated)
            if (previous != null && previous.isNotBlank() && translated.isNotBlank() && previous != translated) {
                throw TranslationWorkbenchException(TranslationWorkbenchError.MTOOL_SOURCE_CONFLICT)
            }
            if (previous != null && previous.isBlank() && translated.isNotBlank()) map[entry.sourceText] = translated
        }
        if (map.isEmpty()) throw TranslationWorkbenchException(TranslationWorkbenchError.EMPTY_PROJECT)
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
