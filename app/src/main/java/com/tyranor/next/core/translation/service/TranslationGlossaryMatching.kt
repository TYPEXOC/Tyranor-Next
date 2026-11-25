package com.tyranor.next.core.translation.service

/** Keeps service-side glossary injection aligned with the source-term matching used by local review rules. */
internal object TranslationGlossaryMatching {
    fun matchingTerms(
        inputs: List<TranslationInput>,
        glossary: List<TranslationGlossaryEntry>,
    ): List<TranslationGlossaryEntry> = glossary.filter { term ->
        term.sourceTerm.isNotBlank() && term.targetTerm.isNotBlank() &&
            inputs.any { input -> containsSourceTerm(input.sourceText, term.sourceTerm) }
    }

    fun containsSourceTerm(sourceText: String, sourceTerm: String): Boolean {
        val term = sourceTerm.trim()
        return term.isNotEmpty() && sourceText.contains(term, ignoreCase = true)
    }
}
