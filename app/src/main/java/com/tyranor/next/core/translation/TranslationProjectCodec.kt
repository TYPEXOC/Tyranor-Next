package com.tyranor.next.core.translation

/** CSV project exchange codec. JSON exchange is handled by the dedicated MTool adapter. */
object TranslationProjectCodec {
    const val MAX_ENTRIES = 200_000
    const val MAX_DOCUMENT_CHARS = 32 * 1024 * 1024

    private val csvColumns = listOf(
        "projectKey",
        "projectTitle",
        "engineId",
        "sourceLanguage",
        "targetLanguage",
        "sourceKey",
        "sourceText",
        "translatedText",
        "sourceFile",
        "sourcePointer",
        "textType",
    )

    /** Accepts this codec's CSV and ordinary header-based CSV with a sourceText column. */
    fun decodeCsv(text: String, fallbackTitle: String): TranslationProjectArchive {
        checkSize(text)
        val rows = try {
            parseCsv(text.removePrefix("\uFEFF"))
        } catch (failure: TranslationWorkbenchException) {
            throw failure
        } catch (failure: Exception) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_PROJECT_FILE, failure)
        }
        val headerIndex = rows.indexOfFirst { row -> row.any { it.equals("sourceText", ignoreCase = true) } }
        if (headerIndex < 0) throw TranslationWorkbenchException(TranslationWorkbenchError.UNSUPPORTED_PROJECT_FORMAT)
        val headers = rows[headerIndex].map { it.trim().removePrefix("\uFEFF") }
        val positions = headers.mapIndexed { index, name -> name.lowercase() to index }.toMap()
        fun value(row: List<String>, name: String): String? = positions[name.lowercase()]
            ?.let(row::getOrNull)
            ?.takeIf(String::isNotBlank)

        val entries = rows.drop(headerIndex + 1).mapIndexedNotNull { offset, row ->
            val sourceText = value(row, "sourceText") ?: return@mapIndexedNotNull null
            TranslationArchiveEntry(
                sourceKey = value(row, "sourceKey") ?: "csv:${headerIndex + offset + 2}",
                sourceText = sourceText,
                translatedText = value(row, "translatedText"),
                sourceFile = value(row, "sourceFile"),
                sourcePointer = value(row, "sourcePointer"),
                textType = value(row, "textType"),
            )
        }
        validateEntries(entries)
        if (entries.isEmpty()) throw TranslationWorkbenchException(TranslationWorkbenchError.EMPTY_PROJECT)
        return TranslationProjectArchive(
            projectKey = rows.drop(headerIndex + 1).firstNotNullOfOrNull { value(it, "projectKey") },
            title = rows.drop(headerIndex + 1).firstNotNullOfOrNull { value(it, "projectTitle") }
                ?: fallbackTitle,
            engineId = rows.drop(headerIndex + 1).firstNotNullOfOrNull { value(it, "engineId") },
            sourceLanguage = rows.drop(headerIndex + 1).firstNotNullOfOrNull { value(it, "sourceLanguage") },
            targetLanguage = rows.drop(headerIndex + 1).firstNotNullOfOrNull { value(it, "targetLanguage") },
            entries = entries,
        )
    }

    fun encodeCsv(archive: TranslationProjectArchive): String {
        validateEntries(archive.entries)
        val output = StringBuilder()
        output.append(csvColumns.joinToString(",") { csvEscape(it) }).append("\r\n")
        archive.entries.forEach { entry ->
            val row = listOf(
                archive.projectKey.orEmpty(),
                archive.title,
                archive.engineId.orEmpty(),
                archive.sourceLanguage.orEmpty(),
                archive.targetLanguage.orEmpty(),
                entry.sourceKey,
                entry.sourceText,
                entry.translatedText.orEmpty(),
                entry.sourceFile.orEmpty(),
                entry.sourcePointer.orEmpty(),
                entry.textType.orEmpty(),
            )
            output.append(row.joinToString(",", transform = ::csvEscape)).append("\r\n")
        }
        return output.toString()
    }

    private fun validateEntries(entries: List<TranslationArchiveEntry>) {
        if (entries.size > MAX_ENTRIES) throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_TOO_LARGE)
        if (entries.any { it.sourceKey.isBlank() || it.sourceText.isBlank() }) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_PROJECT_FILE)
        }
        if (entries.map { it.sourceKey }.toSet().size != entries.size) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.DUPLICATE_SOURCE_KEY)
        }
    }

    private fun checkSize(text: String) {
        if (text.length > MAX_DOCUMENT_CHARS) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_TOO_LARGE)
        }
    }

    private fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                quoted && char == '"' && text.getOrNull(index + 1) == '"' -> {
                    cell.append('"')
                    index++
                }
                char == '"' && !quoted && cell.isEmpty() -> quoted = true
                char == '"' && quoted -> quoted = false
                char == ',' && !quoted -> {
                    row += cell.toString()
                    cell.clear()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    row += cell.toString()
                    cell.clear()
                    if (row.any(String::isNotEmpty)) rows += row
                    row = mutableListOf()
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                }
                else -> cell.append(char)
            }
            index++
        }
        if (quoted) throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_PROJECT_FILE)
        row += cell.toString()
        if (row.any(String::isNotEmpty)) rows += row
        return rows
    }
}
