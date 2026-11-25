package com.tyranor.next.core.translation

import com.tyranor.next.core.translation.storage.TranslationEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationLocalRuleCheckerTest {
    @Test
    fun detectsMissingTranslationPlaceholderAndLineBreakIssues() {
        val findings = TranslationLocalRuleChecker().inspect(
            listOf(
                entry("missing", "Hello", null),
                entry("format", "Hello {player}", "こんにちは"),
                entry("linebreak", "First\nSecond", "前後"),
                entry("valid", "\\V[2] Hello", "\\V[2] こんにちは"),
            ),
        )

        assertEquals(
            setOf(
                RuleFinding("missing", TranslationReviewIssueCode.MISSING_TRANSLATION),
                RuleFinding("format", TranslationReviewIssueCode.PLACEHOLDER_MISMATCH),
                RuleFinding("linebreak", TranslationReviewIssueCode.LINE_BREAK_MISMATCH),
            ),
            findings.toSet(),
        )
    }

    @Test
    fun detectsUnchangedTranslationWhenProjectLanguagesAreUnavailable() {
        val findings = TranslationLocalRuleChecker().inspect(
            entries = listOf(
                entry(
                    "unchanged",
                    "Hybrid fighters, comfortable battling with",
                    "Hybrid fighters, comfortable battling with",
                ),
            ),
        )

        assertEquals(
            setOf(RuleFinding("unchanged", TranslationReviewIssueCode.SIMILARITY_TOO_HIGH)),
            findings.toSet(),
        )
    }

    @Test
    fun eachDisplayedWarningCategoryHasALocalRuleFinding() {
        val findings = TranslationLocalRuleChecker().inspect(
            entries = listOf(
                entry("foreign", "Welcome", "Welcome"),
                entry("line-count", "First\nSecond", "第一行"),
                entry("glossary", "Potion", "药剂"),
                entry("preservation", "Hello {player}", "你好"),
                entry("punctuation", "Hello (world)", "你好"),
            ),
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
            glossary = listOf(TranslationGlossaryTerm("Potion", "药水")),
        ).groupBy { it.entryId }.mapValues { (_, entryFindings) -> entryFindings.map { it.code }.toSet() }

        assertTrue(TranslationReviewIssueCode.FOREIGN_TEXT_REMAINS in findings.getValue("foreign"))
        assertTrue(TranslationReviewIssueCode.SIMILARITY_TOO_HIGH in findings.getValue("foreign"))
        assertTrue(TranslationReviewIssueCode.LINE_BREAK_MISMATCH in findings.getValue("line-count"))
        assertTrue(TranslationReviewIssueCode.GLOSSARY_TERM_MISSING in findings.getValue("glossary"))
        assertTrue(TranslationReviewIssueCode.PLACEHOLDER_MISMATCH in findings.getValue("preservation"))
        assertTrue(TranslationReviewIssueCode.PUNCTUATION_MISMATCH in findings.getValue("punctuation"))
    }

    private fun entry(id: String, source: String, translation: String?) = TranslationEntryEntity(
        id = id,
        projectId = "project",
        sourceKey = id,
        sourceText = source,
        translatedText = translation,
        sourceFile = null,
        sourcePointer = null,
        textType = null,
        status = if (translation == null) "UNTRANSLATED" else "TRANSLATED",
    )
}
