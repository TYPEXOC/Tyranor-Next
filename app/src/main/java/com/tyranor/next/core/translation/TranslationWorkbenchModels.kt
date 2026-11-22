package com.tyranor.next.core.translation

import androidx.room.ColumnInfo

/** Paired project import/export formats registered by [TranslationProjectFormatRegistry]. */
enum class TranslationProjectFormat {
    CSV,
    JSON,
    MTOOL_JSON,
}

/** 项目来源。目录项目保留 SAF tree URI；文件导入项目保存导入数据和仅用于展示的原文件路径。 */
enum class TranslationProjectSource {
    GAME_DIRECTORY,
    IMPORTED_CSV,
    IMPORTED_JSON,
}

/** Durable lifecycle state for a project, separate from its most recent task state. */
enum class TranslationProjectState {
    NEW,
    PROCESSING,
    READY,
    PAUSED,
    FAILED,
}

/** 工具页项目卡片使用的只读汇总，不向 UI 暴露 Room 实体。 */
data class TranslationProjectCard(
    val id: String,
    val title: String,
    @ColumnInfo(name = "project_state") val projectState: String,
    @ColumnInfo(name = "engine_id") val engineId: String?,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_tree_uri") val sourceTreeUri: String?,
    @ColumnInfo(name = "source_display_path") val sourceDisplayPath: String?,
    @ColumnInfo(name = "source_language") val sourceLanguage: String?,
    @ColumnInfo(name = "target_language") val targetLanguage: String?,
    @ColumnInfo(name = "latest_task_type") val latestTaskType: String?,
    @ColumnInfo(name = "entry_count") val entryCount: Int,
    @ColumnInfo(name = "translated_count") val translatedCount: Int,
    @ColumnInfo(name = "latest_task_id") val latestTaskId: String?,
    @ColumnInfo(name = "latest_task_state") val latestTaskState: String?,
    @ColumnInfo(name = "latest_task_failure_code") val latestTaskFailureCode: String?,
    @ColumnInfo(name = "completed_files") val completedFiles: Int,
    @ColumnInfo(name = "total_files") val totalFiles: Int,
    @ColumnInfo(name = "item_total") val itemTotal: Int,
    @ColumnInfo(name = "completed_items") val completedItems: Int,
    @ColumnInfo(name = "failed_items") val failedItems: Int,
    @ColumnInfo(name = "review_completed_at") val reviewCompletedAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

data class TranslationGlossaryTerm(
    val sourceTerm: String,
    val targetTerm: String,
    val note: String? = null,
)

/** A named reusable glossary shown by the workbench and selected when a task starts. */
data class TranslationGlossary(
    val id: String,
    val name: String,
    val revision: Long,
    val termCount: Int,
    val sourceProjectId: String?,
    val updatedAt: Long,
)

/** 项目条目供列表预览和导出使用。每个来源位置独立保存，不按原文合并。 */
data class TranslationTextEntry(
    val id: String,
    val sourceKey: String,
    val sourceText: String,
    val translatedText: String?,
    val sourceFile: String?,
    val sourcePointer: String?,
    val textType: String?,
)

enum class TranslationReviewState {
    UNREVIEWED,
    CONFIRMED,
}

enum class TranslationReviewIssueCode {
    MISSING_TRANSLATION,
    PLACEHOLDER_MISMATCH,
    LINE_BREAK_MISMATCH,
    FOREIGN_TEXT_REMAINS,
    SIMILARITY_TOO_HIGH,
    LINE_COUNT_MISMATCH,
    GLOSSARY_TERM_MISSING,
    TEXT_PRESERVATION_MISMATCH,
    PUNCTUATION_MISMATCH,
}

data class TranslationReviewIssue(
    val id: String,
    val code: TranslationReviewIssueCode,
    val source: String,
    val severity: String,
    val state: String,
)

data class TranslationReviewEntry(
    val id: String,
    val sourceKey: String,
    val sourceText: String,
    val translatedText: String?,
    val sourceFile: String?,
    val sourcePointer: String?,
    val textType: String?,
    val reviewState: TranslationReviewState,
    val issues: List<TranslationReviewIssue>,
)

/** 项目导入/导出的中间数据形状；MTool JSON 仅携带原文与译文。 */
data class TranslationProjectArchive(
    val projectKey: String?,
    val title: String,
    val engineId: String?,
    val sourceLanguage: String?,
    val targetLanguage: String?,
    val entries: List<TranslationArchiveEntry>,
)

data class TranslationArchiveEntry(
    val sourceKey: String,
    val sourceText: String,
    val translatedText: String? = null,
    val sourceFile: String? = null,
    val sourcePointer: String? = null,
    val textType: String? = null,
)

enum class TranslationWorkbenchError {
    INVALID_PROJECT_FILE,
    UNSUPPORTED_PROJECT_FORMAT,
    PROJECT_TOO_LARGE,
    EMPTY_PROJECT,
    DUPLICATE_SOURCE_KEY,
    PROJECT_NOT_FOUND,
    PROJECT_ALREADY_EXISTS,
    UNSUPPORTED_ENGINE,
    UNSUPPORTED_FORMAT,
    DIRECTORY_PERMISSION_LOST,
    EXTRACTOR_UNAVAILABLE,
    INVALID_GAME_DIRECTORY,
    CORRUPT_RESOURCE,
    NO_TEXT_FOUND,
    RESOURCE_TOO_LARGE,
    TASK_ALREADY_ACTIVE,
    TASK_NOT_ACTIVE,
    TRANSLATION_SERVICE_UNAVAILABLE,
    INVALID_LANGUAGE_PAIR,
    NO_TRANSLATION_CANDIDATES,
    INVALID_GLOSSARY,
    DUPLICATE_GLOSSARY_NAME,
    GLOSSARY_NOT_FOUND,
    DUPLICATE_GLOSSARY_TERM,
    PROJECT_CONFIGURATION_CHANGED,
    MTOOL_SOURCE_CONFLICT,
    INVALID_REVIEW_ENTRY,
    EXPORT_FAILED,
    STORAGE_FAILURE,
}

class TranslationWorkbenchException(
    val code: TranslationWorkbenchError,
    cause: Throwable? = null,
) : Exception(code.name, cause)
