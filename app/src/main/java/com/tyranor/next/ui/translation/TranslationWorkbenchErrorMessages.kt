package com.tyranor.next.ui.translation

import com.tyranor.next.R
import com.tyranor.next.core.translation.TranslationWorkbenchError

internal fun TranslationWorkbenchError.messageResource(): Int = when (this) {
    TranslationWorkbenchError.INVALID_PROJECT_FILE -> R.string.translation_error_invalid_project_file
    TranslationWorkbenchError.UNSUPPORTED_PROJECT_FORMAT -> R.string.translation_error_unsupported_project_format
    TranslationWorkbenchError.PROJECT_TOO_LARGE -> R.string.translation_error_project_too_large
    TranslationWorkbenchError.EMPTY_PROJECT -> R.string.translation_error_empty_project
    TranslationWorkbenchError.DUPLICATE_SOURCE_KEY -> R.string.translation_error_duplicate_source_key
    TranslationWorkbenchError.PROJECT_NOT_FOUND -> R.string.translation_error_project_not_found
    TranslationWorkbenchError.PROJECT_ALREADY_EXISTS -> R.string.translation_error_project_already_exists
    TranslationWorkbenchError.UNSUPPORTED_ENGINE -> R.string.translation_error_unsupported_engine
    TranslationWorkbenchError.UNSUPPORTED_FORMAT -> R.string.translation_error_unsupported_format
    TranslationWorkbenchError.DIRECTORY_PERMISSION_LOST -> R.string.translation_error_directory_permission_lost
    TranslationWorkbenchError.EXTRACTOR_UNAVAILABLE -> R.string.translation_error_extractor_unavailable
    TranslationWorkbenchError.INVALID_GAME_DIRECTORY -> R.string.translation_error_invalid_game_directory
    TranslationWorkbenchError.CORRUPT_RESOURCE -> R.string.translation_error_corrupt_resource
    TranslationWorkbenchError.NO_TEXT_FOUND -> R.string.translation_error_no_text_found
    TranslationWorkbenchError.RESOURCE_TOO_LARGE -> R.string.translation_error_resource_too_large
    TranslationWorkbenchError.TASK_ALREADY_ACTIVE -> R.string.translation_error_task_already_active
    TranslationWorkbenchError.TASK_NOT_ACTIVE -> R.string.translation_error_task_not_active
    TranslationWorkbenchError.TRANSLATION_SERVICE_UNAVAILABLE -> R.string.translation_error_translation_service_unavailable
    TranslationWorkbenchError.INVALID_LANGUAGE_PAIR -> R.string.translation_error_invalid_language_pair
    TranslationWorkbenchError.NO_TRANSLATION_CANDIDATES -> R.string.translation_error_no_translation_candidates
    TranslationWorkbenchError.INVALID_GLOSSARY -> R.string.translation_error_invalid_glossary
    TranslationWorkbenchError.DUPLICATE_GLOSSARY_TERM -> R.string.translation_error_duplicate_glossary_term
    TranslationWorkbenchError.PROJECT_CONFIGURATION_CHANGED -> R.string.translation_error_project_configuration_changed
    TranslationWorkbenchError.MTOOL_ENGINE_UNSUPPORTED -> R.string.translation_error_mtool_engine_unsupported
    TranslationWorkbenchError.MTOOL_SOURCE_CONFLICT -> R.string.translation_error_mtool_source_conflict
    TranslationWorkbenchError.NO_TRANSLATED_ENTRIES -> R.string.translation_error_no_translated_entries
    TranslationWorkbenchError.NO_PROOFREAD_CANDIDATES -> R.string.translation_error_no_proofread_candidates
    TranslationWorkbenchError.INVALID_REVIEW_ENTRY -> R.string.translation_error_invalid_review_entry
    TranslationWorkbenchError.EXPORT_FAILED -> R.string.translation_error_export_failed
    TranslationWorkbenchError.EXPORT_HISTORY_FAILED -> R.string.translation_error_export_history_failed
    TranslationWorkbenchError.STORAGE_FAILURE -> R.string.translation_error_storage_failure
}
