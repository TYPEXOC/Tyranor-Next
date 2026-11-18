package com.tyranor.next.core.translation

import com.tyranor.next.core.translation.storage.TranslationEntryEntity
import org.junit.Assert.assertEquals
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
