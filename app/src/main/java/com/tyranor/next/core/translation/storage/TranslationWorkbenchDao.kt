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
import org.json.JSONObject
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class TranslationWorkbenchDao {
    @Query(
        """SELECT p.id, p.title, p.project_state, p.engine_id, p.source_type, p.source_tree_uri, p.source_language, p.target_language,
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
            p.review_completed_at, p.updated_at
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
        "SELECT e.id, e.source_key, e.source_text, e.translated_text, e.source_file, e.source_pointer, e.text_type, " +
            "e.review_state, (SELECT s.id FROM translation_proofread_suggestions s " +
            "WHERE s.entry_id = e.id AND s.state = 'PENDING' ORDER BY s.created_at DESC LIMIT 1) AS suggestion_id, " +
            "(SELECT s.suggested_translation FROM translation_proofread_suggestions s " +
            "WHERE s.entry_id = e.id AND s.state = 'PENDING' ORDER BY s.created_at DESC LIMIT 1) AS suggested_translation " +
            "FROM translation_entries e WHERE project_id = :projectId ORDER BY source_key",
    )
    abstract fun observeEntries(projectId: String): Flow<List<TranslationEntryRow>>

    @Query("SELECT id, entry_id, source, code, severity, state FROM translation_review_issues WHERE project_id = :projectId ORDER BY updated_at DESC")
    abstract fun observeReviewIssues(projectId: String): Flow<List<TranslationReviewIssueRow>>

    @Query("SELECT * FROM translation_proofread_suggestions WHERE id = :id LIMIT 1")
    abstract suspend fun getProofreadSuggestion(id: String): TranslationProofreadSuggestionEntity?

    @Query("SELECT * FROM translation_entries WHERE id = :id LIMIT 1")
    abstract suspend fun getEntry(id: String): TranslationEntryEntity?

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

    @Query(
        "SELECT * FROM translation_entries e WHERE e.project_id = :projectId " +
            "AND e.translated_text IS NOT NULL AND e.translated_text != '' " +
            "AND (:retryFailed = 0 OR e.review_state IN ('UNREVIEWED', 'PROOFREAD_FAILED')) " +
            "AND (:scope = 'ALL_TRANSLATED' OR EXISTS (" +
            "SELECT 1 FROM translation_review_issues i WHERE i.entry_id = e.id AND i.source = 'RULE' AND i.state = 'OPEN')) " +
            "AND (e.last_proofread_task_id IS NULL OR e.last_proofread_task_id != :taskId) " +
            "ORDER BY e.source_key LIMIT :limit",
    )
    abstract suspend fun getProofreadCandidates(
        projectId: String,
        scope: String,
        retryFailed: Boolean,
        taskId: String,
        limit: Int,
    ): List<TranslationEntryEntity>

    @Query(
        "SELECT COUNT(*) FROM translation_entries e WHERE e.project_id = :projectId " +
            "AND e.translated_text IS NOT NULL AND e.translated_text != '' " +
            "AND (:retryFailed = 0 OR e.review_state IN ('UNREVIEWED', 'PROOFREAD_FAILED')) " +
            "AND (:scope = 'ALL_TRANSLATED' OR EXISTS (" +
            "SELECT 1 FROM translation_review_issues i WHERE i.entry_id = e.id AND i.source = 'RULE' AND i.state = 'OPEN'))",
    )
    abstract suspend fun countProofreadCandidates(projectId: String, scope: String, retryFailed: Boolean): Int

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

    @Query(
        "SELECT g.id, g.name, g.revision, g.source_project_id, g.updated_at, " +
            "COUNT(e.id) AS term_count FROM translation_glossaries g " +
            "LEFT JOIN translation_glossary_entries e ON e.glossary_id = g.id " +
            "GROUP BY g.id ORDER BY g.updated_at DESC, g.name COLLATE NOCASE",
    )
    abstract fun observeReusableGlossaries(): Flow<List<TranslationGlossaryRow>>

    @Query("SELECT * FROM translation_glossaries WHERE id = :id LIMIT 1")
    abstract suspend fun getReusableGlossary(id: String): TranslationGlossaryEntity?

    @Query("SELECT * FROM translation_glossary_entries WHERE glossary_id = :glossaryId ORDER BY source_term COLLATE NOCASE")
    abstract fun observeReusableGlossaryEntries(glossaryId: String): Flow<List<TranslationGlossaryEntryEntity>>

    @Query("SELECT * FROM translation_glossary_entries WHERE glossary_id = :glossaryId ORDER BY source_term COLLATE NOCASE")
    abstract suspend fun getReusableGlossaryEntries(glossaryId: String): List<TranslationGlossaryEntryEntity>

    @Upsert
    abstract suspend fun upsertReusableGlossary(glossary: TranslationGlossaryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertReusableGlossaryEntries(entries: List<TranslationGlossaryEntryEntity>)

    @Query("DELETE FROM translation_glossary_entries WHERE glossary_id = :glossaryId")
    abstract suspend fun deleteReusableGlossaryEntries(glossaryId: String)

    @Query("DELETE FROM translation_glossaries WHERE id = :glossaryId")
    abstract suspend fun deleteReusableGlossary(glossaryId: String): Int

    @Transaction
    open suspend fun replaceReusableGlossary(
        glossary: TranslationGlossaryEntity,
        entries: List<TranslationGlossaryEntryEntity>,
        expectedRevision: Long?,
    ): Boolean {
        val current = getReusableGlossary(glossary.id)
        if ((expectedRevision == null && current != null) ||
            (expectedRevision != null && current?.revision != expectedRevision)
        ) return false
        upsertReusableGlossary(glossary)
        deleteReusableGlossaryEntries(glossary.id)
        if (entries.isNotEmpty()) insertReusableGlossaryEntries(entries)
        return true
    }

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

    @Query("UPDATE translation_entries SET translated_text = :translation, status = :status, review_state = 'UNREVIEWED', failure_code = NULL WHERE id = :entryId AND project_id = :projectId")
    abstract suspend fun updateManualTranslation(projectId: String, entryId: String, translation: String?, status: String): Int

    @Query("UPDATE translation_entries SET review_state = 'CONFIRMED' WHERE id = :entryId AND project_id = :projectId AND translated_text IS NOT NULL")
    abstract suspend fun setEntryConfirmed(projectId: String, entryId: String): Int

    @Query("UPDATE translation_review_issues SET state = :state, updated_at = :updatedAt WHERE id = :issueId AND project_id = :projectId AND state = 'OPEN'")
    abstract suspend fun updateReviewIssueState(projectId: String, issueId: String, state: String, updatedAt: Long): Int

    @Query("UPDATE translation_review_issues SET state = 'RESOLVED', updated_at = :updatedAt WHERE project_id = :projectId AND entry_id = :entryId AND state = 'OPEN'")
    abstract suspend fun resolveEntryIssues(projectId: String, entryId: String, updatedAt: Long): Int

    @Query("UPDATE translation_proofread_suggestions SET state = 'DISMISSED' WHERE project_id = :projectId AND entry_id = :entryId AND state = 'PENDING'")
    abstract suspend fun dismissEntrySuggestions(projectId: String, entryId: String): Int

    @Query("UPDATE translation_proofread_suggestions SET state = 'DISMISSED' WHERE project_id = :projectId AND entry_id = :entryId AND id != :suggestionId AND state = 'PENDING'")
    abstract suspend fun dismissOtherEntrySuggestions(projectId: String, entryId: String, suggestionId: String): Int

    @Query("UPDATE translation_proofread_suggestions SET state = :state WHERE id = :suggestionId AND project_id = :projectId AND state = 'PENDING'")
    abstract suspend fun updateSuggestionState(projectId: String, suggestionId: String, state: String): Int

    @Query("UPDATE translation_proofread_suggestions SET state = 'SUPERSEDED' WHERE entry_id IN (:entryIds) AND state = 'PENDING'")
    abstract suspend fun supersedePendingSuggestions(entryIds: List<String>): Int

    @Query("UPDATE translation_review_issues SET state = 'SUPERSEDED', updated_at = :updatedAt WHERE entry_id IN (:entryIds) AND source = 'AI' AND state = 'OPEN'")
    abstract suspend fun supersedeOpenAiIssues(entryIds: List<String>, updatedAt: Long): Int

    @Query("DELETE FROM translation_review_issues WHERE project_id = :projectId AND source = 'RULE' AND state = 'OPEN'")
    abstract suspend fun deleteOpenRuleIssues(projectId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertReviewIssues(issues: List<TranslationReviewIssueEntity>)

    @Upsert
    abstract suspend fun upsertProofreadSuggestions(suggestions: List<TranslationProofreadSuggestionEntity>)

    @Upsert
    abstract suspend fun upsertReviewIssues(issues: List<TranslationReviewIssueEntity>)

    @Query("UPDATE translation_projects SET review_completed_at = NULL, revision = revision + 1, updated_at = :updatedAt WHERE id = :projectId")
    abstract suspend fun markReviewChanged(projectId: String, updatedAt: Long): Int

    @Query("UPDATE translation_projects SET review_completed_at = :completedAt, revision = revision + 1, updated_at = :completedAt WHERE id = :projectId")
    abstract suspend fun setReviewCompleted(projectId: String, completedAt: Long): Int

    @Query("UPDATE translation_projects SET review_completed_at = NULL, revision = revision + 1, updated_at = :updatedAt WHERE id = :projectId")
    abstract suspend fun bumpProjectReviewRevision(projectId: String, updatedAt: Long): Int

    @Transaction
    open suspend fun replaceOpenRuleIssues(projectId: String, issues: List<TranslationReviewIssueEntity>, updatedAt: Long) {
        if (getProject(projectId) == null) throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_NOT_FOUND)
        if (getActiveTask(projectId) != null) throw TranslationWorkbenchException(TranslationWorkbenchError.TASK_ALREADY_ACTIVE)
        deleteOpenRuleIssues(projectId)
        if (issues.isNotEmpty()) insertReviewIssues(issues)
        bumpProjectReviewRevision(projectId, updatedAt)
    }

    @Transaction
    open suspend fun saveManualTranslation(projectId: String, entryId: String, translation: String?): Boolean {
        if (getProject(projectId) == null || getActiveTask(projectId) != null) return false
        val status = if (translation.isNullOrBlank()) "UNTRANSLATED" else "TRANSLATED"
        if (updateManualTranslation(projectId, entryId, translation?.takeIf(String::isNotBlank), status) != 1) return false
        dismissEntrySuggestions(projectId, entryId)
        markReviewChanged(projectId, System.currentTimeMillis())
        return true
    }

    @Transaction
    open suspend fun confirmEntry(projectId: String, entryId: String, updatedAt: Long): Boolean {
        if (getProject(projectId) == null || getActiveTask(projectId) != null) return false
        if (setEntryConfirmed(projectId, entryId) != 1) return false
        resolveEntryIssues(projectId, entryId, updatedAt)
        dismissEntrySuggestions(projectId, entryId)
        bumpProjectReviewRevision(projectId, updatedAt)
        return true
    }

    @Transaction
    open suspend fun changeReviewIssueState(projectId: String, issueId: String, state: String, updatedAt: Long): Boolean {
        if (getProject(projectId) == null || getActiveTask(projectId) != null) return false
        if (updateReviewIssueState(projectId, issueId, state, updatedAt) != 1) return false
        bumpProjectReviewRevision(projectId, updatedAt)
        return true
    }

    @Transaction
    open suspend fun completeReviewRound(projectId: String, completedAt: Long): Boolean {
        if (getProject(projectId) == null || getActiveTask(projectId) != null) return false
        return setReviewCompleted(projectId, completedAt) == 1
    }

    @Transaction
    open suspend fun applySuggestion(projectId: String, entryId: String, suggestionId: String, suggestedText: String): Boolean {
        if (getProject(projectId) == null || getActiveTask(projectId) != null) return false
        if (updateManualTranslation(projectId, entryId, suggestedText, "TRANSLATED") != 1) return false
        if (updateSuggestionState(projectId, suggestionId, "ACCEPTED") != 1) return false
        dismissOtherEntrySuggestions(projectId, entryId, suggestionId)
        markReviewChanged(projectId, System.currentTimeMillis())
        return true
    }

    @Transaction
    open suspend fun createProofreadTaskIfProjectIdle(task: TranslationTaskEntity, scope: String): Boolean {
        val project = getProject(task.projectId) ?: return false
        if (getActiveTask(task.projectId) != null) return false
        val snapshot = JSONObject(task.configSnapshotJson)
        val itemTotal = countProofreadCandidates(task.projectId, scope, retryFailed = false)
        val glossaryId = snapshot.optString("glossaryId").takeIf(String::isNotBlank)
        if (project.selectedGlossaryId != glossaryId ||
            !matchesGlossaryRevision(glossaryId, snapshot.optLong("glossaryRevision", 0L)) || itemTotal <= 0
        ) return false
        insertTask(task.copy(itemTotal = itemTotal))
        setTranslationProjectLanguages(
            task.projectId,
            snapshot.optString("sourceLanguage"),
            snapshot.optString("targetLanguage"),
            task.createdAt,
        )
        updateProjectState(task.projectId, TranslationProjectState.PROCESSING.name, task.createdAt)
        return true
    }

    @Transaction
    open suspend fun commitProofreadBatch(
        taskId: String,
        entries: List<TranslationEntryEntity>,
        suggestions: List<TranslationProofreadSuggestionEntity>,
        issues: List<TranslationReviewIssueEntity>,
        checkpointJson: String,
        completedItems: Int,
        newFailures: Int,
        unknownResponseIds: Boolean,
        updatedAt: Long,
    ): Boolean {
        val task = getTask(taskId) ?: return false
        if (task.state != "RUNNING") return false
        val processedIds = entries.map(TranslationEntryEntity::id)
        if (entries.isNotEmpty()) upsertEntries(entries)
        if (processedIds.isNotEmpty()) {
            supersedePendingSuggestions(processedIds)
            supersedeOpenAiIssues(processedIds, updatedAt)
        }
        if (suggestions.isNotEmpty()) upsertProofreadSuggestions(suggestions)
        if (issues.isNotEmpty()) upsertReviewIssues(issues)
        if (updateTranslationCheckpoint(taskId, checkpointJson, completedItems, newFailures, unknownResponseIds, updatedAt) != 1) return false
        val project = getProject(task.projectId) ?: return false
        upsertProject(project.copy(reviewCompletedAt = null, revision = project.revision + 1, updatedAt = updatedAt))
        return true
    }

    @Transaction
    open suspend fun createProofreadRetryTask(task: TranslationTaskEntity, previousTaskId: String): Boolean {
        val previous = getTask(previousTaskId) ?: return false
        if (previous.type != "AI_PROOFREAD" || previous.projectId != task.projectId ||
            previous.state !in setOf("FAILED", "CANCELLED", "COMPLETED_WITH_ERRORS") ||
            getProject(task.projectId) == null || getActiveTask(task.projectId) != null
        ) return false
        val snapshot = org.json.JSONObject(task.configSnapshotJson)
        val scope = snapshot.optString("scope")
        val itemTotal = countProofreadCandidates(task.projectId, scope, retryFailed = true)
        if (itemTotal <= 0) return false
        insertTask(task.copy(itemTotal = itemTotal))
        updateProjectState(task.projectId, TranslationProjectState.PROCESSING.name, task.createdAt)
        return true
    }

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
        glossaryId: String?,
        expectedGlossaryRevision: Long,
    ): Boolean {
        val project = getProject(task.projectId) ?: return false
        if (getActiveTask(task.projectId) != null ||
            !matchesGlossaryRevision(glossaryId, expectedGlossaryRevision)
        ) return false
        insertTask(task)
        upsertProject(
            project.copy(
                selectedGlossaryId = glossaryId,
                revision = project.revision + 1,
                updatedAt = task.createdAt,
            ),
        )
        setTranslationProjectLanguages(task.projectId, sourceLanguage, targetLanguage, task.createdAt)
        return true
    }

    private suspend fun matchesGlossaryRevision(glossaryId: String?, expectedRevision: Long): Boolean =
        if (glossaryId == null) expectedRevision == 0L
        else getReusableGlossary(glossaryId)?.revision == expectedRevision

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
