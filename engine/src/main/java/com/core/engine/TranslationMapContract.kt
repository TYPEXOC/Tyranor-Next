package com.core.engine

/** Read-only normalized translation-map content URI shared by the App and engine hosts. */
object TranslationMapContract {
    const val EXTRA_TRANSLATION_MAP_URI = "com.tyranor.runtime.extra.TRANSLATION_MAP_URI"
    const val AUTHORITY_SUFFIX = ".translationmaps"
    const val SESSION_PATH = "session"
    const val COLUMN_REVISION = "revision"
}
