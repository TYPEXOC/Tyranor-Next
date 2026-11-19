package com.tyranor.next.core.translation.export.mtool

import com.tyranor.next.core.translation.TranslationArchiveEntry
import com.tyranor.next.core.translation.TranslationProjectArchive
import com.tyranor.next.core.translation.TranslationProjectCodec
import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import org.json.JSONObject

/** Imports the MTool source-text → translation JSON map into editable project entries. */
internal object MToolJsonImporter {
    fun decode(text: String, fallbackTitle: String): TranslationProjectArchive {
        if (text.length > TranslationProjectCodec.MAX_DOCUMENT_CHARS) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_TOO_LARGE)
        }
        try {
            val root = JSONObject(text.removePrefix("\uFEFF"))
            val sourceTexts = root.keys().asSequence().toList().sorted()
            if (sourceTexts.size > TranslationProjectCodec.MAX_ENTRIES) {
                throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_TOO_LARGE)
            }
            val entries = sourceTexts.mapIndexed { index, sourceText ->
                val targetText = root.opt(sourceText) as? String
                    ?: throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_PROJECT_FILE)
                if (sourceText.isBlank()) {
                    throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_PROJECT_FILE)
                }
                TranslationArchiveEntry(
                    sourceKey = "mtool:$index",
                    sourceText = sourceText,
                    translatedText = targetText.takeIf(String::isNotBlank),
                )
            }
            if (entries.isEmpty()) throw TranslationWorkbenchException(TranslationWorkbenchError.EMPTY_PROJECT)
            val title = fallbackTitle.substringBeforeLast('.', fallbackTitle).trim()
            if (title.isBlank()) throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_PROJECT_FILE)
            return TranslationProjectArchive(
                projectKey = null,
                title = title,
                engineId = null,
                sourceLanguage = null,
                targetLanguage = null,
                entries = entries,
            )
        } catch (failure: TranslationWorkbenchException) {
            throw failure
        } catch (failure: Exception) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_PROJECT_FILE, failure)
        }
    }
}
