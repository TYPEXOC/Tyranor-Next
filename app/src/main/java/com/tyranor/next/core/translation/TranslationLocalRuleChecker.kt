package com.tyranor.next.core.translation

import com.tyranor.next.core.translation.service.TranslationTextIntegrity
import com.tyranor.next.core.translation.service.TranslationTextProblem
import com.tyranor.next.core.translation.service.TranslationGlossaryMatching
import com.tyranor.next.core.translation.storage.TranslationEntryEntity
import java.util.Locale

internal class TranslationLocalRuleChecker {
    fun inspect(
        entries: List<TranslationEntryEntity>,
        sourceLanguage: String? = null,
        targetLanguage: String? = null,
        glossary: List<TranslationGlossaryTerm> = emptyList(),
    ): List<RuleFinding> = entries.flatMap { entry ->
        val translation = entry.translatedText
        if (translation.isNullOrBlank()) {
            listOf(RuleFinding(entry.id, TranslationReviewIssueCode.MISSING_TRANSLATION))
        } else {
            val sourceText = FORMAT_TOKEN.replace(entry.sourceText, "")
            val translatedText = FORMAT_TOKEN.replace(translation, "")
            buildList {
                if (TranslationTextIntegrity.compare(entry.sourceText, translation)
                        .contains(TranslationTextProblem.PLACEHOLDER_MISMATCH)
                ) {
                    add(RuleFinding(entry.id, TranslationReviewIssueCode.PLACEHOLDER_MISMATCH))
                }
                if (lineCount(entry.sourceText) != lineCount(translation)) {
                    add(RuleFinding(entry.id, TranslationReviewIssueCode.LINE_BREAK_MISMATCH))
                }
                val residues = targetLanguage?.let { collectForeignResidues(translatedText, it) }.orEmpty()
                if (residues.isNotEmpty()) {
                    add(RuleFinding(entry.id, TranslationReviewIssueCode.FOREIGN_TEXT_REMAINS))
                }
                if (sourceLanguage != null && targetLanguage != null && isTooSimilar(
                        source = sourceText,
                        translated = translatedText,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        hasForeignResidue = residues.isNotEmpty(),
                    )
                ) {
                    add(RuleFinding(entry.id, TranslationReviewIssueCode.SIMILARITY_TOO_HIGH))
                }
                if (glossary.any { term ->
                        term.sourceTerm.isNotBlank() && term.targetTerm.isNotBlank() &&
                            TranslationGlossaryMatching.containsSourceTerm(entry.sourceText, term.sourceTerm) &&
                            !translation.contains(term.targetTerm.trim(), ignoreCase = true)
                    }
                ) {
                    add(RuleFinding(entry.id, TranslationReviewIssueCode.GLOSSARY_TERM_MISSING))
                }
                if (hasPunctuationStructureMismatch(sourceText, translatedText)) {
                    add(RuleFinding(entry.id, TranslationReviewIssueCode.PUNCTUATION_MISMATCH))
                }
            }
        }
    }

    private fun lineCount(text: String): Int = NEWLINE.findAll(text).count() + 1

    private fun isTooSimilar(
        source: String,
        translated: String,
        sourceLanguage: String,
        targetLanguage: String,
        hasForeignResidue: Boolean,
    ): Boolean {
        val sourceText = source.trim()
        val translatedText = translated.trim()
        if (sourceText.isEmpty() || translatedText.isEmpty()) return false
        val sourceCode = normalizeLanguage(sourceLanguage)
        val targetCode = normalizeLanguage(targetLanguage)
        val isChineseTarget = targetCode == "ZH" || targetCode == "ZH-HANT"
        if (isChineseTarget && sourceCode in setOf("JA", "KO", "AUTO", "AUTO-DETECT")) {
            return hasForeignResidue && similarByCharacters(sourceText, translatedText)
        }
        return sourceText.contains(translatedText) || translatedText.contains(sourceText) ||
            similarByCharacters(sourceText, translatedText)
    }

    private fun similarByCharacters(left: String, right: String): Boolean {
        val leftCharacters = left.codePoints().toArray().toSet()
        val rightCharacters = right.codePoints().toArray().toSet()
        val unionSize = (leftCharacters + rightCharacters).size
        if (unionSize == 0) return false
        val similarity = leftCharacters.intersect(rightCharacters).size.toDouble() / unionSize
        return similarity > SIMILARITY_THRESHOLD
    }

    private fun collectForeignResidues(text: String, targetLanguage: String): List<String> {
        val targetScripts = targetScripts(targetLanguage) ?: return emptyList()
        val fragments = linkedSetOf<String>()
        val current = StringBuilder()
        var graphemeCount = 0
        var hasNonLatinResidue = false

        fun flush() {
            if (current.isEmpty()) return
            val fragment = current.toString()
            val isExemptLatin = !hasNonLatinResidue &&
                (graphemeCount == 1 || SHORT_ASCII_UPPERCASE.matches(fragment))
            if (!isExemptLatin) fragments += fragment
            current.setLength(0)
            graphemeCount = 0
            hasNonLatinResidue = false
        }

        text.codePoints().forEach { codePoint ->
            val character = String(Character.toChars(codePoint))
            val script = Character.UnicodeScript.of(codePoint)
            val isLetter = Character.isLetter(codePoint)
            val isMark = Character.getType(codePoint) in MARK_TYPES
            when {
                isLetter && script in targetScripts -> flush()
                isLetter && script != Character.UnicodeScript.COMMON &&
                    script != Character.UnicodeScript.INHERITED -> {
                    current.append(character)
                    graphemeCount += 1
                    hasNonLatinResidue = hasNonLatinResidue || script != Character.UnicodeScript.LATIN
                }
                isMark && current.isNotEmpty() -> current.append(character)
                else -> flush()
            }
        }
        flush()
        return fragments.toList()
    }

    private fun targetScripts(language: String): Set<Character.UnicodeScript>? = when (normalizeLanguage(language)) {
        "ZH", "ZH-HANT" -> setOf(Character.UnicodeScript.HAN)
        "JA" -> setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
        "KO" -> setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HANGUL)
        "BG", "RU", "UK" -> setOf(Character.UnicodeScript.CYRILLIC)
        "SR" -> setOf(Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.LATIN)
        "EL" -> setOf(Character.UnicodeScript.GREEK)
        "AR", "FA" -> setOf(Character.UnicodeScript.ARABIC)
        "HE" -> setOf(Character.UnicodeScript.HEBREW)
        "HI" -> setOf(Character.UnicodeScript.DEVANAGARI)
        "TH" -> setOf(Character.UnicodeScript.THAI)
        "CA", "CS", "DA", "DE", "EN", "ES", "ET", "FI", "FR", "HR", "HU", "ID", "IT",
        "LT", "LV", "MS", "NB", "NL", "PL", "PT", "RO", "SK", "SL", "SV", "TR", "VI" ->
            setOf(Character.UnicodeScript.LATIN)
        else -> null
    }

    private fun normalizeLanguage(language: String): String =
        language.trim().replace('_', '-').uppercase(Locale.ROOT).let { normalized ->
            if (normalized.startsWith("ZH-") && normalized !in setOf("ZH-HANT", "ZH-TW", "ZH-HK")) {
                "ZH"
            } else if (normalized in setOf("ZH-TW", "ZH-HK")) {
                "ZH-HANT"
            } else {
                normalized.substringBefore('-')
            }
        }

    private fun hasPunctuationStructureMismatch(source: String, translated: String): Boolean {
        val sourceStructure = source.filter { it in STRUCTURAL_PUNCTUATION }.map(::punctuationGroup)
        val translatedStructure = translated.filter { it in STRUCTURAL_PUNCTUATION }.map(::punctuationGroup)
        return sourceStructure != translatedStructure
    }

    private fun punctuationGroup(character: Char): Int = when (character) {
        in QUOTES -> 0
        '(', '（' -> 1
        ')', '）' -> 2
        '[', '［', '【' -> 3
        ']', '］', '】' -> 4
        '{', '｛' -> 5
        '}', '｝' -> 6
        '《' -> 7
        '》' -> 8
        else -> -1
    }

    private companion object {
        const val SIMILARITY_THRESHOLD = 0.8
        val FORMAT_TOKEN = Regex(
            """\\(?:[A-Za-z]+(?:\[[^\]\r\n]*\])?|[{}!>|^])|%(?:\d+\$)?[-+#0-9.]*[A-Za-z]|\{[A-Za-z0-9_.-]+\}|</?[A-Za-z][^>\r\n]*>""",
        )
        val NEWLINE = Regex("\\r\\n|\\r|\\n")
        val SHORT_ASCII_UPPERCASE = Regex("[A-Z]{2,4}")
        val QUOTES = setOf('“', '”', '„', '‟', '「', '」', '『', '』', '«', '»', '"', '＂')
        val STRUCTURAL_PUNCTUATION = QUOTES + setOf(
            '(', '（', ')', '）', '[', '［', '【', ']', '］', '】', '{', '｛', '}', '｝', '《', '》',
        )
        val MARK_TYPES = setOf<Int>(
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
        )
    }
}

internal data class RuleFinding(val entryId: String, val code: TranslationReviewIssueCode)
