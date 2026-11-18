package com.tyranor.next.core.translation.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tyranor.next.core.translation.TranslationProjectCard

@Entity(
    tableName = "translation_projects",
    indices = [Index(value = ["identity"], unique = true), Index(value = ["exchange_key"], unique = true)],
)
internal data class TranslationProjectEntity(
    @PrimaryKey val id: String,
    val identity: String,
    @ColumnInfo(name = "exchange_key") val exchangeKey: String,
    val title: String,
    @ColumnInfo(name = "engine_id") val engineId: String?,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_tree_uri") val sourceTreeUri: String?,
    @ColumnInfo(name = "source_language") val sourceLanguage: String?,
    @ColumnInfo(name = "target_language") val targetLanguage: String?,
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
    @ColumnInfo(name = "failure_code") val failureCode: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
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
)

internal data class TranslationProjectCardRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "engine_id") val engineId: String?,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_tree_uri") val sourceTreeUri: String?,
    @ColumnInfo(name = "entry_count") val entryCount: Int,
    @ColumnInfo(name = "translated_count") val translatedCount: Int,
    @ColumnInfo(name = "latest_task_id") val latestTaskId: String?,
    @ColumnInfo(name = "latest_task_state") val latestTaskState: String?,
    @ColumnInfo(name = "latest_task_failure_code") val latestTaskFailureCode: String?,
    @ColumnInfo(name = "completed_files") val completedFiles: Int,
    @ColumnInfo(name = "total_files") val totalFiles: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    fun toModel() = TranslationProjectCard(
        id = id,
        title = title,
        engineId = engineId,
        sourceType = sourceType,
        sourceTreeUri = sourceTreeUri,
        entryCount = entryCount,
        translatedCount = translatedCount,
        latestTaskId = latestTaskId,
        latestTaskState = latestTaskState,
        latestTaskFailureCode = latestTaskFailureCode,
        completedFiles = completedFiles,
        totalFiles = totalFiles,
        updatedAt = updatedAt,
    )
}
