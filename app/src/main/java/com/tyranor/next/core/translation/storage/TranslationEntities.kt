package com.tyranor.next.core.translation.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tyranor.next.core.translation.TranslationProjectCard

@Entity(
    tableName = "translation_projects",
    foreignKeys = [
        ForeignKey(
            entity = TranslationGlossaryEntity::class,
            parentColumns = ["id"],
            childColumns = ["selected_glossary_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["identity"], unique = true),
        Index(value = ["exchange_key"], unique = true),
        Index(value = ["selected_glossary_id"]),
    ],
)
internal data class TranslationProjectEntity(
    @PrimaryKey val id: String,
    val identity: String,
    @ColumnInfo(name = "exchange_key") val exchangeKey: String,
    val title: String,
    @ColumnInfo(name = "project_state", defaultValue = "'NEW'") val projectState: String,
    @ColumnInfo(name = "engine_id") val engineId: String?,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_tree_uri") val sourceTreeUri: String?,
    @ColumnInfo(name = "source_language") val sourceLanguage: String?,
    @ColumnInfo(name = "target_language") val targetLanguage: String?,
    @ColumnInfo(name = "glossary_revision", defaultValue = "0") val glossaryRevision: Long = 0,
    @ColumnInfo(name = "selected_glossary_id") val selectedGlossaryId: String? = null,
    @ColumnInfo(name = "review_completed_at") val reviewCompletedAt: Long? = null,
    val revision: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "translation_entries",
    foreignKeys = [
        ForeignKey(
            entity = TranslationProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("project_id"), Index(value = ["project_id", "source_key"], unique = true)],
)
internal data class TranslationEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "source_text") val sourceText: String,
    @ColumnInfo(name = "translated_text") val translatedText: String?,
    @ColumnInfo(name = "source_file") val sourceFile: String?,
    @ColumnInfo(name = "source_pointer") val sourcePointer: String?,
    @ColumnInfo(name = "text_type") val textType: String?,
    val status: String,
    @ColumnInfo(name = "failure_code") val failureCode: String? = null,
    @ColumnInfo(name = "review_state", defaultValue = "'UNREVIEWED'") val reviewState: String = "UNREVIEWED",
)

@Entity(
    tableName = "translation_tasks",
    foreignKeys = [
        ForeignKey(
            entity = TranslationProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("project_id"), Index(value = ["project_id", "created_at"])],
)
internal data class TranslationTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val type: String,
    val state: String,
    @ColumnInfo(name = "extractor_id") val extractorId: String?,
    @ColumnInfo(name = "source_tree_uri") val sourceTreeUri: String?,
    @ColumnInfo(name = "config_snapshot_json") val configSnapshotJson: String,
    @ColumnInfo(name = "checkpoint_json") val checkpointJson: String,
    @ColumnInfo(name = "total_files") val totalFiles: Int,
    @ColumnInfo(name = "completed_files") val completedFiles: Int,
    @ColumnInfo(name = "entry_count") val entryCount: Int,
    @ColumnInfo(name = "item_total", defaultValue = "0") val itemTotal: Int = 0,
    @ColumnInfo(name = "completed_items", defaultValue = "0") val completedItems: Int = 0,
    @ColumnInfo(name = "failed_items", defaultValue = "0") val failedItems: Int = 0,
    @ColumnInfo(name = "failure_code") val failureCode: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "translation_exports",
    foreignKeys = [
        ForeignKey(
            entity = TranslationProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["project_id", "created_at"])],
)
internal data class TranslationExportEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val format: String,
    @ColumnInfo(name = "destination_name") val destinationName: String?,
    @ColumnInfo(name = "entry_count") val entryCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "translation_glossary_terms",
    foreignKeys = [
        ForeignKey(
            entity = TranslationProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("project_id"), Index(value = ["project_id", "source_term"], unique = true)],
)
internal data class TranslationGlossaryTermEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "source_term") val sourceTerm: String,
    @ColumnInfo(name = "target_term") val targetTerm: String,
    val note: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Reusable user glossary. Migrated project glossaries remain available after their source project is deleted. */
@Entity(
    tableName = "translation_glossaries",
    foreignKeys = [
        ForeignKey(
            entity = TranslationProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_project_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["source_project_id"], unique = true), Index(value = ["updated_at"])],
)
internal data class TranslationGlossaryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val revision: Long,
    @ColumnInfo(name = "source_project_id") val sourceProjectId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "translation_glossary_entries",
    foreignKeys = [
        ForeignKey(
            entity = TranslationGlossaryEntity::class,
            parentColumns = ["id"],
            childColumns = ["glossary_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("glossary_id"), Index(value = ["glossary_id", "source_term"], unique = true)],
)
internal data class TranslationGlossaryEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "glossary_id") val glossaryId: String,
    @ColumnInfo(name = "source_term") val sourceTerm: String,
    @ColumnInfo(name = "target_term") val targetTerm: String,
    val note: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "translation_review_issues",
    foreignKeys = [
        ForeignKey(entity = TranslationProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TranslationEntryEntity::class, parentColumns = ["id"], childColumns = ["entry_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TranslationTaskEntity::class, parentColumns = ["id"], childColumns = ["task_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("project_id"), Index("entry_id"), Index("task_id"), Index(value = ["project_id", "state"])],
)
internal data class TranslationReviewIssueEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "task_id") val taskId: String?,
    val source: String,
    val code: String,
    val severity: String,
    val state: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

internal data class TranslationEntryRow(
    val id: String,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "source_text") val sourceText: String,
    @ColumnInfo(name = "translated_text") val translatedText: String?,
    @ColumnInfo(name = "source_file") val sourceFile: String?,
    @ColumnInfo(name = "source_pointer") val sourcePointer: String?,
    @ColumnInfo(name = "text_type") val textType: String?,
    @ColumnInfo(name = "review_state") val reviewState: String,
)

internal data class TranslationReviewIssueRow(
    val id: String,
    @ColumnInfo(name = "entry_id") val entryId: String,
    val source: String,
    val code: String,
    val severity: String,
    val state: String,
)

internal data class TranslationGlossaryRow(
    val id: String,
    val name: String,
    val revision: Long,
    @ColumnInfo(name = "source_project_id") val sourceProjectId: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "term_count") val termCount: Int,
)

internal data class TranslationExportRow(
    val id: String,
    val format: String,
    @ColumnInfo(name = "destination_name") val destinationName: String?,
    @ColumnInfo(name = "entry_count") val entryCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

internal data class TranslationProjectCardRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "project_state") val projectState: String,
    @ColumnInfo(name = "engine_id") val engineId: String?,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_tree_uri") val sourceTreeUri: String?,
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
) {
    fun toModel() = TranslationProjectCard(
        id = id,
        title = title,
        projectState = projectState,
        engineId = engineId,
        sourceType = sourceType,
        sourceTreeUri = sourceTreeUri,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        entryCount = entryCount,
        translatedCount = translatedCount,
        latestTaskId = latestTaskId,
        latestTaskState = latestTaskState,
        latestTaskFailureCode = latestTaskFailureCode,
        completedFiles = completedFiles,
        totalFiles = totalFiles,
        latestTaskType = latestTaskType,
        itemTotal = itemTotal,
        completedItems = completedItems,
        failedItems = failedItems,
        reviewCompletedAt = reviewCompletedAt,
        updatedAt = updatedAt,
    )
}
