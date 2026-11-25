package com.tyranor.next.core.translation

import com.tyranor.next.core.translation.export.mtool.MToolJsonExporter
import com.tyranor.next.core.translation.export.mtool.MToolJsonImporter

/** One paired reader/writer registration per project exchange format. */
object TranslationProjectFormatRegistry {
    data class Descriptor(
        val format: TranslationProjectFormat,
        val extension: String,
        val mimeType: String,
        val sourceType: TranslationProjectSource,
    )

    data class DecodedDocument(
        val format: TranslationProjectFormat,
        val archive: TranslationProjectArchive,
    )

    private val handlers: List<TranslationProjectFormatHandler> = listOf(
        CsvTranslationProjectFormatHandler,
        MToolJsonTranslationProjectFormatHandler,
    ).sortedByDescending { it.detectionPriority }
    private val handlersByFormat = handlers.associateBy { it.descriptor.format }

    init {
        require(handlersByFormat.size == handlers.size) { "Project format handlers must have unique formats" }
    }

    /** Lists registered formats with paired import/export handlers; the UI may expose a smaller subset. */
    fun exportFormats(): List<TranslationProjectFormat> = handlers.map { it.descriptor.format }

    fun descriptor(format: TranslationProjectFormat): Descriptor =
        handlersByFormat[format]?.descriptor
            ?: throw TranslationWorkbenchException(TranslationWorkbenchError.UNSUPPORTED_PROJECT_FORMAT)

    /** Returns a filename/MIME candidate for enabling the import action before the file is read. */
    fun supportsImportFile(displayName: String, mimeType: String?): Boolean =
        handlers.any { it.supportsFile(displayName, mimeType) }

    /** Handlers match by file type; each decoder validates the document contents. */
    fun decode(
        displayName: String,
        mimeType: String?,
        text: String,
    ): DecodedDocument {
        if (text.length > TranslationProjectCodec.MAX_DOCUMENT_CHARS) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_TOO_LARGE)
        }
        val normalizedText = text.removePrefix("\uFEFF")
        val handler = handlers.firstOrNull { it.matchesDocument(displayName, mimeType, normalizedText) }
            ?: throw TranslationWorkbenchException(TranslationWorkbenchError.UNSUPPORTED_PROJECT_FORMAT)
        return DecodedDocument(handler.descriptor.format, handler.decode(normalizedText, displayName))
    }

    fun encode(archive: TranslationProjectArchive, format: TranslationProjectFormat): String =
        handlersByFormat[format]?.encode(archive)
            ?: throw TranslationWorkbenchException(TranslationWorkbenchError.UNSUPPORTED_PROJECT_FORMAT)
}

/** Paired interface: every registered format must be able to import and export. */
internal interface TranslationProjectFormatHandler {
    val descriptor: TranslationProjectFormatRegistry.Descriptor
    val detectionPriority: Int

    fun supportsFile(displayName: String, mimeType: String?): Boolean

    fun matchesDocument(displayName: String, mimeType: String?, text: String): Boolean

    fun decode(text: String, displayName: String): TranslationProjectArchive

    fun encode(archive: TranslationProjectArchive): String
}

internal object CsvTranslationProjectFormatHandler : TranslationProjectFormatHandler {
    override val descriptor = TranslationProjectFormatRegistry.Descriptor(
        format = TranslationProjectFormat.CSV,
        extension = ".csv",
        mimeType = "text/csv",
        sourceType = TranslationProjectSource.IMPORTED_CSV,
    )
    override val detectionPriority = 100

    override fun supportsFile(displayName: String, mimeType: String?) =
        displayName.hasExtension("csv") || mimeType.normalizedMimeType() == descriptor.mimeType

    override fun matchesDocument(displayName: String, mimeType: String?, text: String) =
        supportsFile(displayName, mimeType)

    override fun decode(text: String, displayName: String) =
        TranslationProjectCodec.decodeCsv(text, displayName.substringBeforeLast('.', displayName))

    override fun encode(archive: TranslationProjectArchive) = TranslationProjectCodec.encodeCsv(archive)
}

internal object MToolJsonTranslationProjectFormatHandler : TranslationProjectFormatHandler {
    override val descriptor = TranslationProjectFormatRegistry.Descriptor(
        format = TranslationProjectFormat.MTOOL_JSON,
        extension = ".json",
        mimeType = "application/json",
        sourceType = TranslationProjectSource.IMPORTED_JSON,
    )
    override val detectionPriority = 0

    override fun supportsFile(displayName: String, mimeType: String?) =
        displayName.hasExtension("json") || mimeType.isJsonMimeType()

    override fun matchesDocument(displayName: String, mimeType: String?, text: String) =
        supportsFile(displayName, mimeType)

    override fun decode(text: String, displayName: String) =
        MToolJsonImporter.decode(text, displayName)

    override fun encode(archive: TranslationProjectArchive) =
        MToolJsonExporter.export(archive.entries).text
}

private fun String.hasExtension(extension: String): Boolean =
    substringAfterLast('/').substringAfterLast('.', "").equals(extension, ignoreCase = true)

private fun String?.normalizedMimeType(): String =
    this?.substringBefore(';')?.trim()?.lowercase().orEmpty()

private fun String?.isJsonMimeType(): Boolean {
    val mimeType = normalizedMimeType()
    return mimeType == "application/json" || mimeType == "text/json" || mimeType.endsWith("+json")
}
