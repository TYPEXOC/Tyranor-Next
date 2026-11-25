package com.tyranor.next.core.translation.extraction.engines.rpgmaker.rm2k

import com.tyranor.next.core.translation.extraction.ExtractedTextEntry
import com.tyranor.next.core.translation.extraction.ExtractionErrorCode
import com.tyranor.next.core.translation.extraction.TranslationExtractionException
import org.json.JSONArray
import org.json.JSONObject

/** Extracts visible text from the structured LCF JSON shape for RPG Maker 2000/2003 only. */
internal object RpgMaker2000JsonTextReader {
    private val textFields = setOf(
        "name", "title", "description", "nickname", "profile", "message1", "message2", "message3", "message4",
        "currencyunit", "gametitle", "displayname", "usingmessage1", "usingmessage2",
    )
    private val skippedFields = setOf("note", "script", "scripts", "parameters")

    fun extract(file: String, document: JSONObject): List<ExtractedTextEntry> {
        val output = LinkedHashMap<String, ExtractedTextEntry>()
        try {
            visit(document, file, "", termsContext = false, output)
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
                val keys = value.keys().asSequence().toList().sorted()
                for (rawField in keys) {
                    val field = rawField.lowercase()
                    if (field in skippedFields) continue
                    val child = value.opt(rawField)
                    val childPointer = "$pointer/${escapePointer(rawField)}"
                    if (field == "event_commands") {
                        visitEventCommands(child, file, childPointer, output)
                    } else if (field == "terms") {
                        visit(child, file, childPointer, termsContext = true, output)
                    } else {
                        val normalized = field.filter(Char::isLetterOrDigit)
                        if (child is String && (termsContext || normalized in textFields)) {
                            add(child, file, childPointer, textTypeFor(file), output)
                        } else {
                            visit(child, file, childPointer, termsContext, output)
                        }
                    }
                }
            }
            is JSONArray -> {
                if (value.length() > 0 && (0 until value.length()).all { isEventCommand(value.optJSONObject(it)) }) {
                    visitEventCommands(value, file, pointer, output)
                } else {
                    for (index in 0 until value.length()) {
                        visit(value.opt(index), file, "$pointer/$index", termsContext, output)
                    }
                }
            }
        }
    }

    private fun visitEventCommands(
        value: Any?,
        file: String,
        pointer: String,
        output: MutableMap<String, ExtractedTextEntry>,
    ) {
        val commands = value as? JSONArray ?: return
        val dialogueLines = mutableListOf<String>()
        var firstDialogueIndex = -1
        fun flushDialogue() {
            if (dialogueLines.isNotEmpty()) {
                add(
                    dialogueLines.joinToString("\n"),
                    file,
                    "$pointer/$firstDialogueIndex/dialogue",
                    "dialogue",
                    output,
                )
                dialogueLines.clear()
            }
            firstDialogueIndex = -1
        }

        for (index in 0 until commands.length()) {
            val command = commands.optJSONObject(index) ?: continue
            val code = command.optInt("code", -1)
            val text = command.opt("string") as? String
            when (code) {
                SHOW_MESSAGE -> {
                    flushDialogue()
                    firstDialogueIndex = index
                    text?.takeIf(String::isNotBlank)?.let(dialogueLines::add)
                }
                SHOW_MESSAGE_CONTINUATION -> {
                    if (firstDialogueIndex < 0) firstDialogueIndex = index
                    text?.takeIf(String::isNotBlank)?.let(dialogueLines::add)
                }
                SHOW_CHOICE_OPTION -> {
                    flushDialogue()
                    text?.let { add(it, file, "$pointer/$index/string", "choice", output) }
                }
                CHANGE_HERO_NAME, CHANGE_HERO_TITLE -> {
                    flushDialogue()
                    text?.let { add(it, file, "$pointer/$index/string", "event", output) }
                }
                else -> flushDialogue()
            }
        }
        flushDialogue()
    }

    private fun isEventCommand(value: JSONObject?): Boolean =
        value?.has("code") == true && value.has("string")

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
        output.putIfAbsent(sourceKey, ExtractedTextEntry(sourceKey, text, file, location, type))
    }

    private fun textTypeFor(file: String): String =
        if (file.substringAfterLast('/').startsWith("Map", ignoreCase = true)) "event" else "database"

    private fun escapePointer(value: String): String = value.replace("~", "~0").replace("/", "~1")

    private const val SHOW_MESSAGE = 10_110
    private const val SHOW_MESSAGE_CONTINUATION = 20_110
    private const val SHOW_CHOICE_OPTION = 20_140
    private const val CHANGE_HERO_NAME = 10_610
    private const val CHANGE_HERO_TITLE = 10_620
}
