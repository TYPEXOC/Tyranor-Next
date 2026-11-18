package com.tyranor.next.core.translation

import android.content.Context
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationItemFailureCode
import com.tyranor.next.core.translation.service.TranslationServiceClient
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceRepository
import com.tyranor.next.core.translation.storage.TranslationEntryEntity
import com.tyranor.next.core.translation.storage.TranslationTaskEntity
import com.tyranor.next.core.translation.storage.TranslationWorkbenchDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Executes a durable translation task in batches; a batch's results and checkpoint commit together. */
internal class TranslationBatchTaskProcessor(
    context: Context,
    private val dao: TranslationWorkbenchDao,
    private val reportProgress: suspend (completed: Int, total: Int) -> Unit,
) {
    private val appContext = context.applicationContext

    suspend fun process(task: TranslationTaskEntity) {
        val taskId = task.id
        if (dao.markTaskRunning(taskId, System.currentTimeMillis()) != 1) return
        try {
            val snapshot = TranslationTaskSnapshot.decode(task.configSnapshotJson)
            val existingTask = dao.getTask(taskId) ?: return
            val total = if (existingTask.itemTotal > 0) {
                existingTask.itemTotal
            } else {
                dao.countTranslationCandidates(task.projectId, snapshot.retryFailed)
            }
            if (total <= 0) {
                dao.completeTranslationTaskAndReadyProject(taskId, System.currentTimeMillis())
                return
            }
            if (existingTask.itemTotal == 0) dao.setTaskItemTotal(taskId, total, System.currentTimeMillis())

            val processedIds = decodeProcessedIds(existingTask.checkpointJson).toMutableSet()
            val unknownIds = decodeUnknownIds(existingTask.checkpointJson).toMutableSet()
            reportProgress(existingTask.completedItems, total)
            val client = TranslationServiceClient(appContext)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (dao.getTask(taskId)?.state != "RUNNING") return
                val entries = dao.getTranslationCandidates(
                    projectId = task.projectId,
                    retryFailed = snapshot.retryFailed,
                    limit = BATCH_SIZE,
                )
                if (entries.isEmpty()) break
                val inputs = entries.map { entry ->
                    TranslationInput(
                        entryId = entry.id,
                        sourceText = entry.sourceText,
                        sourceLanguage = snapshot.sourceLanguage,
                        targetLanguage = snapshot.targetLanguage,
                    )
                }
                val response = client.translateBatch(
                    profile = snapshot.toServiceProfile(),
                    secretRef = snapshot.secretRef,
                    sourceLanguage = snapshot.sourceLanguage,
                    targetLanguage = snapshot.targetLanguage,
                    inputs = inputs,
                )
                currentCoroutineContext().ensureActive()
                val outputById = response.successes.associateBy { it.entryId }
                val updated = entries.map { entry ->
                    val output = outputById[entry.id]
                    when {
                        output != null -> entry.copy(
                            translatedText = output.translatedText,
                            status = "TRANSLATED",
                            failureCode = null,
                        )
                        else -> entry.copy(
                            translatedText = null,
                            status = "FAILED",
                            failureCode = (response.itemFailures[entry.id]
                                ?: TranslationItemFailureCode.MISSING_RESPONSE).name,
                        )
                    }
                }
                processedIds += entries.map(TranslationEntryEntity::id)
                unknownIds += response.unknownResponseIds
                val committed = dao.commitTranslatedBatch(
                    taskId = taskId,
                    entries = updated,
                    checkpointJson = encodeCheckpoint(processedIds, unknownIds),
                    completedItems = processedIds.size,
                    newFailures = updated.count { it.status == "FAILED" },
                    unknownResponseIds = response.unknownResponseIds.isNotEmpty(),
                    updatedAt = System.currentTimeMillis(),
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
            // A cleanup failure must not roll back or relabel already committed translations.
        }
    }

    private fun decodeProcessedIds(json: String): Set<String> = try {
        val value = JSONObject(json)
        value.optJSONArray(CHECKPOINT_PROCESSED)?.toStringSet().orEmpty()
    } catch (_: Exception) {
        runCatching { JSONArray(json).toStringSet() }.getOrDefault(emptySet())
    }

    private fun decodeUnknownIds(json: String): Set<String> = runCatching {
        JSONObject(json).optJSONArray(CHECKPOINT_UNKNOWN)?.toStringSet().orEmpty()
    }.getOrDefault(emptySet())

    private fun JSONArray.toStringSet(): Set<String> =
        (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotBlank) }.toSet()

    private fun encodeCheckpoint(processed: Set<String>, unknown: Set<String>): String = JSONObject()
        .put(CHECKPOINT_PROCESSED, JSONArray(processed.sorted()))
        .put(CHECKPOINT_UNKNOWN, JSONArray(unknown.sorted()))
        .toString()

    companion object {
        private const val BATCH_SIZE = 30
        private const val CHECKPOINT_PROCESSED = "processedEntryIds"
        private const val CHECKPOINT_UNKNOWN = "unknownResponseIds"
    }
}
