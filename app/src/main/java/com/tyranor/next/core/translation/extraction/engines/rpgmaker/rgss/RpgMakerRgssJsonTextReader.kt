package com.tyranor.next.core.translation.extraction.engines.rpgmaker.rgss

import com.tyranor.next.core.translation.extraction.ExtractedTextEntry
import com.tyranor.next.core.translation.extraction.ExtractionErrorCode
import com.tyranor.next.core.translation.extraction.TranslationExtractionException
import org.json.JSONArray
import org.json.JSONObject

/** Reads only the Marshal JSON shape emitted by the RGSS resource decoder. */
internal object RpgMakerRgssJsonTextReader {
    private val textFields = setOf(
        "name", "title", "description", "nickname", "profile", "message1", "message2", "message3", "message4",
        "currencyunit", "gametitle", "displayname", "usingmessage1", "usingmessage2",
    )
    private val skippedFields = setOf("note", "script", "scripts", "parameters")

    fun extract(file: String, document: JSONObject): List<ExtractedTextEntry> {
        val output = LinkedHashMap<String, ExtractedTextEntry>()
        try {
            visit(document, file, "", false, output)
            return output.values.toList()
        } catch (failure: TranslationExtractionException) {
            throw failure
        } catch (failure: Exception) {
            throw TranslationExtractionException(ExtractionErrorCode.CORRUPT_RESOURCE, failure)
        }
    }

    fun extractArchive(json: ByteArray): List<ExtractedTextEntry> {
        try {
            val root = JSONObject(json.toString(Charsets.UTF_8))
            val resources = root.optJSONArray("resources")
                ?: throw TranslationExtractionException(ExtractionErrorCode.CORRUPT_RESOURCE)
            if (resources.length() > MAX_ARCHIVE_RESOURCES) {
                throw TranslationExtractionException(ExtractionErrorCode.RESOURCE_TOO_LARGE)
            }
            val output = LinkedHashMap<String, ExtractedTextEntry>()
            for (index in 0 until resources.length()) {
                val resource = resources.optJSONObject(index)
                    ?: throw TranslationExtractionException(ExtractionErrorCode.CORRUPT_RESOURCE)
                val path = resource.optString("file")
                validateArchiveResourcePath(path)
                val document = resource.optJSONObject("document")
                    ?: throw TranslationExtractionException(ExtractionErrorCode.CORRUPT_RESOURCE)
                visit(document, path, "", termsContext = false, output)
            }
            return output.values.toList()
        } catch (failure: TranslationExtractionException) {
            throw failure
        } catch (failure: Exception) {
            throw TranslationExtractionException(ExtractionErrorCode.CORRUPT_RESOURCE, failure)
        }
    }

    private fun visit(
        value: Any?,
        file: String,
        pointer: String,
        termsContext: Boolean,
        output: MutableMap<String, ExtractedTextEntry>,
    ) {
        when (value) {
            is JSONObject -> {
                when (value.optString("__type")) {
                    "str" -> {
                        val text = value.opt("__value") as? String ?: return
                        if (termsContext) add(text, file, pointer, "database", output)
                    }
                    "array" -> visitArray(value.optJSONArray("__value"), file, pointer, termsContext, output)
                    "hash" -> visitHash(value.optJSONArray("__value"), file, pointer, termsContext, output)
                    "object" -> {
                        if (isCommand(value)) {
                            extractCommand(value, file, pointer, output)
                            return
                        }
                        val members = value.optJSONArray("__members") ?: return
                        for (index in 0 until members.length()) {
                            val pair = members.optJSONArray(index) ?: continue
                            val field = pair.optString(0).removePrefix("@").ifBlank { continue }
                            visitField(field, pair.opt(1), file, pointer, termsContext, output)
                        }
                    }
                    else -> {
                        if (isCommand(value)) {
                            extractCommand(value, file, pointer, output)
                            return
                        }
                        val keys = value.keys().asSequence().toList().sorted()
                        for (field in keys) {
                            if (field.startsWith("__")) continue
                            visitField(field, value.opt(field), file, pointer, termsContext, output)
                        }
                    }
                }
            }
            is JSONArray -> visitArray(value, file, pointer, termsContext, output)
            is String -> if (termsContext) add(value, file, pointer, "database", output)
        }
    }

    private fun visitField(
        rawField: String,
        child: Any?,
        file: String,
        pointer: String,
        termsContext: Boolean,
        output: MutableMap<String, ExtractedTextEntry>,
    ) {
        val field = rawField.removePrefix("@").lowercase()
        if (field in skippedFields) return
        val childPointer = "$pointer/${escapePointer(rawField)}"
        if (field == "list" || field == "event_commands") {
            visitCommandArray(child, file, childPointer, output)
            return
        }
        if (field == "terms") {
            visit(child, file, childPointer, termsContext = true, output)
            return
        }
        val normalized = field.filter(Char::isLetterOrDigit)
        val text = stringValue(child)
        if (text != null && (termsContext || normalized in textFields)) {
            add(text, file, childPointer, textTypeFor(file), output)
        } else {
            visit(child, file, childPointer, termsContext, output)
        }
    }

    private fun visitHash(
        pairs: JSONArray?,
        file: String,
        pointer: String,
        termsContext: Boolean,
        output: MutableMap<String, ExtractedTextEntry>,
    ) {
        if (pairs == null) return
        for (index in 0 until pairs.length()) {
            val pair = pairs.optJSONArray(index) ?: continue
            val key = scalarKey(pair.opt(0)) ?: index.toString()
            visit(pair.opt(1), file, "$pointer/${escapePointer(key)}", termsContext, output)
        }
    }

    private fun visitArray(
        array: JSONArray?,
        file: String,
        pointer: String,
        termsContext: Boolean,
        output: MutableMap<String, ExtractedTextEntry>,
    ) {
        if (array == null) return
        val commands = (0 until array.length()).map { array.opt(it) }
        if (commands.isNotEmpty() && commands.all(::isCommand)) {
            visitCommandArray(array, file, pointer, output)
        } else {
            commands.forEachIndexed { index, item ->
                visit(item, file, "$pointer/$index", termsContext, output)
            }
        }
    }

    private fun visitCommandArray(
        value: Any?,
        file: String,
        pointer: String,
        output: MutableMap<String, ExtractedTextEntry>,
    ) {
        val commands = when (value) {
            is JSONArray -> value
            is JSONObject -> if (value.optString("__type") == "array") value.optJSONArray("__value") else null
            else -> null
        } ?: return
        for (index in 0 until commands.length()) {
            val command = commands.optJSONObject(index) ?: continue
            if (isCommand(command)) extractCommand(command, file, "$pointer/$index", output)
        }
    }

    private fun extractCommand(
        command: JSONObject,
        file: String,
        pointer: String,
        output: MutableMap<String, ExtractedTextEntry>,
    ) {
        val code = intValue(member(command, "code")) ?: return
        val parameters = arrayValues(member(command, "parameters"))
        when (code) {
            RGSS_SHOW_CHOICES -> arrayValues(parameters.getOrNull(0)).forEachIndexed { index, item ->
                stringValue(item)?.let { add(it, file, "$pointer/parameters/0/$index", "choice", output) }
            }
            RGSS_SHOW_TEXT_LINE, RGSS_SCROLLING_TEXT -> stringValue(parameters.getOrNull(0))?.let {
                add(it, file, "$pointer/parameters/0", "dialogue", output)
            }
            RGSS_CHANGE_ACTOR_NAME, RGSS_CHANGE_ACTOR_NICKNAME, RGSS_CHANGE_ACTOR_PROFILE ->
                stringValue(parameters.getOrNull(1))?.let {
                    add(it, file, "$pointer/parameters/1", "event", output)
                }
        }
    }

    private fun isCommand(value: Any?): Boolean {
        val objectValue = value as? JSONObject ?: return false
        if (objectValue.optString("__class").endsWith("EventCommand")) return true
        return member(objectValue, "code") != null &&
            (member(objectValue, "string") != null || member(objectValue, "parameters") != null)
    }

    private fun member(value: JSONObject, name: String): Any? {
        if (value.has(name)) return value.opt(name)
        val members = value.optJSONArray("__members") ?: return null
        for (index in 0 until members.length()) {
            val pair = members.optJSONArray(index) ?: continue
            if (pair.optString(0).removePrefix("@").equals(name, ignoreCase = true)) return pair.opt(1)
        }
        return null
    }

    private fun stringValue(value: Any?): String? = when (value) {
        is String -> value
        is JSONObject -> if (value.optString("__type") == "str") value.opt("__value") as? String else null
        else -> null
    }

    private fun arrayValues(value: Any?): List<Any?> = when (value) {
        is JSONArray -> (0 until value.length()).map(value::opt)
        is JSONObject -> if (value.optString("__type") == "array") {
            value.optJSONArray("__value")?.let { array -> (0 until array.length()).map(array::opt) }.orEmpty()
        } else {
            emptyList()
        }
        else -> emptyList()
    }

    private fun intValue(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is JSONObject -> (value.opt("__value") as? Number)?.toInt()
        else -> null
    }

    private fun scalarKey(value: Any?): String? = when (value) {
        is String, is Number -> value.toString()
        else -> null
    }

    private fun add(
        text: String,
        file: String,
        pointer: String,
        type: String,
        output: MutableMap<String, ExtractedTextEntry>,
    ) {
        if (text.isBlank()) return
        val location = pointer.ifEmpty { "/" }
        val sourceKey = "$file#$location"
        output.putIfAbsent(
            sourceKey,
            ExtractedTextEntry(sourceKey, text, file, location, type),
        )
    }

    private fun textTypeFor(file: String): String =
        if (file.substringAfterLast('/').startsWith("Map", ignoreCase = true)) "event" else "database"

    private fun validateArchiveResourcePath(path: String) {
        val parts = path.split('/')
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') ||
            parts.any { it.isBlank() || it == "." || it == ".." || it.contains(':') } ||
            !parts.first().equals("Data", ignoreCase = true)
        ) {
            throw TranslationExtractionException(ExtractionErrorCode.CORRUPT_RESOURCE)
        }
    }

    private fun escapePointer(value: String): String = value.replace("~", "~0").replace("/", "~1")

    private const val MAX_ARCHIVE_RESOURCES = 50_000
    private const val RGSS_SHOW_CHOICES = 102
    private const val RGSS_SHOW_TEXT_LINE = 401
    private const val RGSS_SCROLLING_TEXT = 405
    private const val RGSS_CHANGE_ACTOR_NAME = 320
    private const val RGSS_CHANGE_ACTOR_NICKNAME = 324
    private const val RGSS_CHANGE_ACTOR_PROFILE = 325
}
