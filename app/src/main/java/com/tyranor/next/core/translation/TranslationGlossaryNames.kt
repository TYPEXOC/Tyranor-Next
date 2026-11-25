package com.tyranor.next.core.translation

import java.util.Locale

internal fun normalizeTranslationGlossaryName(name: String): String =
    name.trim().lowercase(Locale.ROOT)
