package com.tyranor.next.core.translation.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import com.tyranor.next.core.translation.TranslationProjectState
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class TranslationWorkbenchDao {
    @Query(
        """SELECT p.id, p.title, p.project_state, p.engine_id, p.source_type, p.source_tree_uri,
            (SELECT t.type FROM translation_tasks t WHERE t.project_id = p.id ORDER BY t.created_at DESC, t.id DESC LIMIT 1) AS latest_task_type,
            (SELECT COUNT(*) FROM translation_entries e WHERE e.project_id = p.id) AS entry_count,
            (SELECT COUNT(*) FROM translation_entries e WHERE e.project_id = p.id AND e.translated_text IS NOT NULL AND e.translated_text != '') AS translated_count,
            (SELECT t.id FROM translation_tasks t WHERE t.project_id = p.id ORDER BY t.created_at DESC, t.id DESC LIMIT 1) AS latest_task_id,
            (SELECT t.state FROM translation_tasks t WHERE t.project_id = p.id ORDER BY t.created_at DESC, t.id DESC LIMIT 1) AS latest_task_state,
            (SELECT t.failure_code FROM translation_tasks t WHERE t.project_id = p.id ORDER BY t.created_at DESC, t.id DESC LIMIT 1) AS latest_task_failure_code,
            COALESCE((SELECT t.completed_files FROM translation_tasks t WHERE t.project_id = p.id ORDER BY t.created_at DESC, t.id DESC LIMIT 1), 0) AS completed_files,
            COALESCE((SELECT t.total_files FROM translation_tasks t WHERE t.project_id = p.id ORDER BY t.created_at DESC, t.id DESC LIMIT 1), 0) AS total_files,
            COALESCE((SELECT t.item_total FROM translation_tasks t WHERE t.project_id = p.id ORDER BY t.created_at DESC, t.id DESC LIMIT 1), 0) AS item_total,
            COALESCE((SELECT t.completed_items FROM translation_tasks t WHERE t.project_id = p.id ORDER BY t.created_at DESC, t.id DESC LIMIT 1), 0) AS completed_items,
            COALESCE((SELECT t.failed_items FROM translation_tasks t WHERE t.project_id = p.id ORDER BY t.created_at DESC, t.id DESC LIMIT 1), 0) AS failed_items,
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

    @Query(
        "SELECT id, format, destination_name, entry_count, created_at FROM translation_exports " +
            "WHERE project_id = :projectId ORDER BY created_at DESC, id DESC",
    )
    abstract fun observeExportHistory(projectId: String): Flow<List<TranslationExportRow>>

    @Query("SELECT * FROM translation_entries WHERE project_id = :projectId ORDER BY source_key")
    abstract suspend fun getEntries(projectId: String): List<TranslationEntryEntity>

    @Query(
        "SELECT * FROM translation_entries WHERE project_id = :projectId AND " +
            "(status = 'UNTRANSLATED' OR (:retryFailed = 1 AND status = 'FAILED')) " +
            "ORDER BY source_key LIMIT :limit",
    )
    abstract suspend fun getTranslationCandidates(
        projectId: String,
        retryFailed: Boolean,
        limit: Int,
    ): List<TranslationEntryEntity>

    @Query(
        "SELECT COUNT(*) FROM translation_entries WHERE project_id = :projectId AND " +
            "(status = 'UNTRANSLATED' OR (:retryFailed = 1 AND status = 'FAILED'))",
    )
    abstract suspend fun countTranslationCandidates(projectId: String, retryFailed: Boolean): Int

    @Query("SELECT * FROM translation_entries WHERE project_id = :projectId AND source_key IN (:sourceKeys)")
    abstract suspend fun getEntriesByKeys(projectId: String, sourceKeys: List<String>): List<TranslationEntryEntity>

    @Upsert
    abstract suspend fun upsertEntries(entries: List<TranslationEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertEntries(entries: List<TranslationEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertExportRecord(record: TranslationExportEntity)

    @Query("UPDATE translation_projects SET updated_at = :updatedAt WHERE id = :projectId")
    abstract suspend fun touchProject(projectId: String, updatedAt: Long): Int

    @Transaction
    open suspend fun recordExport(record: TranslationExportEntity) {
        insertExportRecord(record)
        touchProject(record.projectId, record.createdAt)
    }

    @Query("SELECT * FROM translation_service_profiles ORDER BY updated_at DESC, id")
    abstract fun observeServiceProfiles(): Flow<List<TranslationServiceProfileEntity>>

    @Query("SELECT * FROM translation_service_profiles WHERE id = :id LIMIT 1")
    abstract suspend fun getServiceProfile(id: String): TranslationServiceProfileEntity?

    @Query("SELECT * FROM translation_service_profiles")
    abstract suspend fun getServiceProfiles(): List<TranslationServiceProfileEntity>

    @Upsert
    abstract suspend fun upsertServiceProfile(profile: TranslationServiceProfileEntity)

    @Query("DELETE FROM translation_service_profiles WHERE id = :id")
    abstract suspend fun deleteServiceProfile(id: String): Int

    @Query("SELECT * FROM translation_glossary_terms WHERE project_id = :projectId ORDER BY source_term COLLATE NOCASE")
    abstract fun observeGlossary(projectId: String): Flow<List<TranslationGlossaryTermEntity>>

    @Query("SELECT * FROM translation_glossary_terms WHERE project_id = :projectId ORDER BY source_term COLLATE NOCASE")
    abstract suspend fun getGlossary(projectId: String): List<TranslationGlossaryTermEntity>

    @Query("DELETE FROM translation_glossary_terms WHERE project_id = :projectId")
    abstract suspend fun deleteGlossary(projectId: String)

    @Query(
        "UPDATE translation_entries SET status = 'UNTRANSLATED', failure_code = NULL " +
            "WHERE project_id = :projectId AND status = 'FAILED'",
    )
    abstract suspend fun resetFailedEntriesForRetry(projectId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertGlossaryTerms(terms: List<TranslationGlossaryTermEntity>)

    @Query(
        "UPDATE translation_projects SET glossary_revision = glossary_revision + 1, " +
            "revision = revision + 1, updated_at = :updatedAt WHERE id = :projectId",
    )
    abstract suspend fun bumpGlossaryRevision(projectId: String, updatedAt: Long): Int

    @Transaction
    open suspend fun replaceGlossary(
        projectId: String,
        terms: List<TranslationGlossaryTermEntity>,
        updatedAt: Long,
    ): Long? {
        if (getProject(projectId) == null) return null
        if (getActiveTask(projectId) != null) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.TASK_ALREADY_ACTIVE)
        }
        deleteGlossary(projectId)
        if (terms.isNotEmpty()) insertGlossaryTerms(terms)
        bumpGlossaryRevision(projectId, updatedAt)
        return getProject(projectId)?.glossaryRevision
    }

    @Query("SELECT * FROM translation_tasks WHERE id = :id LIMIT 1")
    abstract suspend fun getTask(id: String): TranslationTaskEntity?

    @Query(
        "SELECT * FROM translation_tasks WHERE project_id = :projectId " +
            "AND state IN ('QUEUED', 'RUNNING') ORDER BY created_at DESC, id DESC LIMIT 1",
    )
    abstract suspend fun getActiveTask(projectId: String): TranslationTaskEntity?

    @Query("SELECT * FROM translation_tasks WHERE state IN ('QUEUED', 'RUNNING') ORDER BY created_at ASC, id ASC LIMIT 1")
    abstract suspend fun getNextRunnableTask(): TranslationTaskEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM translation_tasks WHERE state IN ('QUEUED', 'RUNNING'))")
    abstract suspend fun hasRunnableTasks(): Boolean

    @Query("SELECT * FROM translation_tasks WHERE state IN ('QUEUED', 'RUNNING')")
    abstract suspend fun getRunnableTasks(): List<TranslationTaskEntity>

    @Query("SELECT * FROM translation_tasks")
    abstract suspend fun getAllTasks(): List<TranslationTaskEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertTask(task: TranslationTaskEntity)

    @Query("UPDATE translation_tasks SET state = :state, updated_at = :updatedAt WHERE id = :taskId")
    abstract suspend fun updateTaskState(taskId: String, state: String, updatedAt: Long): Int

    @Query("UPDATE translation_tasks SET item_total = :itemTotal, updated_at = :updatedAt WHERE id = :taskId AND state = 'RUNNING'")
    abstract suspend fun setTaskItemTotal(taskId: String, itemTotal: Int, updatedAt: Long): Int

    @Query(
        "UPDATE translation_tasks SET checkpoint_json = :checkpointJson, completed_items = :completedItems, " +
            "failed_items = failed_items + :newFailures, " +
            "failure_code = CASE WHEN :unknownResponseIds THEN 'UNKNOWN_RESPONSE_IDS' ELSE failure_code END, " +
            "updated_at = :updatedAt WHERE id = :taskId AND state = 'RUNNING'",
    )
    abstract suspend fun updateTranslationCheckpoint(
        taskId: String,
        checkpointJson: String,
        completedItems: Int,
        newFailures: Int,
        unknownResponseIds: Boolean,
        updatedAt: Long,
    ): Int

    @Query("UPDATE translation_projects SET project_state = :projectState, updated_at = :updatedAt WHERE id = :projectId")
    abstract suspend fun updateProjectState(projectId: String, projectState: String, updatedAt: Long): Int

    @Query(
        "UPDATE translation_projects SET project_state = 'PROCESSING', source_language = :sourceLanguage, " +
            "target_language = :targetLanguage, revision = revision + 1, updated_at = :updatedAt WHERE id = :projectId",
    )
    abstract suspend fun setTranslationProjectLanguages(
        projectId: String,
        sourceLanguage: String,
        targetLanguage: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE translation_tasks SET state = 'CANCELLED', updated_at = :updatedAt " +
            "WHERE id = :taskId AND state IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun cancelTask(taskId: String, updatedAt: Long): Int

    @Transaction
    open suspend fun cancelTaskAndPauseProject(taskId: String, updatedAt: Long): Int {
        val task = getTask(taskId) ?: return 0
        val changed = cancelTask(taskId, updatedAt)
        if (changed == 1) updateProjectState(task.projectId, TranslationProjectState.PAUSED.name, updatedAt)
        return changed
    }

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

    @Transaction
    open suspend fun failQueuedTaskAndFailProject(taskId: String, failureCode: String, updatedAt: Long): Int {
        val task = getTask(taskId) ?: return 0
        val changed = failQueuedTask(taskId, failureCode, updatedAt)
        if (changed == 1) updateProjectState(task.projectId, TranslationProjectState.FAILED.name, updatedAt)
        return changed
    }

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

    @Transaction
    open suspend fun failTaskAndFailProject(taskId: String, failureCode: String, updatedAt: Long): Int {
        val task = getTask(taskId) ?: return 0
        val changed = failTask(taskId, failureCode, updatedAt)
        if (changed == 1) updateProjectState(task.projectId, TranslationProjectState.FAILED.name, updatedAt)
        return changed
    }

    @Query("UPDATE translation_tasks SET state = 'COMPLETED', failure_code = NULL, updated_at = :updatedAt WHERE id = :taskId AND state = 'RUNNING'")
    abstract suspend fun completeTask(taskId: String, updatedAt: Long): Int

    @Query(
        "UPDATE translation_tasks SET state = CASE WHEN failed_items > 0 OR failure_code IS NOT NULL " +
            "THEN 'COMPLETED_WITH_ERRORS' ELSE 'COMPLETED' END, updated_at = :updatedAt " +
            "WHERE id = :taskId AND state = 'RUNNING'",
    )
    abstract suspend fun completeTranslationTask(taskId: String, updatedAt: Long): Int

    @Transaction
    open suspend fun completeTranslationTaskAndReadyProject(taskId: String, updatedAt: Long): Int {
        val task = getTask(taskId) ?: return 0
        val changed = completeTranslationTask(taskId, updatedAt)
        if (changed == 1) updateProjectState(task.projectId, TranslationProjectState.READY.name, updatedAt)
        return changed
    }

    @Transaction
    open suspend fun commitTranslatedBatch(
        taskId: String,
        entries: List<TranslationEntryEntity>,
        checkpointJson: String,
        completedItems: Int,
        newFailures: Int,
        unknownResponseIds: Boolean,
        updatedAt: Long,
    ): Boolean {
        val task = getTask(taskId) ?: return false
        if (task.state != "RUNNING") return false
        if (entries.isNotEmpty()) upsertEntries(entries)
        if (updateTranslationCheckpoint(
                taskId = taskId,
                checkpointJson = checkpointJson,
                completedItems = completedItems,
                newFailures = newFailures,
                unknownResponseIds = unknownResponseIds,
                updatedAt = updatedAt,
            ) != 1
        ) return false
        val project = getProject(task.projectId) ?: return false
        upsertProject(project.copy(revision = project.revision + 1, updatedAt = updatedAt))
        return true
    }

    @Transaction
    open suspend fun completeTaskAndReadyProject(taskId: String, updatedAt: Long): Int {
        val task = getTask(taskId) ?: return 0
        val changed = completeTask(taskId, updatedAt)
        if (changed == 1) updateProjectState(task.projectId, TranslationProjectState.READY.name, updatedAt)
        return changed
    }

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
        updateProjectState(task.projectId, TranslationProjectState.PROCESSING.name, task.createdAt)
        return true
    }

    @Transaction
    open suspend fun createTranslationTaskIfProjectIdle(
        task: TranslationTaskEntity,
        sourceLanguage: String,
        targetLanguage: String,
        expectedGlossaryRevision: Long,
    ): Boolean {
        val project = getProject(task.projectId) ?: return false
        if (project.glossaryRevision != expectedGlossaryRevision || getActiveTask(task.projectId) != null) return false
        insertTask(task)
        setTranslationProjectLanguages(task.projectId, sourceLanguage, targetLanguage, task.createdAt)
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
        updateProjectState(task.projectId, TranslationProjectState.PROCESSING.name, task.createdAt)
        return true
    }

    @Transaction
    open suspend fun createTranslationRetryTask(task: TranslationTaskEntity, previousTaskId: String): Boolean {
        val previous = getTask(previousTaskId) ?: return false
        if (previous.type != "TRANSLATE" || previous.projectId != task.projectId ||
            previous.state !in setOf("FAILED", "CANCELLED", "COMPLETED_WITH_ERRORS")
        ) return false
        if (getProject(task.projectId) == null || getActiveTask(task.projectId) != null) return false
        resetFailedEntriesForRetry(task.projectId)
        insertTask(task.copy(configSnapshotJson = previous.configSnapshotJson))
        updateProjectState(task.projectId, TranslationProjectState.PROCESSING.name, task.createdAt)
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
