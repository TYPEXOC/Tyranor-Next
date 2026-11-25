package com.tyranor.next.core.translation.extraction.engines.rpgmaker

import com.tyranor.next.core.translation.extraction.ExtractedTextEntry
import com.tyranor.next.core.translation.extraction.ExtractionErrorCode
import com.tyranor.next.core.translation.extraction.ExtractionPlan
import com.tyranor.next.core.translation.extraction.ReadOnlyGameSource
import com.tyranor.next.core.translation.extraction.TranslationExtractionException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream

/** Shared mechanics for the structurally compatible MV/MZ JSON data files. */
internal object RpgMakerJsonTextReader {
    private const val MAX_JSON_BYTES = 32 * 1024 * 1024
    private val databaseFiles = setOf(
        "actors.json",
        "animations.json",
        "armors.json",
        "classes.json",
        "commonevents.json",
        "enemies.json",
        "items.json",
        "mapinfos.json",
        "skills.json",
        "states.json",
        "system.json",
        "tilesets.json",
        "troops.json",
        "weapons.json",
    )
    private val textScalarFields = setOf(
        "name", "description", "nickname", "profile", "message1", "message2", "message3", "message4",
        "currencyUnit", "gameTitle", "displayName",
    )
    private val textArrayFields = setOf("basic", "commands", "params", "messages", "choices")
    private val skippedFields = setOf("note", "script", "parameters")

    suspend fun preflight(source: ReadOnlyGameSource): ExtractionPlan {
        val rootChildren = source.list("")
        val dataCandidates = buildList {
            if (rootChildren.any { it.isDirectory && it.name.equals("data", ignoreCase = true) }) add("data")
            val www = rootChildren.firstOrNull { it.isDirectory && it.name.equals("www", ignoreCase = true) }
            if (www != null) add("${www.name}/data")
        }
        for (dataPath in dataCandidates) {
            val files = source.list(dataPath).filter { !it.isDirectory }
            if (files.none { it.name.equals("System.json", ignoreCase = true) }) continue
            val supported = files.filter { file ->
                val lowerName = file.name.lowercase()
                lowerName in databaseFiles || MAP_FILE.matches(file.name)
            }.map { "$dataPath/${it.name}" }.sorted()
            if (supported.isNotEmpty()) return ExtractionPlan(supported)
        }
        throw TranslationExtractionException(ExtractionErrorCode.UNSUPPORTED_FORMAT)
    }

    suspend fun extractFile(source: ReadOnlyGameSource, relativePath: String): List<ExtractedTextEntry> {
        val stream = source.open(relativePath)
            ?: throw TranslationExtractionException(ExtractionErrorCode.INVALID_GAME_DIRECTORY)
        val text = stream.use(::readUtf8Bounded)
        try {
            val root = JSONTokener(text).nextValue()
            val output = LinkedHashMap<String, ExtractedTextEntry>()
            visit(root, relativePath, "", output)
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
        output: MutableMap<String, ExtractedTextEntry>,
        inTerms: Boolean = false,
    ) {
        when (value) {
            is JSONObject -> {
                collectEventCommand(value, file, pointer, output)
                val keys = value.keys().asSequence().toList().sorted()
                for (key in keys) {
                    if (key in skippedFields) continue
                    val child = value.opt(key)
                    val childPointer = "$pointer/${escapePointer(key)}"
                    if ((inTerms || key in textScalarFields) && child is String) {
                        add(child, file, childPointer, output)
                    } else if ((inTerms || key in textArrayFields) && child is JSONArray) {
                        collectStringArray(child, file, childPointer, output)
                    } else {
                        visit(child, file, childPointer, output, inTerms || key == "terms")
                    }
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                visit(value.opt(index), file, "$pointer/$index", output, inTerms)
            }
        }
    }

    private fun collectEventCommand(
        command: JSONObject,
        file: String,
        pointer: String,
        output: MutableMap<String, ExtractedTextEntry>,
    ) {
        val code = command.optInt("code", -1)
        val parameters = command.optJSONArray("parameters") ?: return
        when (code) {
            101 -> if (parameters.length() > 4) {
                add(parameters.optString(4), file, "$pointer/parameters/4", output, "speaker")
            }
            102 -> parameters.optJSONArray(0)?.let { choices ->
                collectStringArray(choices, file, "$pointer/parameters/0", output, "choice")
            }
            401 -> add(parameters.optString(0), file, "$pointer/parameters/0", output, "dialogue")
            405 -> add(parameters.optString(0), file, "$pointer/parameters/0", output, "scrolling_text")
            320, 324, 325 -> add(parameters.optString(1), file, "$pointer/parameters/1", output, "dialogue")
        }
    }

    private fun collectStringArray(
        values: JSONArray,
        file: String,
        pointer: String,
        output: MutableMap<String, ExtractedTextEntry>,
        textType: String = "database",
    ) {
        for (index in 0 until values.length()) {
            val text = values.opt(index)
            if (text is String) add(text, file, "$pointer/$index", output, textType)
        }
    }

    private fun add(
        text: String,
        file: String,
        pointer: String,
        output: MutableMap<String, ExtractedTextEntry>,
        textType: String = if (file.substringAfterLast('/').startsWith("Map", ignoreCase = true)) "event" else "database",
    ) {
        if (text.isBlank()) return
        val normalizedPointer = pointer.ifEmpty { "/" }
        val sourceKey = "$file#$normalizedPointer"
        output.putIfAbsent(
            sourceKey,
            ExtractedTextEntry(sourceKey, text, file, normalizedPointer, textType),
        )
    }

    private fun escapePointer(value: String): String = value.replace("~", "~0").replace("/", "~1")

    private fun readUtf8Bounded(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > MAX_JSON_BYTES) {
                throw TranslationExtractionException(ExtractionErrorCode.RESOURCE_TOO_LARGE)
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")
    }

    private val MAP_FILE = Regex("(?i)Map\\d+\\.json")
}
