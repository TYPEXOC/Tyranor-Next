package com.tyranor.next.core.translation.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class TranslationWorkbenchDao {
    @Query(
        """SELECT p.id, p.title, p.engine_id, p.source_type, p.source_tree_uri,
            (SELECT COUNT(*) FROM translation_entries e WHERE e.project_id = p.id) AS entry_count,
            (SELECT COUNT(*) FROM translation_entries e WHERE e.project_id = p.id AND e.translated_text IS NOT NULL AND e.translated_text != '') AS translated_count,
            (SELECT t.id FROM translation_tasks t WHERE t.project_id = p.id ORDER BY t.created_at DESC LIMIT 1) AS latest_task_id,
            (SELECT t.state FROM translation_tasks t WHERE t.project_id = p.id ORDER BY t.created_at DESC LIMIT 1) AS latest_task_state,
            (SELECT t.failure_code FROM translation_tasks t WHERE t.project_id = p.id ORDER BY t.created_at DESC LIMIT 1) AS latest_task_failure_code,
            COALESCE((SELECT t.completed_files FROM translation_tasks t WHERE t.project_id = p.id ORDER BY t.created_at DESC LIMIT 1), 0) AS completed_files,
            COALESCE((SELECT t.total_files FROM translation_tasks t WHERE t.project_id = p.id ORDER BY t.created_at DESC LIMIT 1), 0) AS total_files,
            p.updated_at
            FROM translation_projects p ORDER BY p.updated_at DESC""",
    )
    abstract fun observeProjectCards(): Flow<List<TranslationProjectCardRow>>

    @Query("SELECT * FROM translation_projects WHERE id = :id LIMIT 1")
    abstract suspend fun getProject(id: String): TranslationProjectEntity?

    @Query("SELECT * FROM translation_projects WHERE identity = :identity LIMIT 1")
    abstract suspend fun getProjectByIdentity(identity: String): TranslationProjectEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertProjectIfAbsent(project: TranslationProjectEntity): Long

    @Upsert
    abstract suspend fun upsertProject(project: TranslationProjectEntity)

    @Query("DELETE FROM translation_projects WHERE id = :id")
    abstract suspend fun deleteProject(id: String): Int

    @Query(
        "SELECT id, source_key, source_text, translated_text, source_file, source_pointer, text_type " +
            "FROM translation_entries WHERE project_id = :projectId ORDER BY source_key",
    )
    abstract fun observeEntries(projectId: String): Flow<List<TranslationEntryRow>>

    @Query("SELECT * FROM translation_entries WHERE project_id = :projectId ORDER BY source_key")
    abstract suspend fun getEntries(projectId: String): List<TranslationEntryEntity>

    @Query("SELECT * FROM translation_entries WHERE project_id = :projectId AND source_key IN (:sourceKeys)")
    abstract suspend fun getEntriesByKeys(projectId: String, sourceKeys: List<String>): List<TranslationEntryEntity>

    @Upsert
    abstract suspend fun upsertEntries(entries: List<TranslationEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertEntries(entries: List<TranslationEntryEntity>)

    @Query("SELECT * FROM translation_tasks WHERE id = :id LIMIT 1")
    abstract suspend fun getTask(id: String): TranslationTaskEntity?

    @Query(
        "SELECT * FROM translation_tasks WHERE project_id = :projectId " +
            "AND state IN ('QUEUED', 'RUNNING') ORDER BY created_at DESC LIMIT 1",
    )
    abstract suspend fun getActiveTask(projectId: String): TranslationTaskEntity?

    @Query("SELECT * FROM translation_tasks WHERE state IN ('QUEUED', 'RUNNING') ORDER BY created_at ASC LIMIT 1")
    abstract suspend fun getNextRunnableTask(): TranslationTaskEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM translation_tasks WHERE state IN ('QUEUED', 'RUNNING'))")
    abstract suspend fun hasRunnableTasks(): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertTask(task: TranslationTaskEntity)

    @Query("UPDATE translation_tasks SET state = :state, updated_at = :updatedAt WHERE id = :taskId")
    abstract suspend fun updateTaskState(taskId: String, state: String, updatedAt: Long): Int

    @Query(
        "UPDATE translation_tasks SET state = 'CANCELLED', updated_at = :updatedAt " +
            "WHERE id = :taskId AND state IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun cancelTask(taskId: String, updatedAt: Long): Int

    @Query(
        "UPDATE translation_tasks SET state = 'CANCELLED', updated_at = :updatedAt " +
            "WHERE project_id = :projectId AND state IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun cancelProjectTasks(projectId: String, updatedAt: Long): Int

    @Query(
        "UPDATE translation_tasks SET state = 'FAILED', failure_code = :failureCode, updated_at = :updatedAt " +
            "WHERE id = :taskId AND state = 'QUEUED'",
    )
    abstract suspend fun failQueuedTask(taskId: String, failureCode: String, updatedAt: Long): Int

    @Query(
        "UPDATE translation_tasks SET state = 'RUNNING', updated_at = :updatedAt " +
            "WHERE id = :taskId AND state IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun markTaskRunning(taskId: String, updatedAt: Long): Int

    @Query(
        "UPDATE translation_tasks SET total_files = :totalFiles, updated_at = :updatedAt " +
            "WHERE id = :taskId AND state = 'RUNNING'",
    )
    abstract suspend fun setTaskFileTotal(taskId: String, totalFiles: Int, updatedAt: Long): Int

    @Query("UPDATE translation_tasks SET state = 'FAILED', failure_code = :failureCode, updated_at = :updatedAt WHERE id = :taskId AND state = 'RUNNING'")
    abstract suspend fun failTask(taskId: String, failureCode: String, updatedAt: Long): Int

    @Query("UPDATE translation_tasks SET state = 'COMPLETED', failure_code = NULL, updated_at = :updatedAt WHERE id = :taskId AND state = 'RUNNING'")
    abstract suspend fun completeTask(taskId: String, updatedAt: Long): Int

    @Query(
        "UPDATE translation_tasks SET checkpoint_json = :checkpointJson, completed_files = :completedFiles, " +
            "entry_count = entry_count + :newEntries, updated_at = :updatedAt " +
            "WHERE id = :taskId AND state = 'RUNNING'",
    )
    abstract suspend fun updateTaskCheckpoint(
        taskId: String,
        checkpointJson: String,
        completedFiles: Int,
        newEntries: Int,
        updatedAt: Long,
    ): Int

    @Transaction
    open suspend fun createTaskIfProjectIdle(task: TranslationTaskEntity): Boolean {
        if (getProject(task.projectId) == null || getActiveTask(task.projectId) != null) return false
        insertTask(task)
        return true
    }

    @Transaction
    open suspend fun createContinuationTask(task: TranslationTaskEntity, previousTaskId: String): Boolean {
        val previous = getTask(previousTaskId) ?: return false
        if (previous.projectId != task.projectId || previous.state !in setOf("FAILED", "CANCELLED")) return false
        if (getProject(task.projectId) == null || getActiveTask(task.projectId) != null) return false
        insertTask(
            task.copy(
                checkpointJson = previous.checkpointJson,
                totalFiles = previous.totalFiles,
                completedFiles = previous.completedFiles,
                entryCount = previous.entryCount,
            ),
        )
        return true
    }

    @Transaction
    open suspend fun importProject(project: TranslationProjectEntity, entries: List<TranslationEntryEntity>) {
        insertProjectIfAbsent(project)
        if (getProjectByIdentity(project.identity)?.id != project.id) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_ALREADY_EXISTS)
        }
        if (entries.isNotEmpty()) insertEntries(entries)
    }

    @Transaction
    open suspend fun commitExtractedFile(
        projectId: String,
        taskId: String,
        checkpointJson: String,
        completedFiles: Int,
        entries: List<TranslationEntryEntity>,
        updatedAt: Long,
    ): Boolean {
        val task = getTask(taskId) ?: return false
        if (task.state != "RUNNING") return false
        val keys = entries.map { it.sourceKey }
        val existing = if (keys.isEmpty()) emptyMap() else getEntriesByKeys(projectId, keys).associateBy { it.sourceKey }
        val merged = entries.map { fresh ->
            val previous = existing[fresh.sourceKey]
            when {
                previous == null -> fresh
                previous.sourceText == fresh.sourceText -> fresh.copy(
                    id = previous.id,
                    translatedText = previous.translatedText,
                    status = previous.status,
                )
                else -> fresh.copy(id = previous.id, translatedText = null, status = "UNTRANSLATED")
            }
        }
        if (merged.isNotEmpty()) upsertEntries(merged)
        val newEntries = merged.count { fresh -> existing[fresh.sourceKey] == null }
        if (updateTaskCheckpoint(taskId, checkpointJson, completedFiles, newEntries, updatedAt) != 1) return false
        val project = getProject(projectId) ?: return false
        upsertProject(project.copy(revision = project.revision + 1, updatedAt = updatedAt))
        return true
    }
}
