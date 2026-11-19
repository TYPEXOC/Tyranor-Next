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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Runs AI proofreading without replacing translations; suggestions and checkpoints commit together. */
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
            val profile = snapshot.toServiceProfile()
            val protocolMaxBatchSize = client.maxBatchSize(snapshot.protocolId)
            val batchSize = TranslationTaskRequestPolicy.batchSize(
                snapshot.protocolId,
                snapshot.taskSettings,
                protocolMaxBatchSize,
            )
            val batchConcurrency = snapshot.taskSettings.requestConcurrency(snapshot.protocolId.category)
            val windowSize = (batchSize * batchConcurrency)
                .coerceAtMost(MAX_WINDOW_SIZE)
            val commitMutex = Mutex()

            suspend fun commitBatch(
                batchEntries: List<TranslationEntryEntity>,
                suggestions: List<TranslationProofreadSuggestionEntity>,
                issues: List<TranslationReviewIssueEntity>,
                failedCount: Int,
                unknownResponseIds: Set<String> = emptySet(),
            ): Boolean = commitMutex.withLock {
                val previous = dao.getTask(taskId) ?: return@withLock false
                val completedCount = previous.completedItems + batchEntries.size
                val committed = dao.commitProofreadBatch(
                    taskId = taskId,
                    entries = batchEntries,
                    suggestions = suggestions,
                    issues = issues,
                    checkpointJson = JSONObject()
                        .put("lastCommittedEntryIds", JSONArray(batchEntries.map(TranslationEntryEntity::id)))
                        .put("unknownResponseIds", JSONArray(unknownResponseIds.sorted()))
                        .toString(),
                    completedItems = completedCount,
                    newFailures = failedCount,
                    unknownResponseIds = unknownResponseIds.isNotEmpty(),
                    updatedAt = System.currentTimeMillis(),
                )
                if (committed) {
                    val fresh = dao.getTask(taskId) ?: return@withLock false
                    reportProgress(fresh.completedItems, fresh.itemTotal)
                }
                committed
            }

            while (true) {
                currentCoroutineContext().ensureActive()
                if (dao.getTask(taskId)?.state != "RUNNING") return
                val entries = dao.getProofreadCandidates(
                    projectId = task.projectId,
                    scope = snapshot.scope.name,
                    retryFailed = snapshot.retryFailed,
                    taskId = taskId,
                    limit = windowSize,
                )
                if (entries.isEmpty()) break

                val plan = TranslationTaskRequestPolicy.proofreadingBatches(
                    entries = entries,
                    settings = snapshot.taskSettings,
                    protocolMaxBatchSize = protocolMaxBatchSize,
                )

                val failures = TranslationTaskRequestPolicy.executeBatches(plan.batches, batchConcurrency) { batch ->
                    try {
                        currentCoroutineContext().ensureActive()
                        TranslationTaskRequestLimiter.awaitRequestSlot(profile)
                        currentCoroutineContext().ensureActive()
                        val response = client.proofreadBatch(
                            profile = profile,
                            secretRef = snapshot.secretRef,
                            sourceLanguage = snapshot.sourceLanguage,
                            targetLanguage = snapshot.targetLanguage,
                            inputs = batch.map { entry ->
                                ProofreadInput(entry.id, entry.sourceText, entry.translatedText.orEmpty())
                            },
                        )
                        currentCoroutineContext().ensureActive()
                        val outputsById = response.successes.associateBy { it.entryId }
                        val now = System.currentTimeMillis()
                        val updatedEntries = batch.map { entry ->
                            entry.copy(
                                reviewState = if (outputsById.containsKey(entry.id)) "AI_REVIEWED" else "PROOFREAD_FAILED",
                                lastProofreadTaskId = taskId,
                            )
                        }
                        val suggestions = batch.mapNotNull { entry ->
                            val output = outputsById[entry.id] ?: return@mapNotNull null
                            if (output.suggestedTranslation == entry.translatedText && output.issueCodes.isEmpty()) {
                                return@mapNotNull null
                            }
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
                        val issues = batch.flatMap { entry ->
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
                        if (!commitBatch(
                                batchEntries = updatedEntries,
                                suggestions = suggestions,
                                issues = issues,
                                failedCount = response.itemFailures.size,
                                unknownResponseIds = response.unknownResponseIds,
                            )
                        ) {
                            throw CancellationException("Proofreading task is no longer running")
                        }
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        failure
                    }
                }
                failures.filterNotNull().firstOrNull()?.let { throw it }
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
        private const val MAX_WINDOW_SIZE = 400
    }
}
