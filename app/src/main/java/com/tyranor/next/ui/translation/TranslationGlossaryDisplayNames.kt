package com.tyranor.next.ui.translation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.translation.TranslationGlossary
import com.tyranor.next.core.translation.normalizeTranslationGlossaryName

internal fun duplicateGlossaryOrdinals(glossaries: List<TranslationGlossary>): List<Int?> {
    val counts = glossaries.groupingBy { normalizeTranslationGlossaryName(it.name) }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return glossaries.map { glossary ->
        val name = normalizeTranslationGlossaryName(glossary.name)
        if ((counts[name] ?: 0) < 2) {
            null
        } else {
            val ordinal = (seen[name] ?: 0) + 1
            seen[name] = ordinal
            ordinal
        }
    }
}

@Composable
internal fun translationGlossaryDisplayNames(glossaries: List<TranslationGlossary>): List<String> {
    val ordinals = remember(glossaries) { duplicateGlossaryOrdinals(glossaries) }
    val names = ArrayList<String>(glossaries.size)
    for (index in glossaries.indices) {
        val ordinal = ordinals[index]
        names += if (ordinal == null) {
            glossaries[index].name
        } else {
            stringResource(R.string.translation_glossary_duplicate_option, glossaries[index].name, ordinal)
        }
    }
    return names
}
