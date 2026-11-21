package com.tyranor.next.core.translation.service

enum class TranslationTextProblem {
    PLACEHOLDER_MISMATCH,
    LINE_BREAK_MISMATCH,
}

/** Shared deterministic checks used for remote translation responses and local review rules. */
object TranslationTextIntegrity {
    fun compare(source: String, translated: String): Set<TranslationTextProblem> = buildSet {
        if (formatTokens(source) != formatTokens(translated)) add(TranslationTextProblem.PLACEHOLDER_MISMATCH)
        if (newlineTokens(source) != newlineTokens(translated)) add(TranslationTextProblem.LINE_BREAK_MISMATCH)
    }

    private fun formatTokens(text: String): List<String> = FORMAT_TOKEN.findAll(text).map { it.value }.toList()
    private fun newlineTokens(text: String): List<String> = NEWLINE_TOKEN.findAll(text).map { it.value }.toList()

    private val FORMAT_TOKEN = Regex(
        """\\(?:[A-Za-z]+(?:\[[^\]\r\n]*\])?|[{}!>|^])|%(?:\d+\$)?[-+#0-9.]*[A-Za-z]|\{[A-Za-z0-9_.-]+\}|</?[A-Za-z][^>\r\n]*>""",
    )
    private val NEWLINE_TOKEN = Regex("\\r\\n|\\r|\\n")
}
