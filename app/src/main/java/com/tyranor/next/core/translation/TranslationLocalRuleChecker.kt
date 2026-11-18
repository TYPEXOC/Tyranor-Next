package com.tyranor.next.core.translation

import com.tyranor.next.core.translation.service.TranslationTextIntegrity
import com.tyranor.next.core.translation.service.TranslationTextProblem
import com.tyranor.next.core.translation.storage.TranslationEntryEntity

internal class TranslationLocalRuleChecker {
    fun inspect(entries: List<TranslationEntryEntity>): List<RuleFinding> = entries.flatMap { entry ->
        val translation = entry.translatedText
        if (translation.isNullOrBlank()) {
            listOf(RuleFinding(entry.id, TranslationReviewIssueCode.MISSING_TRANSLATION))
        } else {
            TranslationTextIntegrity.compare(entry.sourceText, translation).map { problem ->
                RuleFinding(
                    entryId = entry.id,
                    code = when (problem) {
                        TranslationTextProblem.PLACEHOLDER_MISMATCH -> TranslationReviewIssueCode.PLACEHOLDER_MISMATCH
                        TranslationTextProblem.LINE_BREAK_MISMATCH -> TranslationReviewIssueCode.LINE_BREAK_MISMATCH
                    },
                )
            }
        }
    }
}

internal data class RuleFinding(val entryId: String, val code: TranslationReviewIssueCode)
