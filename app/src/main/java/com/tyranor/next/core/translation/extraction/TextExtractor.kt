package com.tyranor.next.core.translation.extraction

import com.tyranor.next.core.translation.TranslationWorkbenchError
import java.io.InputStream

/** Read-only SAF document view. Paths are normalized, project-relative paths only. */
interface ReadOnlyGameSource {
    suspend fun list(relativePath: String): List<GameDocumentInfo>
    suspend fun open(relativePath: String): InputStream?
}

data class GameDocumentInfo(
    val name: String,
    val isDirectory: Boolean,
)

data class TextExtractorDescriptor(
    val extractorId: String,
    val engineId: String,
    val formatVersion: String,
    val outputSchemaVersion: Int,
)

data class ExtractionPlan(
    val files: List<String>,
)

data class ExtractedTextEntry(
    val sourceKey: String,
    val sourceText: String,
    val sourceFile: String,
    val sourcePointer: String,
    val textType: String,
)

interface TextExtractor {
    val descriptor: TextExtractorDescriptor

    suspend fun preflight(source: ReadOnlyGameSource): ExtractionPlan

    suspend fun extractFile(source: ReadOnlyGameSource, relativePath: String): List<ExtractedTextEntry>
}

class TranslationExtractionException(
    val code: ExtractionErrorCode,
    cause: Throwable? = null,
) : Exception(code.name, cause)

enum class ExtractionErrorCode {
    DIRECTORY_PERMISSION_LOST,
    EXTRACTOR_UNAVAILABLE,
    INVALID_GAME_DIRECTORY,
    UNSUPPORTED_FORMAT,
    CORRUPT_RESOURCE,
    RESOURCE_TOO_LARGE,
    NO_TEXT_FOUND,
    STORAGE_FAILURE,
}

internal fun validateRelativePath(path: String, allowEmpty: Boolean = false): List<String> {
    if (path.isEmpty() && allowEmpty) return emptyList()
    if (path.isBlank() || path.startsWith('/') || path.startsWith('\\') || path.contains('\\')) {
        throw TranslationExtractionException(ExtractionErrorCode.INVALID_GAME_DIRECTORY)
    }
    val parts = path.split('/')
    if (parts.any { it.isBlank() || it == "." || it == ".." || it.contains(':') }) {
        throw TranslationExtractionException(ExtractionErrorCode.INVALID_GAME_DIRECTORY)
    }
    return parts
}

internal fun ExtractionErrorCode.toWorkbenchError(): TranslationWorkbenchError = when (this) {
    ExtractionErrorCode.DIRECTORY_PERMISSION_LOST -> TranslationWorkbenchError.DIRECTORY_PERMISSION_LOST
    ExtractionErrorCode.EXTRACTOR_UNAVAILABLE -> TranslationWorkbenchError.EXTRACTOR_UNAVAILABLE
    ExtractionErrorCode.INVALID_GAME_DIRECTORY -> TranslationWorkbenchError.INVALID_GAME_DIRECTORY
    ExtractionErrorCode.UNSUPPORTED_FORMAT -> TranslationWorkbenchError.UNSUPPORTED_FORMAT
    ExtractionErrorCode.CORRUPT_RESOURCE -> TranslationWorkbenchError.CORRUPT_RESOURCE
    ExtractionErrorCode.RESOURCE_TOO_LARGE -> TranslationWorkbenchError.RESOURCE_TOO_LARGE
    ExtractionErrorCode.NO_TEXT_FOUND -> TranslationWorkbenchError.NO_TEXT_FOUND
    ExtractionErrorCode.STORAGE_FAILURE -> TranslationWorkbenchError.STORAGE_FAILURE
}
