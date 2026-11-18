package com.tyranor.next.core.translation

import android.content.Context
import com.tyranor.next.core.translation.service.ProofreadInput
import com.tyranor.next.core.translation.service.TranslationItemFailureCode
import com.tyranor.next.core.translation.service.TranslationServiceClient
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceRepository
import com.tyranor.next.core.translation.storage.TranslationEntryEntity
import com.tyranor.next.core.translation.storage.TranslationProofreadSuggestionEntity
import com.tyranor.next.core.translation.storage.TranslationReviewIssueEntity
import com.tyranor.next.core.translation.storage.TranslationTaskEntity
import com.tyranor.next.core.translation.storage.TranslationWorkbenchDao
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Executes AI proofreading without replacing current translations; each batch commits with its checkpoint. */
internal class TranslationProofreadTaskProcessor(
    context: Context,
    private val dao: TranslationWorkbenchDao,
    private val reportProgress: suspend (completed: Int, total: Int) -> Unit,
) {
    private val appContext = context.applicationContext

    suspend fun process(task: TranslationTaskEntity) {
        val taskId = task.id
        if (dao.markTaskRunning(taskId, System.currentTimeMillis()) != 1) return
        try {
            val snapshot = TranslationProofreadTaskSnapshot.decode(task.configSnapshotJson)
            val taskRow = dao.getTask(taskId) ?: return
            val total = taskRow.itemTotal.takeIf { it > 0 }
                ?: dao.countProofreadCandidates(task.projectId, snapshot.scope.name, snapshot.retryFailed)
            if (total <= 0) {
                dao.completeTranslationTaskAndReadyProject(taskId, System.currentTimeMillis())
                return
            }
            if (taskRow.itemTotal == 0) dao.setTaskItemTotal(taskId, total, System.currentTimeMillis())
            reportProgress(taskRow.completedItems, total)
            val client = TranslationServiceClient(appContext)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (dao.getTask(taskId)?.state != "RUNNING") return
                val entries = dao.getProofreadCandidates(
                    projectId = task.projectId,
                    scope = snapshot.scope.name,
                    retryFailed = snapshot.retryFailed,
                    taskId = taskId,
                    limit = BATCH_SIZE,
                )
                if (entries.isEmpty()) break
                val response = client.proofreadBatch(
                    profile = snapshot.toServiceProfile(),
                    secretRef = snapshot.secretRef,
                    sourceLanguage = snapshot.sourceLanguage,
                    targetLanguage = snapshot.targetLanguage,
                    inputs = entries.map { entry ->
                        ProofreadInput(entry.id, entry.sourceText, entry.translatedText.orEmpty())
                    },
                )
                currentCoroutineContext().ensureActive()
                val outputsById = response.successes.associateBy { it.entryId }
                val now = System.currentTimeMillis()
                val updatedEntries = entries.map { entry ->
                    entry.copy(
                        reviewState = if (outputsById.containsKey(entry.id)) "AI_REVIEWED" else "PROOFREAD_FAILED",
                        lastProofreadTaskId = taskId,
                    )
                }
                val suggestions = entries.mapNotNull { entry ->
                    val output = outputsById[entry.id] ?: return@mapNotNull null
                    if (output.suggestedTranslation == entry.translatedText && output.issueCodes.isEmpty()) return@mapNotNull null
                    TranslationProofreadSuggestionEntity(
                        id = UUID.randomUUID().toString(),
                        projectId = task.projectId,
                        entryId = entry.id,
                        taskId = taskId,
                        suggestedTranslation = output.suggestedTranslation,
                        state = "PENDING",
                        createdAt = now,
                    )
                }
                val issues = entries.flatMap { entry ->
                    outputsById[entry.id]?.issueCodes.orEmpty().map { code ->
                        TranslationReviewIssueEntity(
                            id = UUID.randomUUID().toString(),
                            projectId = task.projectId,
                            entryId = entry.id,
                            taskId = taskId,
                            source = "AI",
                            code = code,
                            severity = "SUGGESTION",
                            state = "OPEN",
                            updatedAt = now,
                        )
                    }
                }
                val previous = dao.getTask(taskId) ?: return
                val committedCount = previous.completedItems + entries.size
                val committed = dao.commitProofreadBatch(
                    taskId = taskId,
                    entries = updatedEntries,
                    suggestions = suggestions,
                    issues = issues,
                    checkpointJson = JSONObject()
                        .put("lastCommittedEntryIds", JSONArray(entries.map(TranslationEntryEntity::id)))
                        .put("unknownResponseIds", JSONArray(response.unknownResponseIds.sorted()))
                        .toString(),
                    completedItems = committedCount,
                    newFailures = response.itemFailures.size,
                    unknownResponseIds = response.unknownResponseIds.isNotEmpty(),
                    updatedAt = now,
                )
                if (!committed) return
                val fresh = dao.getTask(taskId) ?: return
                reportProgress(fresh.completedItems, fresh.itemTotal)
            }
            dao.completeTranslationTaskAndReadyProject(taskId, System.currentTimeMillis())
            cleanupSecrets()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TranslationServiceException) {
            dao.failTaskAndFailProject(taskId, failure.code.name, System.currentTimeMillis())
            cleanupSecrets()
        } catch (failure: Exception) {
            dao.failTaskAndFailProject(taskId, TranslationWorkbenchError.STORAGE_FAILURE.name, System.currentTimeMillis())
            cleanupSecrets()
        }
    }

    private suspend fun cleanupSecrets() {
        try {
            TranslationServiceRepository(appContext).cleanupUnusedSecrets()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A cleanup failure cannot roll back already committed suggestions.
        }
    }

    companion object {
        private const val BATCH_SIZE = 30
    }
}
